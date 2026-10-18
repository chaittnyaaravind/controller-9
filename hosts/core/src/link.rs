//! Non-IP-datagram bindings (SPEC §8.2 stream, §8.3 BLE GATT). Both adapt their medium to the same
//! framed control stream (`serve_control`) and the same datagram path (`handle_datagram`), so
//! security, slots, freshness and neutralization are identical on every link.
use crate::host::{handle_datagram, read_frame, serve_control, Reply, Shared};
use nine_proto::control;
use nine_proto::link::{Reassembler, Record, RecordReader, Segmenter};
use nine_proto::policy::LinkKind;
use serde::Serialize;
use std::sync::Arc;
use std::time::Instant;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc;

#[derive(Serialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct BleStatus {
    /// Built with BLE support for this OS.
    pub supported: bool,
    /// Adapter powered and scanning.
    pub active: bool,
    pub detail: String,
    pub connected: Vec<String>,
}

fn io_err(e: impl std::fmt::Display) -> std::io::Error { std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string()) }

/// Stream binding over any reliable byte stream (TCP-only networks, RFCOMM, USB accessory).
pub(crate) async fn serve_stream<S>(sh: &Arc<Shared>, stream: S, peer: String, link: LinkKind) -> std::io::Result<()>
where S: AsyncRead + AsyncWrite + Send + Unpin + 'static {
    let (mut rd, mut wr) = tokio::io::split(stream);
    let (mut ctl_in_w, ctl_in_r) = tokio::io::duplex(1 << 17);
    let (ctl_out_w, mut ctl_out_r) = tokio::io::duplex(1 << 17);
    let (dg_tx, mut dg_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let sh2 = sh.clone();
    let reader = tokio::spawn(async move {
        let mut rr = RecordReader::default();
        let mut buf = [0u8; 4096];
        loop {
            let n = rd.read(&mut buf).await?;
            if n == 0 { return Ok::<_, std::io::Error>(()); }
            rr.push(&buf[..n]);
            while let Some(rec) = rr.next_record().map_err(io_err)? {
                match rec {
                    Record::Control(b) => ctl_in_w.write_all(&control::frame(&b)).await?,
                    Record::Datagram(d) => handle_datagram(&sh2, &d, Reply::Link(dg_tx.clone()), Instant::now()),
                }
            }
        }
    });
    let writer = tokio::spawn(async move {
        loop {
            tokio::select! {
                f = read_frame(&mut ctl_out_r, 65535) => match f {
                    Ok(b) => wr.write_all(&Record::Control(b).encode()).await?,
                    Err(_) => return Ok::<_, std::io::Error>(()),
                },
                Some(d) = dg_rx.recv() => wr.write_all(&Record::Datagram(d).encode()).await?,
            }
        }
    });
    let r = serve_control(sh, ctl_in_r, ctl_out_w, peer, link).await;
    reader.abort();
    // let the writer flush the final control frame (bye/error) before closing
    let _ = tokio::time::timeout(std::time::Duration::from_millis(200), writer).await;
    r
}

/// Characteristic-level pipes for one connected phone (SPEC §8.3). Values are raw characteristic
/// values (segment header included).
pub struct GattPipes {
    pub control_up: mpsc::Receiver<Vec<u8>>,
    pub input_up: mpsc::Receiver<Vec<u8>>,
    pub control_down: mpsc::Sender<Vec<u8>>,
    pub input_down: mpsc::Sender<Vec<u8>>,
    /// ATT MTU usable for host→phone writes (23 when unknown).
    pub write_mtu: usize,
}

/// BLE GATT binding: reassembles notifications, segments writes, reuses the common session code.
pub(crate) async fn serve_gatt(sh: &Arc<Shared>, p: GattPipes, peer: String) -> std::io::Result<()> {
    let GattPipes { mut control_up, mut input_up, control_down, input_down, write_mtu } = p;
    let (mut ctl_in_w, ctl_in_r) = tokio::io::duplex(1 << 17);
    let (ctl_out_w, mut ctl_out_r) = tokio::io::duplex(1 << 17);
    let (dg_tx, mut dg_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let sh2 = sh.clone();
    let up = tokio::spawn(async move {
        let mut rc = Reassembler::new(65535);
        let mut ri = Reassembler::new(nine_proto::frame::MAX_DATAGRAM);
        loop {
            tokio::select! {
                v = control_up.recv() => match v {
                    Some(v) => if let Some(m) = rc.push(&v) { if m.is_empty() { continue; } ctl_in_w.write_all(&control::frame(&m)).await? },
                    None => return Ok::<_, std::io::Error>(()),
                },
                Some(v) = input_up.recv() => if let Some(d) = ri.push(&v) { handle_datagram(&sh2, &d, Reply::Link(dg_tx.clone()), Instant::now()) },
            }
        }
    });
    let down = tokio::spawn(async move {
        let (mut sc, mut si) = (Segmenter::default(), Segmenter::default());
        loop {
            tokio::select! {
                f = read_frame(&mut ctl_out_r, 65535) => match f {
                    Ok(b) => for v in sc.segment(&b, write_mtu) { if control_down.send(v).await.is_err() { return Ok::<_, std::io::Error>(()); } },
                    Err(_) => return Ok(()),
                },
                Some(d) = dg_rx.recv() => for v in si.segment(&d, write_mtu) { let _ = input_down.try_send(v); },
            }
        }
    });
    let r = serve_control(sh, ctl_in_r, ctl_out_w, peer, LinkKind::Ble).await;
    up.abort();
    let _ = tokio::time::timeout(std::time::Duration::from_millis(200), down).await;
    r
}

/// Entry point for the BLE central adapter and for tests that simulate a GATT phone.
impl crate::Host {
    pub async fn serve_gatt(&self, pipes: GattPipes, peer: String) -> std::io::Result<()> { serve_gatt(&self.sh, pipes, peer).await }
    pub async fn serve_stream<S: AsyncRead + AsyncWrite + Send + Unpin + 'static>(&self, s: S, peer: String, link: LinkKind) -> std::io::Result<()> {
        serve_stream(&self.sh, s, peer, link).await
    }
    pub fn stream_port(&self) -> Option<u16> { self.sh.stream_port }
}
