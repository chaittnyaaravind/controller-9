//! Local interface discovery and link classification (heuristic, documented in
//! docs/TRANSPORT_MATRIX.md). Used to list every reachable address in the pairing QR and to label
//! the link a controller is using.
use nine_proto::policy::LinkKind;
use serde::Serialize;
use std::net::{IpAddr, Ipv4Addr};

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LocalAddr { pub ip: Ipv4Addr, pub iface: String, pub kind: LinkKind, pub label: String }

const IGNORED: [&str; 10] = ["utun", "awdl", "llw", "tun", "tap", "vmnet", "vboxnet", "docker", "veth", "anpi"];

/// Classify an interface from its name and address. Address ranges are the documented OS defaults
/// for phone hotspots (Android 192.168.43/24, iPhone 172.20.10/28), Android USB tethering
/// (192.168.42/24) and Android Wi-Fi Direct groups (192.168.49/24); names cover RNDIS/NCM/CDC.
pub fn classify(iface: &str, ip: Ipv4Addr) -> LinkKind {
    let n = iface.to_ascii_lowercase();
    let o = ip.octets();
    if n.contains("rndis") || n.contains("ncm") || n.starts_with("usb") || n.contains("remote ndis") || n.contains("iphone usb")
        || n.contains("apple mobile device") || (o[0] == 192 && o[1] == 168 && o[2] == 42) { return LinkKind::Usb; }
    if o[0] == 192 && o[1] == 168 && o[2] == 49 || n.starts_with("p2p") { return LinkKind::WifiDirect; }
    if (o[0] == 172 && o[1] == 20 && o[2] == 10 && o[3] < 16) || (o[0] == 192 && o[1] == 168 && o[2] == 43) || n.starts_with("bridge") || n.starts_with("ap") {
        return LinkKind::Hotspot;
    }
    if n.starts_with("wl") || n.contains("wi-fi") || n.contains("wifi") || n.contains("wireless") { return LinkKind::Lan; }
    if n.starts_with("eth") || n.starts_with("enp") || n.starts_with("eno") || n.starts_with("ens") || n.starts_with("enx") || n.contains("ethernet") {
        return LinkKind::Ethernet;
    }
    LinkKind::Lan // macOS en0/en1 can be Wi-Fi or Ethernet; report the neutral "Wi-Fi / LAN"
}

/// All usable IPv4 addresses, the default-route address first.
pub fn local_ipv4() -> Vec<LocalAddr> {
    let primary = crate::discovery::primary_ipv4();
    let mut v: Vec<LocalAddr> = if_addrs::get_if_addrs().unwrap_or_default().into_iter()
        .filter(|i| !i.is_loopback() && !IGNORED.iter().any(|p| i.name.to_ascii_lowercase().starts_with(p)))
        .filter_map(|i| match i.ip() {
            IpAddr::V4(ip) if !ip.is_link_local() || classify(&i.name, ip) == LinkKind::Usb => {
                let kind = classify(&i.name, ip);
                Some(LocalAddr { ip, iface: i.name.clone(), kind, label: kind.label().into() })
            }
            _ => None,
        })
        .collect();
    v.sort_by_key(|a| (Some(a.ip) != primary, a.ip));
    v.dedup_by_key(|a| a.ip);
    v
}

/// Link kind of the interface that owns `local` (the address a connection arrived on).
pub fn kind_for_local(local: Option<IpAddr>) -> LinkKind {
    match local {
        Some(IpAddr::V4(ip)) if !ip.is_loopback() => local_ipv4().into_iter().find(|a| a.ip == ip).map(|a| a.kind).unwrap_or(LinkKind::Lan),
        _ => LinkKind::Lan,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn classification() {
        let ip = |s: &str| s.parse::<Ipv4Addr>().unwrap();
        assert_eq!(classify("rndis0", ip("192.168.42.129")), LinkKind::Usb);
        assert_eq!(classify("en7", ip("192.168.42.10")), LinkKind::Usb);
        assert_eq!(classify("Ethernet 3 (Remote NDIS based Internet Sharing Device)", ip("10.0.0.5")), LinkKind::Usb);
        assert_eq!(classify("en0", ip("172.20.10.3")), LinkKind::Hotspot);
        assert_eq!(classify("wlan0", ip("192.168.43.20")), LinkKind::Hotspot);
        assert_eq!(classify("bridge100", ip("192.168.2.1")), LinkKind::Hotspot);
        assert_eq!(classify("wlp2s0", ip("192.168.49.12")), LinkKind::WifiDirect);
        assert_eq!(classify("wlp2s0", ip("192.168.1.20")), LinkKind::Lan);
        assert_eq!(classify("enp3s0", ip("192.168.1.21")), LinkKind::Ethernet);
        assert_eq!(classify("en0", ip("192.168.1.22")), LinkKind::Lan);
    }
    #[test]
    fn enumerates_this_machine() {
        let v = local_ipv4();
        println!("local addresses: {v:?}");
        assert!(v.iter().all(|a| !a.ip.is_loopback()));
    }
}
