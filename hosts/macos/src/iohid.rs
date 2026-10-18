use core_foundation::base::{CFType, TCFType};
use core_foundation::data::CFData;
use core_foundation::dictionary::CFDictionary;
use core_foundation::number::CFNumber;
use core_foundation::string::CFString;
use nine_host_core::backend::{Backend, BackendError, BackendKind, BackendStatus, VirtualPad};
use nine_host_core::hid::{report, GAMEPAD_DESCRIPTOR};
use nine_proto::input::InputState;
use std::ffi::c_void;

type IOHIDUserDeviceRef = *mut c_void;

#[link(name = "IOKit", kind = "framework")]
unsafe extern "C" {
    fn IOHIDUserDeviceCreateWithProperties(alloc: *const c_void, props: *const c_void, options: u32) -> IOHIDUserDeviceRef;
    fn IOHIDUserDeviceHandleReportWithTimeStamp(dev: IOHIDUserDeviceRef, ts: u64, report: *const u8, len: isize) -> i32;
}
#[link(name = "CoreFoundation", kind = "framework")]
unsafe extern "C" {
    fn CFRelease(cf: *const c_void);
}
unsafe extern "C" {
    fn mach_absolute_time() -> u64;
}

pub const ENTITLEMENT: &str = "com.apple.developer.hid.virtual.device";
const VENDOR_ID: i32 = 0x1209; // pid.codes open-source vendor id
const PRODUCT_ID: i32 = 0x9C01; // placeholder until a pid.codes product id is allocated (docs/RELEASE.md)

pub struct IoHidBackend;

fn create_device(name: &str) -> IOHIDUserDeviceRef {
    let pairs: Vec<(CFString, CFType)> = vec![
        (CFString::new("ReportDescriptor"), CFData::from_buffer(&GAMEPAD_DESCRIPTOR).as_CFType()),
        (CFString::new("VendorID"), CFNumber::from(VENDOR_ID).as_CFType()),
        (CFString::new("ProductID"), CFNumber::from(PRODUCT_ID).as_CFType()),
        (CFString::new("Product"), CFString::new(name).as_CFType()),
        (CFString::new("Manufacturer"), CFString::new("9-controller").as_CFType()),
        (CFString::new("Transport"), CFString::new("Virtual").as_CFType()),
    ];
    let props = CFDictionary::from_CFType_pairs(&pairs);
    // SAFETY: props is a valid CFDictionary for the duration of the call.
    unsafe { IOHIDUserDeviceCreateWithProperties(std::ptr::null(), props.as_concrete_TypeRef().cast(), 0) }
}

impl Backend for IoHidBackend {
    fn status(&self) -> BackendStatus {
        // Probe: create and immediately release a device.
        let dev = create_device("9-controller probe");
        let ok = !dev.is_null();
        // SAFETY: release only a non-null device we created.
        if ok { unsafe { CFRelease(dev) } }
        BackendStatus {
            name: "macos-iohid".into(),
            kind: if ok { BackendKind::Native } else { BackendKind::Blocked },
            available: ok,
            max_pads: 4,
            detail: if ok { "IOHIDUserDevice available".into() }
                    else { format!("macOS refused to create a virtual HID device: this build lacks the {ENTITLEMENT} entitlement") },
            remedy: (!ok).then(|| "Request the entitlement from Apple (docs/platforms/macos.md), then ship a build signed with it. \
                Until then: pairing, diagnostics and the input pipeline work, but no controller is visible to games.".into()),
        }
    }

    fn create(&self, slot: u8, _name: &str) -> Result<Box<dyn VirtualPad>, BackendError> {
        let dev = create_device(&format!("9-controller #{}", slot + 1));
        if dev.is_null() {
            return Err(BackendError::Unavailable(format!("IOHIDUserDevice creation refused (missing {ENTITLEMENT})")));
        }
        Ok(Box::new(IoHidPad { dev }))
    }
}

struct IoHidPad { dev: IOHIDUserDeviceRef }
// SAFETY: IOHIDUserDevice report submission is thread-safe; the pad is used by one thread at a time.
unsafe impl Send for IoHidPad {}

impl VirtualPad for IoHidPad {
    fn submit(&mut self, s: &InputState) -> Result<(), BackendError> {
        let r = report(s);
        // SAFETY: dev is a live device; report buffer outlives the call.
        let ret = unsafe { IOHIDUserDeviceHandleReportWithTimeStamp(self.dev, mach_absolute_time(), r.as_ptr(), r.len() as isize) };
        if ret != 0 { return Err(BackendError::Io(format!("IOHIDUserDeviceHandleReport returned 0x{ret:08x}"))); }
        Ok(())
    }
}

impl Drop for IoHidPad {
    fn drop(&mut self) {
        let _ = self.submit(&InputState::NEUTRAL);
        // SAFETY: we own the only reference.
        unsafe { CFRelease(self.dev) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    /// On an unentitled build this must report Blocked, never Native.
    #[test]
    fn unentitled_build_reports_blocked() {
        let s = IoHidBackend.status();
        println!("macOS backend status: {s:?}");
        if !s.available {
            assert_eq!(s.kind, BackendKind::Blocked);
            assert!(IoHidBackend.create(0, "x").is_err());
        }
    }
}
