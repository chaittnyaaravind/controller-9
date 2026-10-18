use crate::abi;
use nine_host_core::backend::{Backend, BackendError, BackendKind, BackendStatus, VirtualPad};
use nine_host_core::hid::{report, REPORT_LEN};
use nine_proto::input::InputState;
use std::sync::{Arc, Mutex};
use windows_sys::core::GUID;
use windows_sys::Win32::Devices::DeviceAndDriverInstallation::{
    CM_Get_Device_Interface_ListW, CM_Get_Device_Interface_List_SizeW, CM_GET_DEVICE_INTERFACE_LIST_PRESENT, CR_SUCCESS,
};
use windows_sys::Win32::Foundation::{CloseHandle, GetLastError, GENERIC_READ, GENERIC_WRITE, HANDLE, INVALID_HANDLE_VALUE};
use windows_sys::Win32::Storage::FileSystem::{CreateFileW, FILE_SHARE_READ, FILE_SHARE_WRITE, OPEN_EXISTING};
use windows_sys::Win32::System::IO::DeviceIoControl;

struct Handle(HANDLE);
// SAFETY: a device HANDLE may be used from any thread; access is serialized by the Mutex below.
unsafe impl Send for Handle {}
impl Drop for Handle {
    // Closing the handle makes the driver delete every pad this process owns (EvtFileCleanup).
    fn drop(&mut self) { unsafe { CloseHandle(self.0) }; }
}

fn interface_path() -> Option<Vec<u16>> {
    let guid = GUID::from_u128(abi::INTERFACE_GUID);
    let mut len = 0u32;
    // SAFETY: out-pointer to a local; documented CM API usage.
    if unsafe { CM_Get_Device_Interface_List_SizeW(&mut len, &guid, std::ptr::null(), CM_GET_DEVICE_INTERFACE_LIST_PRESENT) } != CR_SUCCESS || len <= 1 {
        return None;
    }
    let mut buf = vec![0u16; len as usize];
    // SAFETY: buffer of the size the API asked for.
    if unsafe { CM_Get_Device_Interface_ListW(&guid, std::ptr::null(), buf.as_mut_ptr(), len, CM_GET_DEVICE_INTERFACE_LIST_PRESENT) } != CR_SUCCESS {
        return None;
    }
    let end = buf.iter().position(|&c| c == 0)?;
    if end == 0 { return None; }
    buf.truncate(end + 1);
    Some(buf)
}

fn ioctl(h: HANDLE, code: u32, input: &[u8], out: &mut [u8]) -> Result<u32, u32> {
    let mut ret = 0u32;
    // SAFETY: buffers are valid for the given lengths for the duration of the synchronous call.
    let ok = unsafe { DeviceIoControl(h, code, input.as_ptr().cast(), input.len() as u32, out.as_mut_ptr().cast(), out.len() as u32, &mut ret, std::ptr::null_mut()) };
    if ok == 0 { Err(unsafe { GetLastError() }) } else { Ok(ret) }
}

#[derive(Default)]
pub struct VhfBackend { handle: Mutex<Option<Arc<Mutex<Handle>>>> }

impl VhfBackend {
    fn open(&self) -> Result<Arc<Mutex<Handle>>, String> {
        let mut g = self.handle.lock().unwrap();
        if let Some(h) = g.as_ref() { return Ok(h.clone()); }
        let path = interface_path().ok_or("NineVhf driver is not installed")?;
        // SAFETY: NUL-terminated wide path from the CM API.
        let h = unsafe { CreateFileW(path.as_ptr(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, std::ptr::null(), OPEN_EXISTING, 0, std::ptr::null_mut()) };
        if h == INVALID_HANDLE_VALUE { return Err(format!("cannot open NineVhf device (error {})", unsafe { GetLastError() })); }
        let mut v = [0u8; 4];
        let h = Handle(h);
        match ioctl(h.0, abi::IOCTL_VERSION, &[], &mut v) {
            Ok(4) if u32::from_le_bytes(v) == abi::INTERFACE_VERSION => {}
            _ => return Err("NineVhf driver version mismatch — reinstall the host".into()),
        }
        let h = Arc::new(Mutex::new(h));
        *g = Some(h.clone());
        Ok(h)
    }
}

impl Backend for VhfBackend {
    fn status(&self) -> BackendStatus {
        let s = |available, kind, detail: String, remedy: Option<&str>| BackendStatus {
            name: "windows-vhf".into(), kind, available, max_pads: 4, detail, remedy: remedy.map(String::from) };
        match self.open() {
            Ok(_) => s(true, BackendKind::Native, "NineVhf virtual HID driver ready".into(), None),
            Err(e) => s(false, BackendKind::Blocked, e,
                Some("Install the 9-controller driver from the installer, or see hosts/windows/README.md (test-signing builds need 'bcdedit /set testsigning on')")),
        }
    }

    fn create(&self, slot: u8, _name: &str) -> Result<Box<dyn VirtualPad>, BackendError> {
        let h = self.open().map_err(BackendError::Unavailable)?;
        ioctl(h.lock().unwrap().0, abi::IOCTL_CREATE, &[slot], &mut [])
            .map_err(|e| BackendError::Io(format!("create pad {slot}: error {e}")))?;
        Ok(Box::new(VhfPad { h, slot }))
    }
}

struct VhfPad { h: Arc<Mutex<Handle>>, slot: u8 }

impl VirtualPad for VhfPad {
    fn submit(&mut self, s: &InputState) -> Result<(), BackendError> {
        let mut buf = [0u8; 1 + REPORT_LEN];
        buf[0] = self.slot;
        buf[1..].copy_from_slice(&report(s));
        ioctl(self.h.lock().unwrap().0, abi::IOCTL_SUBMIT, &buf, &mut []).map(|_| ()).map_err(|e| BackendError::Io(format!("submit: error {e}")))
    }
}

impl Drop for VhfPad {
    fn drop(&mut self) {
        let _ = self.submit(&InputState::NEUTRAL);
        let _ = ioctl(self.h.lock().unwrap().0, abi::IOCTL_DESTROY, &[self.slot], &mut []);
    }
}
