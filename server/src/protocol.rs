//! Hoparlor wire protocol v1. See PROTOCOL.md at the repository root.
//! All multi-byte fields are little-endian.

pub const MAGIC: &[u8; 4] = b"HPR1";

pub const TYPE_AUDIO: u8 = 1;
pub const TYPE_HELLO: u8 = 2;
pub const TYPE_BEACON: u8 = 3;
pub const TYPE_BYE: u8 = 4;
pub const TYPE_HELLO_ACK: u8 = 5;

pub const AUDIO_HEADER_LEN: usize = 16;

pub const DEFAULT_AUDIO_PORT: u16 = 47800;
pub const DISCOVERY_PORT: u16 = 47801;

/// Returns the packet type if `buf` starts with a valid Hoparlor header.
pub fn packet_type(buf: &[u8]) -> Option<u8> {
    if buf.len() >= 8 && &buf[0..4] == MAGIC {
        Some(buf[4])
    } else {
        None
    }
}

/// Writes an AUDIO header into `buf[..16]`.
pub fn write_audio_header(buf: &mut [u8], channels: u8, frames: u16, seq: u32, rate: u32) {
    buf[0..4].copy_from_slice(MAGIC);
    buf[4] = TYPE_AUDIO;
    buf[5] = channels;
    buf[6..8].copy_from_slice(&frames.to_le_bytes());
    buf[8..12].copy_from_slice(&seq.to_le_bytes());
    buf[12..16].copy_from_slice(&rate.to_le_bytes());
}

pub fn beacon(port: u16, id: u32, name: &str) -> Vec<u8> {
    let mut v = Vec::with_capacity(12 + name.len());
    v.extend_from_slice(MAGIC);
    v.push(TYPE_BEACON);
    v.push(0);
    v.extend_from_slice(&port.to_le_bytes());
    v.extend_from_slice(&id.to_le_bytes());
    v.extend_from_slice(name.as_bytes());
    v
}
