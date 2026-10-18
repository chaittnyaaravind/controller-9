//! `nine-devnode install|remove <hardware-id> [inf]` — create or remove the root-enumerated device
//! node the NineVhf driver binds to (what `devcon install/remove` does). Run elevated; the NSIS
//! installer hook calls it after `pnputil /add-driver`.
#[cfg(windows)]
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let code = match (args.get(1).map(String::as_str), args.get(2)) {
        (Some("install"), Some(hwid)) => win::install(hwid, args.get(3).map(String::as_str)),
        (Some("remove"), Some(hwid)) => win::remove(hwid),
        _ => { eprintln!("usage: nine-devnode install|remove <hardware-id> [path\\to\\NineVhf.inf]"); 2 }
    };
    std::process::exit(code);
}

#[cfg(not(windows))]
fn main() {
    eprintln!("nine-devnode is a Windows-only tool");
    std::process::exit(2);
}

#[cfg(windows)]
mod win {
    use windows_sys::core::GUID;
    use windows_sys::Win32::Devices::DeviceAndDriverInstallation::*;
    use windows_sys::Win32::Foundation::{GetLastError, INVALID_HANDLE_VALUE};

    /// GUID_DEVCLASS_HIDCLASS {745a17a0-74d3-11d0-b6fe-00a0c90f57da}
    const HIDCLASS: GUID = GUID::from_u128(0x745a17a0_74d3_11d0_b6fe_00a0c90f57da);

    fn wide(s: &str) -> Vec<u16> { s.encode_utf16().chain(std::iter::once(0)).collect() }
    fn multi_sz(s: &str) -> Vec<u16> { s.encode_utf16().chain([0, 0]).collect() }

    pub fn install(hwid: &str, inf: Option<&str>) -> i32 {
        unsafe {
            let set = SetupDiCreateDeviceInfoList(&HIDCLASS, std::ptr::null_mut());
            if set == INVALID_HANDLE_VALUE as _ { eprintln!("SetupDiCreateDeviceInfoList failed: {}", GetLastError()); return 1; }
            let mut info: SP_DEVINFO_DATA = std::mem::zeroed();
            info.cbSize = size_of::<SP_DEVINFO_DATA>() as u32;
            let class = wide("HIDClass");
            if SetupDiCreateDeviceInfoW(set, class.as_ptr(), &HIDCLASS, std::ptr::null(), std::ptr::null_mut(), DICD_GENERATE_ID, &mut info) == 0 {
                eprintln!("SetupDiCreateDeviceInfo failed: {}", GetLastError()); SetupDiDestroyDeviceInfoList(set); return 1;
            }
            let ids = multi_sz(hwid);
            if SetupDiSetDeviceRegistryPropertyW(set, &mut info, SPDRP_HARDWAREID, ids.as_ptr().cast(), (ids.len() * 2) as u32) == 0
                || SetupDiCallClassInstaller(DIF_REGISTERDEVICE, set, &mut info) == 0 {
                eprintln!("registering device failed: {}", GetLastError()); SetupDiDestroyDeviceInfoList(set); return 1;
            }
            SetupDiDestroyDeviceInfoList(set);
            if let Some(inf) = inf {
                let (h, p) = (wide(hwid), wide(inf));
                let mut reboot = 0;
                if UpdateDriverForPlugAndPlayDevicesW(std::ptr::null_mut(), h.as_ptr(), p.as_ptr(), INSTALLFLAG_FORCE, &mut reboot) == 0 {
                    eprintln!("driver install failed: {} (is the package signed / test-signing on?)", GetLastError()); return 1;
                }
                if reboot != 0 { println!("reboot required"); }
            }
            println!("device {hwid} created");
            0
        }
    }

    pub fn remove(hwid: &str) -> i32 {
        unsafe {
            let set = SetupDiGetClassDevsW(std::ptr::null(), std::ptr::null(), std::ptr::null_mut(), DIGCF_ALLCLASSES);
            if set == INVALID_HANDLE_VALUE as _ { return 1; }
            let mut removed = 0;
            let mut i = 0;
            loop {
                let mut info: SP_DEVINFO_DATA = std::mem::zeroed();
                info.cbSize = size_of::<SP_DEVINFO_DATA>() as u32;
                if SetupDiEnumDeviceInfo(set, i, &mut info) == 0 { break; }
                i += 1;
                let mut buf = [0u16; 1024];
                let mut needed = 0;
                if SetupDiGetDeviceRegistryPropertyW(set, &info, SPDRP_HARDWAREID, std::ptr::null_mut(), buf.as_mut_ptr().cast(), (buf.len() * 2) as u32, &mut needed) == 0 { continue; }
                let ids: Vec<String> = String::from_utf16_lossy(&buf).split('\0').map(str::to_owned).collect();
                if ids.iter().any(|x| x.eq_ignore_ascii_case(hwid)) && SetupDiCallClassInstaller(DIF_REMOVE, set, &mut info) != 0 { removed += 1; }
            }
            SetupDiDestroyDeviceInfoList(set);
            println!("removed {removed} device(s)");
            0
        }
    }
}
