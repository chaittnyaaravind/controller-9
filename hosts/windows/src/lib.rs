//! Windows virtual gamepads through the NineVhf KMDF driver (`driver/NineVhf`), which creates
//! HID gamepads with Microsoft's Virtual HID Framework. Visible to DirectInput, Raw Input,
//! Windows.Gaming.Input (RawGameController), SDL and Steam Input. XInput-only games need Steam
//! Input (or another XInput layer) — a generic HID gamepad is not an XInput device.

/// IOCTL codes — must match `driver/NineVhf/public.h`.
pub mod abi {
    const fn ctl(func: u32, access: u32) -> u32 { (0x22 << 16) | (access << 14) | (func << 2) }
    pub const IOCTL_CREATE: u32 = ctl(0x901, 2);
    pub const IOCTL_SUBMIT: u32 = ctl(0x902, 2);
    pub const IOCTL_DESTROY: u32 = ctl(0x903, 2);
    pub const IOCTL_VERSION: u32 = ctl(0x904, 1);
    pub const INTERFACE_VERSION: u32 = 1;
    /// {6E1E3B2A-9C7F-4D0B-9A51-3C2F8E9D4A01}
    pub const INTERFACE_GUID: u128 = 0x6e1e3b2a_9c7f_4d0b_9a51_3c2f8e9d4a01;
}

#[cfg(windows)]
mod vhf;
#[cfg(windows)]
pub use vhf::VhfBackend;

#[cfg(test)]
mod tests {
    #[test]
    fn ioctl_codes_match_ctl_code_macro() {
        // CTL_CODE(FILE_DEVICE_UNKNOWN, 0x901, METHOD_BUFFERED, FILE_WRITE_ACCESS)
        assert_eq!(super::abi::IOCTL_CREATE, 0x0022_A404);
        assert_eq!(super::abi::IOCTL_VERSION, 0x0022_6410);
    }

    #[test]
    fn driver_descriptor_matches_host_descriptor() {
        let c = include_str!("../driver/NineVhf/descriptor.h");
        let body = c.split('{').nth(1).unwrap().split("};").next().unwrap();
        let bytes: Vec<u8> = body.lines()
            .map(|l| l.split("/*").next().unwrap())
            .flat_map(|l| l.split(','))
            .filter_map(|t| t.trim().strip_prefix("0x").map(|h| u8::from_str_radix(h, 16).unwrap()))
            .collect();
        assert_eq!(bytes, nine_host_core::hid::GAMEPAD_DESCRIPTOR.to_vec());
    }

    #[test]
    fn guid_matches_public_h() {
        let h = include_str!("../driver/NineVhf/public.h");
        assert!(h.contains("0x6e1e3b2a, 0x9c7f, 0x4d0b, 0x9a, 0x51, 0x3c, 0x2f, 0x8e, 0x9d, 0x4a, 0x01"));
        assert_eq!(super::abi::INTERFACE_GUID, 0x6e1e3b2a_9c7f_4d0b_9a51_3c2f8e9d4a01);
    }
}
