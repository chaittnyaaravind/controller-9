fn main() {
    // macOS: embed Info.plist so CoreBluetooth has a usage description for this command-line tool.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        let plist = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("Info.plist");
        println!("cargo:rustc-link-arg-bins=-Wl,-sectcreate,__TEXT,__info_plist,{}", plist.display());
        println!("cargo:rerun-if-changed={}", plist.display());
    }
}
