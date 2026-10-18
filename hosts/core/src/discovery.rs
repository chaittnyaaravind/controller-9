//! DNS-SD advertisement (`_ninectl._tcp.local.`).
use mdns_sd::{ServiceDaemon, ServiceInfo};

pub struct Advertiser { daemon: ServiceDaemon, fullname: String }

impl Advertiser {
    pub fn start(name: &str, host_id: &str, tcp_port: u16, udp_port: u16, free_slots: usize) -> Result<Advertiser, String> {
        let daemon = ServiceDaemon::new().map_err(|e| e.to_string())?;
        let instance: String = name.chars().filter(|c| !c.is_control() && *c != '.').take(50).collect();
        let hostname = format!("ninectl-{host_id}.local.");
        let props = [("v", "1".to_string()), ("id", host_id.to_string()), ("name", instance.clone()),
            ("os", std::env::consts::OS.to_string()), ("slots", free_slots.to_string()), ("udp", udp_port.to_string())];
        let info = ServiceInfo::new(nine_proto::SERVICE_TYPE, &instance, &hostname, "", tcp_port, &props[..])
            .map_err(|e| e.to_string())?
            .enable_addr_auto();
        let fullname = info.get_fullname().to_string();
        daemon.register(info).map_err(|e| e.to_string())?;
        Ok(Advertiser { daemon, fullname })
    }
}

impl Drop for Advertiser {
    fn drop(&mut self) {
        let _ = self.daemon.unregister(&self.fullname);
        let _ = self.daemon.shutdown();
    }
}

/// The IPv4 address the OS would use for the default route (no packet is sent).
pub fn primary_ipv4() -> Option<std::net::Ipv4Addr> {
    let s = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("192.0.2.1:9").ok()?; // TEST-NET-1: route lookup only
    match s.local_addr().ok()?.ip() { std::net::IpAddr::V4(a) if !a.is_unspecified() => Some(a), _ => None }
}
