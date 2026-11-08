// SPDX-License-Identifier: GPL-3.0-or-later
//! hoparlor-server: exposes a PipeWire sink and streams it to Android phones over UDP.

mod protocol;
mod router;

use anyhow::{Context, Result};
use clap::Parser;
use pipewire as pw;
use pw::{properties::properties, spa};
use spa::pod::Pod;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const RATE: u32 = 48_000;
const CHANNELS: u32 = 2;
const BYTES_PER_FRAME: usize = 2 * CHANNELS as usize; // s16le
const CLIENT_TIMEOUT: Duration = Duration::from_secs(3);

#[derive(Parser)]
#[command(name = "hoparlor-server", version, about = "Use your Android phone as a low-latency speaker")]
struct Opt {
    /// UDP port phones connect to
    #[arg(short, long, default_value_t = protocol::DEFAULT_AUDIO_PORT)]
    port: u16,

    /// Frames per network packet (48 kHz): 120 = 2.5 ms, 240 = 5 ms, 480 = 10 ms
    #[arg(short, long, default_value_t = 240, value_parser = clap::value_parser!(u16).range(48..=1024))]
    frames: u16,

    /// Instead of creating a virtual sink, mirror what the default output is playing
    #[arg(short, long)]
    mirror: bool,

    /// With --mirror: node name/serial of the sink to capture instead of the default
    #[arg(short, long, requires = "mirror")]
    target: Option<String>,

    /// Name shown on the phone (defaults to hostname)
    #[arg(short, long)]
    name: Option<String>,

    /// Disable LAN broadcast discovery beacons
    #[arg(long)]
    no_discovery: bool,

    /// Do not switch the default output to the phone while it is connected
    #[arg(long)]
    no_switch: bool,

    /// Log per-client uplink timing once per second (diagnoses Wi-Fi power saving)
    #[arg(short, long)]
    verbose: bool,
}

#[derive(Clone, Copy)]
struct Client {
    addr: SocketAddr,
    last_seen: Instant,
    window_start: Instant,
    max_gap: Duration,
    hellos: u32,
}

type Clients = Arc<Mutex<Vec<Client>>>;

fn main() -> Result<()> {
    let opt = Opt::parse();
    let name = opt.name.clone().unwrap_or_else(hostname);

    let socket = UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, opt.port))
        .with_context(|| format!("binding UDP port {}", opt.port))?;
    set_dscp_ef(&socket);
    let clients: Clients = Arc::new(Mutex::new(Vec::new()));

    // Mirror mode captures the real output, so switching the default would loop.
    let router = Arc::new(router::Router::new(!opt.no_switch && !opt.mirror));
    {
        let router = router.clone();
        ctrlc::set_handler(move || {
            router.deactivate();
            std::process::exit(0);
        })?;
    }

    spawn_control_thread(socket.try_clone()?, clients.clone(), router, opt.verbose);
    if !opt.no_discovery {
        spawn_beacon_thread(opt.port, name.clone());
    }

    eprintln!(
        "hoparlor-server: '{}' listening on UDP {} ({} frames/packet = {:.1} ms)",
        name,
        opt.port,
        opt.frames,
        opt.frames as f32 * 1000.0 / RATE as f32
    );

    run_pipewire(&opt, socket, clients)
}

/// DSCP EF (46): Wi-Fi access points map it to the WMM voice queue, the lowest-latency one.
fn set_dscp_ef(socket: &UdpSocket) {
    use std::os::fd::AsRawFd;
    let tos: libc::c_int = 0xB8;
    unsafe {
        libc::setsockopt(
            socket.as_raw_fd(),
            libc::IPPROTO_IP,
            libc::IP_TOS,
            &tos as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        );
    }
}

fn hostname() -> String {
    std::fs::read_to_string("/proc/sys/kernel/hostname")
        .map(|s| s.trim().to_string())
        .unwrap_or_else(|_| "Linux PC".into())
}

/// Receives HELLO/BYE packets from phones and maintains the client list.
fn spawn_control_thread(socket: UdpSocket, clients: Clients, router: Arc<router::Router>, verbose: bool) {
    std::thread::Builder::new()
        .name("hoparlor-control".into())
        .spawn(move || {
            // Wake up periodically so silent disappearances still time out.
            let _ = socket.set_read_timeout(Some(Duration::from_millis(500)));
            let mut buf = [0u8; 64];
            loop {
                let (n, from) = match socket.recv_from(&mut buf) {
                    Ok(v) => v,
                    Err(_) => (0, SocketAddr::from(([0, 0, 0, 0], 0))),
                };
                let now = Instant::now();
                let mut list = clients.lock().unwrap();
                let had_clients = !list.is_empty();
                match protocol::packet_type(&buf[..n]) {
                    Some(protocol::TYPE_HELLO) => {
                        // Echo the phone's timestamp so it can measure the round trip.
                        if n >= 16 {
                            let mut ack = [0u8; 16];
                            ack.copy_from_slice(&buf[..16]);
                            ack[4] = protocol::TYPE_HELLO_ACK;
                            let _ = socket.send_to(&ack, from);
                        }
                        if let Some(c) = list.iter_mut().find(|c| c.addr == from) {
                            c.max_gap = c.max_gap.max(now - c.last_seen);
                            c.hellos += 1;
                            c.last_seen = now;
                            if now - c.window_start >= Duration::from_secs(1) {
                                if verbose {
                                    eprintln!(
                                        "{from}: {} keepalives/s, max gap {:.1} ms",
                                        c.hellos,
                                        c.max_gap.as_secs_f32() * 1000.0
                                    );
                                }
                                c.window_start = now;
                                c.max_gap = Duration::ZERO;
                                c.hellos = 0;
                            }
                        } else {
                            eprintln!("client connected: {from}");
                            list.push(Client {
                                addr: from,
                                last_seen: now,
                                window_start: now,
                                max_gap: Duration::ZERO,
                                hellos: 0,
                            });
                        }
                    }
                    Some(protocol::TYPE_BYE) => {
                        list.retain(|c| c.addr != from);
                        eprintln!("client disconnected: {from}");
                    }
                    _ => {}
                }
                list.retain(|c| {
                    let alive = now - c.last_seen < CLIENT_TIMEOUT;
                    if !alive {
                        eprintln!("client timed out: {}", c.addr);
                    }
                    alive
                });
                let has_clients = !list.is_empty();
                drop(list);
                if has_clients && !had_clients {
                    router.activate();
                } else if !has_clients && had_clients {
                    router.deactivate();
                }
            }
        })
        .expect("spawn control thread");
}

/// Broadcasts a discovery beacon on every IPv4 interface once per second.
/// Works for Wi-Fi LANs and USB tethering (where the phone is the gateway).
fn spawn_beacon_thread(port: u16, name: String) {
    std::thread::Builder::new()
        .name("hoparlor-beacon".into())
        .spawn(move || {
            let Ok(sock) = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)) else { return };
            let _ = sock.set_broadcast(true);
            // Random per-run id lets the phone merge beacons heard on several interfaces
            // (Wi-Fi and USB) into one entry.
            let id = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.subsec_nanos() ^ std::process::id().rotate_left(16))
                .unwrap_or(1);
            let packet = protocol::beacon(port, id, &name);
            loop {
                let mut targets = vec![Ipv4Addr::BROADCAST];
                if let Ok(ifaces) = if_addrs::get_if_addrs() {
                    for iface in ifaces.iter().filter(|i| !i.is_loopback()) {
                        if let if_addrs::IfAddr::V4(v4) = &iface.addr {
                            if let Some(b) = v4.broadcast {
                                targets.push(b);
                            }
                        }
                    }
                }
                for t in targets {
                    let _ = sock.send_to(&packet, (t, protocol::DISCOVERY_PORT));
                }
                std::thread::sleep(Duration::from_secs(1));
            }
        })
        .expect("spawn beacon thread");
}

/// State owned by the PipeWire realtime process callback.
struct Streamer {
    socket: UdpSocket,
    clients: Clients,
    cached: Vec<SocketAddr>,
    frames_per_packet: usize,
    packet: Vec<u8>,
    fill: usize, // bytes of PCM currently in `packet` after the header
    seq: u32,
}

impl Streamer {
    fn push(&mut self, mut pcm: &[u8]) {
        // Refresh the client list without ever blocking the RT thread.
        if let Ok(list) = self.clients.try_lock() {
            self.cached.clear();
            self.cached.extend(list.iter().map(|c| c.addr));
        }
        let payload = self.frames_per_packet * BYTES_PER_FRAME;
        while !pcm.is_empty() {
            let take = (payload - self.fill).min(pcm.len());
            let start = protocol::AUDIO_HEADER_LEN + self.fill;
            self.packet[start..start + take].copy_from_slice(&pcm[..take]);
            self.fill += take;
            pcm = &pcm[take..];
            if self.fill == payload {
                protocol::write_audio_header(
                    &mut self.packet,
                    CHANNELS as u8,
                    self.frames_per_packet as u16,
                    self.seq,
                    RATE,
                );
                for addr in &self.cached {
                    let _ = self.socket.send_to(&self.packet, addr);
                }
                self.seq = self.seq.wrapping_add(1);
                self.fill = 0;
            }
        }
    }
}

fn run_pipewire(opt: &Opt, socket: UdpSocket, clients: Clients) -> Result<()> {
    pw::init();
    let mainloop = pw::main_loop::MainLoopRc::new(None)?;
    let context = pw::context::ContextRc::new(&mainloop, None)?;
    let core = context.connect_rc(None)?;

    // Ask the graph for a small quantum so audio leaves the PC as soon as possible.
    let latency = format!("{}/{}", opt.frames, RATE);
    let mut props = properties! {
        *pw::keys::MEDIA_TYPE => "Audio",
        *pw::keys::MEDIA_CATEGORY => "Capture",
        *pw::keys::MEDIA_ROLE => "Music",
        *pw::keys::NODE_LATENCY => latency.as_str(),
        *pw::keys::APP_NAME => "Hoparlor",
    };
    if opt.mirror {
        props.insert(*pw::keys::STREAM_CAPTURE_SINK, "true");
        props.insert(*pw::keys::NODE_NAME, "hoparlor_mirror");
        if let Some(t) = &opt.target {
            props.insert("target.object", t.as_str());
        }
    } else {
        props.insert(*pw::keys::MEDIA_CLASS, "Audio/Sink");
        props.insert(*pw::keys::NODE_NAME, router::SINK_NAME);
        props.insert(*pw::keys::NODE_DESCRIPTION, "Hoparlor (Phone Speaker)");
    }

    let stream = pw::stream::StreamBox::new(&core, "hoparlor", props)?;

    let frames = opt.frames as usize;
    let streamer = Streamer {
        socket,
        clients,
        cached: Vec::with_capacity(8),
        frames_per_packet: frames,
        packet: vec![0u8; protocol::AUDIO_HEADER_LEN + frames * BYTES_PER_FRAME],
        fill: 0,
        seq: 0,
    };

    let _listener = stream
        .add_local_listener_with_user_data(streamer)
        .state_changed(|_, _, old, new| eprintln!("pipewire stream: {old:?} -> {new:?}"))
        .process(|stream, st| {
            let Some(mut buffer) = stream.dequeue_buffer() else { return };
            let datas = buffer.datas_mut();
            if datas.is_empty() {
                return;
            }
            let data = &mut datas[0];
            let chunk = data.chunk();
            let offset = chunk.offset() as usize;
            let size = chunk.size() as usize;
            if let Some(bytes) = data.data() {
                let end = (offset + size).min(bytes.len());
                if offset < end {
                    st.push(&bytes[offset..end]);
                }
            }
        })
        .register()?;

    // Fixed format: 48 kHz stereo s16le. PipeWire converts whatever apps play.
    let mut info = spa::param::audio::AudioInfoRaw::new();
    info.set_format(spa::param::audio::AudioFormat::S16LE);
    info.set_rate(RATE);
    info.set_channels(CHANNELS);
    let mut position = [0u32; spa::sys::SPA_AUDIO_MAX_CHANNELS as usize];
    position[0] = spa::sys::SPA_AUDIO_CHANNEL_FL;
    position[1] = spa::sys::SPA_AUDIO_CHANNEL_FR;
    info.set_position(position);

    let obj = spa::pod::Object {
        type_: spa::utils::SpaTypes::ObjectParamFormat.as_raw(),
        id: spa::param::ParamType::EnumFormat.as_raw(),
        properties: info.into(),
    };
    let values: Vec<u8> = spa::pod::serialize::PodSerializer::serialize(
        std::io::Cursor::new(Vec::new()),
        &spa::pod::Value::Object(obj),
    )
    .map_err(|e| anyhow::anyhow!("serializing format: {e:?}"))?
    .0
    .into_inner();
    let mut params = [Pod::from_bytes(&values).context("format pod")?];

    stream.connect(
        spa::utils::Direction::Input,
        None,
        pw::stream::StreamFlags::AUTOCONNECT
            | pw::stream::StreamFlags::MAP_BUFFERS
            | pw::stream::StreamFlags::RT_PROCESS,
        &mut params,
    )?;

    mainloop.run();
    Ok(())
}
