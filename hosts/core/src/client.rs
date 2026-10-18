//! Controller-side client (reference for the phone apps; used by `ninectl sim` and tests).
use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use nine_proto::control::{self, Control, ControllerHello, HostHello, InitHello};
use nine_proto::frame::{self, Counter, Feedback, Freshness, Header, InputFrame, PacketType, Ping, Pong};
use nine_proto::input::InputState;
use nine_proto::noise::{Handshake, KeyPair, Role, Transport};
use nine_proto::pairing::{commitment, qr_proof, sas_code, PairUri};
use nine_proto::stats::Window;
use std::collections::HashMap;
use std::net::SocketAddr;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};

#[derive(Debug, thiserror::Error)]
pub enum ClientError {
    #[error("network: {0}")] Io(#[from] std::io::Error),
    #[error("protocol: {0}")] Protocol(String),
    #[error("host refused: {0}")] Refused(String),
    #[error("host identity does not match the QR code")] WrongHost,
}

fn proto(e: impl std::fmt::Display) -> ClientError { ClientError::Protocol(e.to_string()) }

pub struct Connection {
    stream: TcpStream,
    t: Transport,
    pub host: HostHello,
    pub host_static: [u8; 32],
    pub handshake_hash: [u8; 32],
}

#[derive(Debug, Clone)]
pub struct SessionInfo {
    pub slot: u8,
    pub session_id: u32,
    pub udp_port: u16,
    pub k_in: [u8; 32],
    pub k_out: [u8; 32],
    pub heartbeat_ms: u32,
    pub idle_heartbeat_ms: u32,
    pub timeout_ms: u32,
    pub backend_warning: Option<String>,
}

impl Connection {
    pub async fn connect(addr: SocketAddr, identity: &KeyPair, hello: &ControllerHello) -> Result<Connection, ClientError> {
        let mut stream = TcpStream::connect(addr).await?;
        stream.set_nodelay(true)?;
        let mut hs = Handshake::new(Role::Initiator, KeyPair::from_secret(identity.secret_bytes()));
        let m1 = hs.write_message(&serde_json::to_vec(&InitHello { proto: vec![1] }).unwrap()).map_err(proto)?;
        stream.write_all(&control::frame(&m1)).await?;
        let m2 = read_frame(&mut stream).await?;
        let host: HostHello = serde_json::from_slice(&hs.read_message(&m2).map_err(proto)?).map_err(proto)?;
        let m3 = hs.write_message(&serde_json::to_vec(hello).unwrap()).map_err(proto)?;
        stream.write_all(&control::frame(&m3)).await?;
        let t = hs.into_transport().map_err(proto)?;
        Ok(Connection { stream, host_static: t.remote_static, handshake_hash: t.handshake_hash, t, host })
    }

    pub async fn send(&mut self, m: &Control) -> Result<(), ClientError> {
        let ct = self.t.encrypt(&m.to_json()).map_err(proto)?;
        self.stream.write_all(&control::frame(&ct)).await?;
        Ok(())
    }

    pub async fn recv(&mut self) -> Result<Control, ClientError> {
        let ct = read_frame(&mut self.stream).await?;
        Control::from_json(&self.t.decrypt(&ct).map_err(proto)?).map_err(proto)
    }

    /// QR pairing. Aborts before proving anything if the host key differs from the QR code.
    pub async fn pair_qr(&mut self, uri: &PairUri) -> Result<(), ClientError> {
        if self.host_static != uri.host_static { return Err(ClientError::WrongHost); }
        let proof = B64.encode(qr_proof(&uri.token, &self.handshake_hash));
        self.send(&Control::PairQr { proof }).await?;
        match self.recv().await? {
            Control::Paired => Ok(()),
            Control::PairFailed { reason } => Err(ClientError::Refused(reason)),
            Control::Error { code, .. } => Err(ClientError::Refused(code)),
            o => Err(proto(format!("unexpected {o:?}"))),
        }
    }

    /// Numeric comparison. `confirm` shows the code to the user and returns their verdict.
    pub async fn pair_code(&mut self, confirm: impl FnOnce(u32) -> bool) -> Result<u32, ClientError> {
        self.send(&Control::PairCode).await?;
        let c = match self.recv().await? {
            Control::Commit { c } => B64.decode(c).map_err(proto)?,
            Control::Error { code, .. } => return Err(ClientError::Refused(code)),
            o => return Err(proto(format!("unexpected {o:?}"))),
        };
        let na = nine_proto::random_bytes::<16>();
        self.send(&Control::Nonce { n: B64.encode(na) }).await?;
        let nb: [u8; 16] = match self.recv().await? {
            Control::Reveal { n } => B64.decode(n).map_err(proto)?.try_into().map_err(|_| proto("nonce length"))?,
            o => return Err(proto(format!("unexpected {o:?}"))),
        };
        if commitment(&self.handshake_hash, &nb).as_slice() != c.as_slice() {
            return Err(ClientError::Protocol("host commitment mismatch — possible interception, pairing aborted".into()));
        }
        let code = sas_code(&self.handshake_hash, &na, &nb);
        let ok = confirm(code);
        self.send(if ok { &Control::CodeOk } else { &Control::CodeReject }).await?;
        if !ok { return Err(ClientError::Refused("rejected on controller".into())); }
        match self.recv().await? {
            Control::Paired => Ok(code),
            Control::PairFailed { reason } => Err(ClientError::Refused(reason)),
            o => Err(proto(format!("unexpected {o:?}"))),
        }
    }

    pub async fn open(&mut self, resume: Option<u8>, profile: Option<&str>) -> Result<SessionInfo, ClientError> {
        self.send(&Control::Open { resume_slot: resume, profile: profile.map(String::from) }).await?;
        let key = |s: &str| -> Result<[u8; 32], ClientError> { B64.decode(s).map_err(proto)?.try_into().map_err(|_| proto("key length")) };
        let mut info = match self.recv().await? {
            Control::Session { slot, session_id, udp_port, k_in, k_out, heartbeat_ms, idle_heartbeat_ms, timeout_ms } => SessionInfo {
                slot, session_id, udp_port, k_in: key(&k_in.0)?, k_out: key(&k_out.0)?, heartbeat_ms, idle_heartbeat_ms, timeout_ms, backend_warning: None },
            Control::PairRequired { .. } => return Err(ClientError::Refused("not_paired".into())),
            Control::Error { code, .. } => return Err(ClientError::Refused(code)),
            o => return Err(proto(format!("unexpected {o:?}"))),
        };
        // An optional backend warning follows immediately when the host could not create a pad.
        if let Ok(Ok(Control::Error { code, message })) = tokio::time::timeout(std::time::Duration::from_millis(50), self.recv()).await {
            if code == "backend_unavailable" { info.backend_warning = message.or(Some(code)); }
        }
        Ok(info)
    }
}

async fn read_frame(s: &mut TcpStream) -> Result<Vec<u8>, ClientError> {
    let n = s.read_u16().await? as usize;
    if n == 0 { return Err(proto("empty frame")); }
    let mut b = vec![0u8; n];
    s.read_exact(&mut b).await?;
    Ok(b)
}

/// UDP input sender with ping/pong RTT measurement.
pub struct InputSender {
    pub sock: UdpSocket,
    pub info: SessionInfo,
    counter: Counter,
    fresh_in: Freshness,
    pings: HashMap<u32, u64>,
    next_ping: u32,
    pub rtt_ms: Window,
    pub feedback: Vec<Feedback>,
    epoch: std::time::Instant,
}

impl InputSender {
    pub async fn new(host: SocketAddr, info: SessionInfo) -> Result<Self, ClientError> {
        let bind: SocketAddr = if host.is_ipv4() { "0.0.0.0:0".parse().unwrap() } else { "[::]:0".parse().unwrap() };
        let sock = UdpSocket::bind(bind).await?;
        sock.connect(SocketAddr::new(host.ip(), info.udp_port)).await?;
        Ok(Self { sock, info, counter: Counter::default(), fresh_in: Freshness::default(), pings: HashMap::new(), next_ping: 1,
            rtt_ms: Window::new(4096), feedback: Vec::new(), epoch: std::time::Instant::now() })
    }

    pub fn now_us(&self) -> u64 { self.epoch.elapsed().as_micros() as u64 }

    pub fn seal(&mut self, ptype: PacketType, payload: &[u8]) -> Vec<u8> {
        frame::seal(&self.info.k_in, Header { ptype, session_id: self.info.session_id, counter: self.counter.next() }, payload)
    }

    pub async fn send_input(&mut self, state: InputState, flags: u16) -> Result<(), ClientError> {
        let f = InputFrame { t_us: self.now_us(), state, flags };
        let d = self.seal(PacketType::Input, &f.encode());
        self.sock.send(&d).await?;
        Ok(())
    }

    pub async fn ping(&mut self) -> Result<(), ClientError> {
        let id = self.next_ping;
        self.next_ping += 1;
        let t = self.now_us();
        self.pings.insert(id, t);
        let d = self.seal(PacketType::Ping, &Ping { t_us: t, id }.encode());
        self.sock.send(&d).await?;
        Ok(())
    }

    pub async fn bye(&mut self) -> Result<(), ClientError> {
        let d = self.seal(PacketType::Bye, &[]);
        self.sock.send(&d).await?;
        Ok(())
    }

    /// Process every datagram already queued (non-blocking).
    pub fn drain(&mut self) {
        let mut buf = [0u8; 512];
        while let Ok(n) = self.sock.try_recv(&mut buf) { self.handle(&buf[..n]); }
    }

    /// Wait up to `dur` for host datagrams.
    pub async fn pump(&mut self, dur: std::time::Duration) {
        let mut buf = [0u8; 512];
        let end = tokio::time::Instant::now() + dur;
        while let Ok(Ok(n)) = tokio::time::timeout_at(end, self.sock.recv(&mut buf)).await { self.handle(&buf[..n]); }
    }

    fn handle(&mut self, d: &[u8]) {
        let Ok((h, pt)) = frame::open(&self.info.k_out, d) else { return };
        if !self.fresh_in.accept(h.counter) { return; }
        match h.ptype {
            PacketType::Pong => if let Ok(p) = Pong::decode(&pt) {
                if self.pings.remove(&p.id).is_some() { self.rtt_ms.push((self.now_us() - p.t_us) as f64 / 1000.0); }
            },
            PacketType::Feedback => if let Ok(f) = Feedback::decode(&pt) { self.feedback.push(f); },
            _ => {}
        }
    }
}
