# Wallhop link protocol, v1

How the phone app and the Mac app talk. Both implementations must match this file exactly:
Mac in `mac/Sources/WallhopMac/Core`, Android in `android/app/src/main/java/app/wallhop/core`.

> Status: development. The handshake below is a small custom design built from standard parts
> (X25519, HKDF-SHA256, ChaCha20-Poly1305). Before a public release, replace it with an audited
> Noise protocol implementation and get it reviewed.

## Transport

- TCP. The Mac listens on port **47474** (falls back to a random port if taken) and advertises
  Bonjour service **`_wallhop._tcp`**. The phone always opens the connection.
- One long-lived connection per phone. Either side may close it; the phone reconnects.

## Framing

Every frame is a 4-byte big-endian length followed by that many bytes of payload.
Maximum payload: 1 MiB.

## Keys

Each device has a long-term X25519 key pair, created on first launch and never sent anywhere
except as a public key. A device id is the first 8 bytes of SHA-256(public key), in hex.

## Handshake frames (plain JSON)

All handshake payloads are UTF-8 JSON objects with a `"t"` field. Binary values are base64.

### Pairing (first time only)

1. Phone → Mac `{"t":"pair","v":1,"name":"Nothing Phone (3)","pub":"<phone static>"}`
2. Mac → Phone `{"t":"pair_wait","name":"MacBook Air","pub":"<mac static>"}`
3. Both show a 6-digit code: SHA-256("wallhop-pair-v1" ‖ phonePub ‖ macPub), first 4 bytes as a
   big-endian unsigned integer, modulo 1 000 000, zero-padded. The user checks they match and
   accepts on the Mac.
4. Mac → Phone `{"t":"pair_ok"}` or `{"t":"pair_no"}`. On `pair_ok` both sides store the other's
   public key and name. The phone then continues with a session handshake on the same connection.

### QR pairing (no code to compare)

The Mac's panel shows a QR with a link:
`wallhop://pair?v=1&n=<mac name>&h=<ipv4,ipv4,…>&p=<port>&k=<mac static pub>&t=<secret>`
(`k` and `t` are URL-safe base64 without padding; `t` is 16 random bytes). One QR is live at a
time, works once, and expires after two minutes; the panel refreshes it.

`h` lists every IPv4 address the Mac has, Wi‑Fi and Ethernet first. The phone tries them all
at once and uses the first that accepts a connection. It connects to that address and port and sends the usual `pair` frame plus
`"qr":"<base64 HMAC-SHA256(key = t, "wallhop-qr-v1" ‖ phonePub ‖ macPub)>"`. The secret never
crosses the network, so someone watching the Wi‑Fi can't reuse it, and swapping in another
phone key breaks the HMAC.

The Mac answers `pair_wait`, then `pair_ok` straight away if the proof matches the live QR
(using it up), or `pair_no` if it doesn't. The phone checks that `pair_wait`'s `pub` equals `k`
before trusting the Mac. After `pair_ok` everything continues as in normal pairing.

### Session (every connection)

1. Phone → Mac `{"t":"hello","v":1,"name":"…","pub":"<phone static>","eph":"<phone ephemeral>"}`
2. If the phone's public key isn't paired: Mac → Phone `{"t":"unpaired","pub":"<mac static>"}` and closes.
   The phone only drops its pairing if `pub` is the Mac it paired with; any other Mac is skipped.
   Otherwise Mac → Phone `{"t":"hello_ok","name":"…","pub":"<mac static>","eph":"<mac ephemeral>"}`.
   The phone checks `pub` matches the Mac it paired with.
3. Both compute
   - `ikm = DH(ePhone, eMac) ‖ DH(sPhone, eMac) ‖ DH(ePhone, sMac)`
   - `salt = SHA-256(phonePub ‖ phoneEph ‖ macPub ‖ macEph)`
   - `kPhoneToMac = HKDF-SHA256(ikm, salt, info "wallhop v1 phone->mac", 32)`
   - `kMacToPhone = HKDF-SHA256(ikm, salt, info "wallhop v1 mac->phone", 32)`
4. From here every frame is sealed (below). The phone sends `ready`, the Mac answers `ready`.
   A frame that fails to decrypt closes the connection.

## Sealed frames

`payload = ChaCha20-Poly1305(key, nonce, plaintext)` as ciphertext followed by the 16-byte tag.
The nonce is 4 zero bytes followed by a 64-bit big-endian counter, starting at 0 for each
direction of each connection and increasing by one per frame. Nonces are never sent.

Plaintext: 2-byte big-endian header length, the header (UTF-8 JSON with `"t"`), then an
optional binary body.

## Messages

| `t` | Direction | Header fields | Body | Reply |
|---|---|---|---|---|
| `ready` | both | | | |
| `ping` / `pong` | both | | | `pong` |
| `text` | both | `id` | UTF-8 text | `ack` |
| `otp` | phone → Mac | `id`, `code`, `sender`, `app` | | `ack` |
| `status` | phone → Mac | `battery` (0–100), `charging` | | |
| `theme` | phone → Mac | `p`, `a`: the phone's Material You primary and tertiary (accent) colours, ARGB as numbers. Never use `t` as a field name: it's the message type | | |
| `mac_info` | Mac → phone | `battery`, `charging`, `locked`, `laptop`, `notch` (bools), `wp` (wallpaper hash) | wallpaper JPEG, only when `wp` changed | |
| `batch_start` | both | `batch`, `count`, `bytes` | | |
| `file_start` | both | `id`, `batch`, `name`, `size`, `mime` | | |
| `file_chunk` | both | `id` | up to 256 KiB | |
| `file_end` | both | `id`, `sha256` (hex) | | `ack` (`ok`, `err`) |
| `batch_end` | both | `batch` | | |
| `ack` | both | `id`, `ok` (bool), `err` (optional) | | |
| `notif` | phone → Mac | `key`, `pkg`, `app`, `title`, `text`, `time` (ms), `reply` (bool), `actions` (array of `{"i": index, "title": …, "reply": bool}`) | app icon PNG, only the first time per connection | |
| `notif_removed` | phone → Mac | `key` | | |
| `notif_reply` | Mac → phone | `id`, `key` | UTF-8 reply text | `ack` |
| `notif_action` | Mac → phone | `key`, `i` (action index) | | |
| `notif_dismiss` | Mac → phone | `key` | | |
| `call` | phone → Mac | `id`, `state` (`ringing`, `active`, `ended`, `missed`), `number`, `name`, `sim` (label, only on dual-SIM phones) | | |
| `call_cmd` | Mac → phone | `action` (`answer`, `decline`, `end`) | | |
| `media` | phone → Mac | `active` (bool), `app`, `title`, `artist`, `playing` (bool), `duration` (ms), `position` (ms), `art` (bool: body holds new artwork) | album art PNG when `art` is true | |
| `media_cmd` | Mac → phone | `action` (`play`, `pause`, `toggle`, `next`, `previous`, `volume_up`, `volume_down`) | | |
| `open_url` | both | `url` | | |
| `ring` | Mac → phone | `action` (`start`, `stop`) | | |

Notifications are only sent for apps the user switched on in the phone app.

Files from the phone land in the Mac's private inbox (`~/Library/Application Support/Wallhop/Inbox`)
and go straight to the clipboard, so they can be pasted anywhere. They are moved to
Downloads › From Phone only when the user clicks Save; unsaved files are removed after a day.
The `ack` for `file_end` is sent once a file is safely in the inbox.

Codes reach the Mac's clipboard from three phone sources: the SMS broadcast, a check of the
SMS inbox whenever it changes (catches anything the broadcast missed), and notifications from
any app (where Android allows it; Android 15+ hides codes in many notifications from other
apps). The phone sends each code once, however many sources see it.

### Text messages (SMS)

The Mac asks; the phone answers with `sms_result` carrying the same `id` and a JSON body.
Nothing is synced ahead of time or stored on the Mac.

| `t` | Direction | Header fields | Body | Reply |
|---|---|---|---|---|
| `sms_threads` | Mac → phone | `id` | | `sms_result`, body `{"threads": [...], "sims": [...]}` |
| `sms_thread` | Mac → phone | `id`, `thread` | | `sms_result`, body `{"thread": "…", "messages": [...]}` |
| `sms_send` | Mac → phone | `id`, `address`, `sim` (subscription id, optional) | UTF-8 text | `ack` (`ok`, `err`) |
| `sms_result` | phone → Mac | `id`, `err` (optional, e.g. `permission`) | JSON | |
| `sms_changed` | phone → Mac | `thread` (optional) | | |

Thread: `{"thread", "address", "name", "snippet", "date" (ms), "unread"}`.
Message: `{"id", "body", "date" (ms), "out" (bool), "failed" (bool), "sim"}`.
SIM: `{"id" (subscription id), "label"}`; only listed on phones with more than one active SIM.

`id` and `batch` are strings, unique per sender. Unknown message types are ignored, so newer
versions can add messages without breaking older ones.
