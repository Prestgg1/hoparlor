# Hoparlor wire protocol (version 1)

Everything is UDP over IPv4. All multi-byte integers are **little-endian**.
Every packet starts with the same 8-byte header:

| Offset | Size | Field                           |
|--------|------|---------------------------------|
| 0      | 4    | magic `HPR1` (ASCII)            |
| 4      | 1    | type                            |
| 5      | 3    | type-specific (zero if unused)  |

Packets with a wrong magic are ignored.

## Ports

| Port  | Direction             | Use                         |
|-------|-----------------------|-----------------------------|
| 47800 | phone ⇄ server        | control and audio (default; `--port`) |
| 47801 | server → broadcast    | discovery beacons           |

## Types

### 1 — AUDIO (server → phone)

| Offset | Size | Field                                           |
|--------|------|-------------------------------------------------|
| 5      | 1    | channels (1 or 2)                               |
| 6      | 2    | frames in this packet                           |
| 8      | 4    | sequence number, +1 per packet, wraps           |
| 12     | 4    | sample rate (Hz), currently always 48000        |
| 16     | …    | interleaved signed 16-bit PCM, `frames × channels` samples |

The receiver uses the sequence number to detect loss (it conceals up to 16 missing packets by
repeating the previous one at half volume) and to drop late or duplicate packets.
A jump of more than 16 is treated as a restart.

### 2 — HELLO (phone → server)

| Offset | Size | Field                                       |
|--------|------|---------------------------------------------|
| 8      | 8    | sender's monotonic timestamp (opaque to the server) |

The phone sends HELLO every 20 ms. It registers the phone as a client, and the server streams
AUDIO to the packet's source address. Sending this often also keeps many Wi-Fi radios out of
power-save, which would otherwise hold back incoming packets for up to a beacon interval
(about 100 ms). A client that sends nothing for 3 s is dropped.

### 5 — HELLO_ACK (server → phone)

The first 16 bytes of the HELLO, with the type changed to 5. The phone uses the echoed
timestamp to measure the round-trip time.

### 4 — BYE (phone → server)

The phone is leaving. The server stops streaming to it right away.

### 3 — BEACON (server → broadcast, port 47801)

| Offset | Size | Field                                              |
|--------|------|----------------------------------------------------|
| 6      | 2    | audio/control port                                 |
| 8      | 4    | server instance id, random per run                 |
| 12     | …    | display name, UTF-8 (to the end of the packet)     |

Sent once per second to `255.255.255.255` and to the broadcast address of every IPv4 interface,
so that it reaches phones on Wi-Fi as well as over USB tethering. A phone that hears the same
instance id from several addresses shows a single entry and prefers the one on a USB
interface (`rndis*`, `ncm*`, `usb*`).

## Server behaviour

The server exposes a PipeWire sink called `hoparlor_sink`. When the first client connects, it
makes that sink the default output and moves the running streams to it. When the last client
leaves, or the server exits, it restores the previous default.
