# Wallhop link protocol, v3

How the phone app and the Mac app talk. Both implementations must match this file exactly:
Mac in `mac/Sources/WallhopMac/Core`, Android in `android/app/src/main/java/app/wallhop/core`.

> v3 (October 2026) replaces the session handshake, on Wi‑Fi and Bluetooth, with
> **Noise_IK_25519_ChaChaPoly_SHA256** ([noiseprotocol.org](https://noiseprotocol.org/noise.html),
> revision 34). Static keys and device names now travel encrypted, the whole handshake is hashed
> into the keys, and "not paired" answers are authenticated. Pairing is unchanged from v2 (the
> 6-digit code from a commit-then-reveal exchange, or the QR's one-time secret). Sealed frames use
> Noise's cipher states, so their nonces are now little-endian. v1/v2 sessions are refused.
>
> Both implementations are checked against the official Noise test vector and against each
> other's bytes (`docs/vectors/lync-v3.json`): `mac/scripts/crypto-check.sh` and the Android
> `NoiseTest`.

## Transport

- TCP over the local network for everything; Bluetooth LE for codes, calls and notifications when
  TCP can't reach the Mac (see [Bluetooth fallback](#bluetooth-fallback)).
- TCP. The Mac listens on port **47474** (falls back to a random port if taken) and advertises
  Bonjour service **`_wallhop._tcp`**. The phone always opens the connection.
- One long-lived connection per phone. Either side may close it; the phone reconnects.
  A new connection from the same phone (same public key) replaces its old one. Several paired
  phones may be connected at once; the Mac shows one of them and the others take over when it
  leaves.
- A connection must get from connecting to `ready` within **15 s**, or the Mac closes it.
  While a pairing request waits for the user the clock stops, but that wait is capped at
  **120 s** (then the connection closes as if declined); after `pair_ok` the 15 s start again.

## Framing

Every frame is a 4-byte big-endian length followed by that many bytes of payload.
Maximum payload: 1 MiB (1 048 576 bytes); a receiver closes the connection on anything larger.
Senders keep every frame at or under 1 MiB − 64 bytes and never send a bigger one: a message
that wouldn't fit is refused locally. Text longer than that (e.g. a huge clipboard) is sent as
a `.txt` file with the file messages instead.

## Keys

Each device has a long-term X25519 key pair, created on first launch and never sent anywhere
except as a public key. A device id is the first 8 bytes of SHA-256(public key), in hex.

## Handshake frames (plain JSON)

All handshake payloads are UTF-8 JSON objects with a `"t"` field. Binary values are base64.

### Pairing (first time only), v2

Why v2: in v1 the code was SHA-256 of the two public keys only. Someone in the middle could
generate key pairs until one gave the same 6 digits as the real phone's key (about a million
tries, seconds of work) and the user would accept the attacker's key. In v2 the code also
depends on a random nonce from each side, and the phone commits to its nonce before it sees
the Mac's. Whoever is in the middle has to fix their own key and nonce before learning the
other side's nonce, so they get one guess: a 1 in 1 000 000 chance, not a search.

1. Phone → Mac `{"t":"pair","v":3,"name":"Nothing Phone (3)","pub":"<phone static>","commit":"<c>"}`
   where `nonceP` is 16 random bytes and
   `c = SHA-256("lync-pair-commit-v2" ‖ phonePub ‖ nonceP)` (32 bytes, base64).
2. Mac → Phone `{"t":"pair_wait","name":"MacBook Air","pub":"<mac static>","nonce":"<nonceM>"}`
   where `nonceM` is 16 random bytes (base64).
3. Phone → Mac `{"t":"pair_nonce","nonce":"<nonceP>"}`. The Mac recomputes `c` and compares it
   with the commitment from step 1 in constant time. If it doesn't match (or `nonce` is
   missing or not 16 bytes) the Mac closes the connection without showing anything.
4. Both show a 6-digit code: `SHA-256("lync-pair-v2" ‖ phonePub ‖ macPub ‖ nonceP ‖ nonceM)`,
   first 4 bytes as a big-endian unsigned integer, modulo 1 000 000, zero-padded to 6 digits.
   The Mac shows it only after step 3 checks out. The user checks the codes match and accepts
   on the Mac.
5. Mac → Phone `{"t":"pair_ok"}` or `{"t":"pair_no"}`. On `pair_ok` both sides store the other's
   public key and name. The phone then continues with a session handshake on the same connection.

`‖` is byte concatenation; the ASCII labels have no terminator. Public keys are the raw 32-byte
X25519 keys. All binary values in the JSON are standard base64 with padding.

`pair_no` may carry `"err"`: `"version"` (the `pair` frame wasn't `"v":3`, the version of the
session protocol this Mac speaks, which it sends as `"v"`; the phone should ask the user to update), `"busy"` (another phone's pairing request is already on screen; try
again shortly), or `"rate"` (more than 5 pairing attempts from this address in the last
minute). Without `err` the user declined. The Mac closes the connection after any `pair_no`.

The phone must not send `pair_nonce` before it has `pair_wait`, and must not reuse a nonce.

**Test vector.** phonePub = 32 × `0x01`, macPub = 32 × `0x02`, nonceP = 16 × `0x03`,
nonceM = 16 × `0x04`:

- commit = `6d60c260fc54cb7820ca62a0395b94dd3b4aaef9a65e4df4cd282ae0fd2a20e6`
- code hash = `5e67237b65edbf8088b03f36b877fc0544d1a306149e2a9c31fe7fac70b640f1`
  (first 4 bytes `0x5e67237b` = 1 583 817 595)
- code = **`817595`**

### QR pairing (no code to compare)

The Mac's panel shows a QR with a link:
`wallhop://pair?v=1&n=<mac name>&h=<ipv4,ipv4,…>&p=<port>&k=<mac static pub>&t=<secret>`
(`k` and `t` are URL-safe base64 without padding; `t` is 16 random bytes). One QR is live at a
time, works once, and expires after two minutes; the panel refreshes it. The link format is
unchanged (still `v=1`).

`h` lists every IPv4 address the Mac has, Wi‑Fi and Ethernet first. The phone tries them all
at once and uses the first that accepts a connection. It connects to that address and port and
sends the usual `pair` frame (`v`, `name`, `pub`, `commit`) plus
`"qr":"<base64 HMAC-SHA256(key = t, "wallhop-qr-v1" ‖ phonePub ‖ macPub)>"`. The secret never
crosses the network, so someone watching the Wi‑Fi can't reuse it, and swapping in another
phone key breaks the HMAC.

The Mac answers `pair_wait` (with a `nonce`, as in normal pairing), then `pair_ok` straight away
if the proof matches the live QR (using it up), or `pair_no` if it doesn't. The phone checks
that `pair_wait`'s `pub` equals `k` before trusting the Mac. No code is shown or compared: the
QR's secret already authenticates both keys (the phone's through the HMAC, the Mac's through
`k`, which the phone read off the screen), so the commit-then-reveal step isn't needed here.
The phone may still send `pair_nonce` after `pair_wait`; the Mac ignores it (and ignores
`commit`) on this path, so one phone code path serves both kinds of pairing. After `pair_ok`
everything continues as in normal pairing. QR attempts count towards the same 5-per-minute
limit.

### Session (every connection), v3

Noise_IK_25519_ChaChaPoly_SHA256 with the phone as initiator. The phone knows the Mac's static key
from pairing, so it is the IK pre-message (`<- s`). Prologue: the ASCII bytes `lync-tcp-v3`.

```
<- s
...
-> e, es, s, ss
<- e, ee, se
```

1. Phone → Mac, plain JSON `{"t":"hello","v":3,"noise":"<base64 message 1>"}`. Message 1's payload
   is UTF-8 JSON `{"name":"<phone name>","app":"<app version>"}`, so the phone's key and name are
   encrypted to the Mac's key.
2. The Mac reads message 1.
   - It doesn't decrypt (written for another key: Lync was reinstalled on this Mac, or it's another
     Mac): Mac → Phone `{"t":"not_me"}` and closes. This is unauthenticated; the phone may treat it
     only as a hint ("this Mac has a different key").
   - `v` isn't 3: Mac → Phone `{"t":"hello_no","v":3}` and closes; the phone tells the user which
     side to update, and so does the Mac (once).
   - Otherwise Mac → Phone `{"t":"hello_ok","noise":"<base64 message 2>"}`, message 2's payload being
     `{"name":"<Mac name>","app":"<app version>","paired":true}`. If the phone's key (learned from
     message 1) isn't paired with this Mac, the payload is `{"app":"…","paired":false}` and the Mac
     closes after sending it. That answer is authenticated: only the Mac the phone paired with
     could have written it.
3. Both `Split()`: the first cipher state is phone → Mac, the second Mac → phone.
4. From here every frame is sealed (below). The phone sends `ready`, the Mac answers `ready`.
   A frame that fails to decrypt closes the connection. A new `name` is saved only after `ready`.

Right after `pair_ok` the phone runs this handshake on the same connection, and says "paired"
only once it succeeds; that session is then its first connection. So a forged `pair_ok` pairs
nothing.

Until the session is up the Mac accepts frames of at most 16 KiB and at most 16 connections
that haven't finished the handshake; pairing is limited to 5 attempts per address and 20 in
total per minute.

## Sealed frames

`payload = EncryptWithAd(cipher state, ad = empty, plaintext)` (Noise): ChaCha20-Poly1305
ciphertext followed by the 16-byte tag. The nonce is 4 zero bytes followed by a 64-bit
**little-endian** counter, starting at 0 for each direction of each connection and increasing by
one per frame. Nonces are never sent.

Noise caps a message at 65 535 bytes; Lync keeps its own framing (above, up to 1 MiB) and seals
each frame with the transport cipher states, which ChaCha20-Poly1305 allows at any length.

Plaintext: 2-byte big-endian header length, the header (UTF-8 JSON with `"t"`), then an
optional binary body.

## Bluetooth fallback

When the phone can't reach its Mac over Wi-Fi (different networks, Wi-Fi off), codes, calls and
notifications go over Bluetooth LE instead. Mac in `Core/BleWire.swift` and `Link/BluetoothLink.swift`,
Android in `core/BleWire.kt` and `link/BleLink.kt`. No other relay of any kind is involved.

**Path selection.** Wi-Fi always wins. The phone advertises only while it is paired, the Bluetooth
switch is on, Android allows it, and the TCP session has been down for **5 s**. The Mac scans only
while no phone is connected to it over TCP, also after **5 s**. A TCP session coming up on either side
closes the Bluetooth link at once. Codes waiting for the Mac (kept up to five minutes) are sent as soon
as the Bluetooth link is ready. Files, text, links, texts (SMS) and everything else stay Wi-Fi only.

**Roles.** The phone is the GATT peripheral, the Mac the central.

- Service `6c796e63-0001-4b1e-9c3a-5d2f7e8a1b40`, one characteristic
  `6c796e63-0002-4b1e-9c3a-5d2f7e8a1b40` with write (Mac → phone, write with response) and notify
  (phone → Mac). The Mac subscribes (CCCD) before it writes anything.
- Advertising data: the service UUID only (no device name). Scan response: service data for the same
  UUID holding an 8-byte **tag**. The Mac connects only to an advertisement whose tag matches one of its
  paired phones; it never contacts anything else.

**Tag.** `ss = DH(sPhone, sMac)` (the long-term pairing keys),
`tagKey = HKDF-SHA256(ss, salt = SHA-256(phonePub ‖ macPub), info "lync ble v1 tag", 32)`,
`tag = HMAC-SHA256(tagKey, "lync-ble-tag" ‖ window)` first 8 bytes, where `window` is
`floor(unix seconds / 900)` as a 64-bit big-endian integer. The phone advertises the current window's
tag (and switches within a minute of a new window); the Mac accepts the current window and either
neighbour. Only the two paired devices can compute it, and it changes every 15 minutes, so it isn't a
tracking identifier.

**Fragments.** Every GATT write or notification is one header byte, `1` on the last piece of a frame
and `0` when more follow, then up to MTU − 4 bytes of the frame. The receiver joins pieces until a
`1`; a frame over **16 KiB**, an empty piece or any other header byte closes the link. Long (prepared)
writes aren't used.

**Handshake.** Within **10 s** of connecting (the phone) / 15 s of finding the phone (the Mac), or the
link is closed. Noise_IK_25519_ChaChaPoly_SHA256 again, but with the **Mac as initiator** (it knows
which phone it's talking to from the tag) and the prologue `lync-ble-v3`, so a Wi‑Fi handshake
can't be replayed here or the other way round:

1. Mac → phone, plain JSON `{"t":"hello","v":3,"noise":"<base64 message 1>"}`, empty payload. The
   phone reads it and closes the link unless it decrypts and the Mac's static key inside is the
   Mac it paired with. Nothing is sent back to anyone else, and the phone's own key never crosses
   the air: a stranger can't use the link to recognise or follow the phone.
2. Phone → Mac, plain JSON `{"t":"hello_ok","noise":"<base64 message 2>"}`, empty payload.
3. Both `Split()` (first cipher state Mac → phone, second phone → Mac).
4. Mac → phone sealed `ready`; the phone answers with a sealed `ready`. Only then does either side use
   the link. Sealing is exactly as in [Sealed frames](#sealed-frames): fresh keys per connection (so
   frames can't be replayed into another connection), and a frame that fails to decrypt closes the
   link.

A phone the Mac finds already connected at the system level has no tag to say which phone it is;
the Mac then tries its paired phones in turn, one per attempt.

**Messages over Bluetooth.** Phone → Mac: `otp` (answered with `ack`), `call`, `notif` (never with an
icon body), `notif_removed`, `forget`, `ping` (answered with `pong`). Mac → phone: `ack`, `ping`, `pong`,
`call_cmd`. Both sides ignore every other type on this path.

**Keeping the link honest.** A link can stay up on the radio and carry nothing (the Mac slept holding
it). So, once ready, each side sends `ping` every 15 s and closes the link after 45 s without
receiving anything. The Mac also pings as soon as it wakes and closes the link if nothing comes back
within 6 s. When an `otp` gets no `ack` within 10 s, the phone closes the link, keeps the code, and
advertises again at once; the code goes across on the next link. A Mac may subscribe over a radio link
that never went down: the phone takes that as a new connection. On such a link the Mac unsubscribes and
subscribes again before its `hello` (macOS may still count it as subscribed and never confirm), and
closes the link if the phone's services change. While it has no link, every 10 s the Mac restarts its
scan and also tries a phone already connected at the system level, whose advertisements macOS may not
report; that phone is identified by the key in its `hello_ok`.

**Test vectors.** Private keys: phone static = 32 × `0x11`, phone ephemeral = 32 × `0x22`,
Mac static = 32 × `0x33`, Mac ephemeral = 32 × `0x44` (X25519, clamped as usual).

- tag for window 1 000 000 = `eb87fa08c61a1082`
- the Wi‑Fi and Bluetooth handshakes with these keys, byte for byte, and the first sealed `ready`
  on each: `docs/vectors/lync-v3.json`.

## Keys at rest

- **Phone:** the private key is encrypted with an AES key held in the Android Keystore; Lync's data is
  excluded from backups.
- **Mac:** the private key is sealed by the Secure Enclave (`identity.sealed`: ECIES on P-256 with an
  Enclave key, HKDF-SHA256, AES-GCM), so a copy of the file is useless on any other machine. Macs
  without a Secure Enclave keep it in a user-only file. A file restored onto another Mac can't be
  opened there: Lync sets it aside (never overwrites it), makes a new key, and phones pair again.
  A plain key from an earlier build is sealed, checked, then deleted (older backups may still hold
  it).

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
| `batch_cancel` | phone → Mac | `batch` | | The phone cancelled the send part-way: the Mac drops the batch's unfinished file at once and keeps any files that already arrived whole |
| `ack` | both | `id`, `ok` (bool), `err` (optional) | | |
| `notif` | phone → Mac | `key`, `pkg`, `app`, `title`, `text`, `time` (ms), `reply` (bool), `actions` (array of `{"i": index, "title": …, "reply": bool}`) | app icon PNG, only the first time per connection | |
| `notif_removed` | phone → Mac | `key` | | |
| `notif_reply` | Mac → phone | `id`, `key` | UTF-8 reply text | `ack` |
| `notif_action` | Mac → phone | `key`, `i` (action index) | | |
| `notif_dismiss` | Mac → phone | `key` | | |
| `call` | phone → Mac | `id`, `state` (`ringing`, `active`, `ended`, `missed`), `number`, `name`, `sim` (label, only on dual-SIM phones) | | |
| `call_cmd` | Mac → phone | `action` (`answer`, `decline`, `end`) | | |
| `media` | phone → Mac | `active` (bool), `app`, `title`, `artist`, `playing` (bool), `duration` (ms), `position` (ms), `art` (bool: body holds new artwork), `new_track` (bool: a different track or app; without new artwork the old one no longer applies) | album art JPEG when `art` is true | |
| `media_cmd` | Mac → phone | `action` (`play`, `pause`, `toggle`, `next`, `previous`, `volume_up`, `volume_down`) | | |
| `open_url` | both | `url` | | |
| `ring` | Mac → phone | `action` (`start`, `stop`) | | |
| `ring` | phone → Mac | `action` (`stopped`): the phone stopped ringing by itself (silenced on the phone, or its own timeout) | | |
| `forget` | phone → Mac | | | |

Notifications are only sent for apps the user switched on in the phone app.

`ring`: the Mac shows "Stop ringing" from `start` until the phone reports `stopped`, or for
30 s if no report comes (older phones).

`forget`: sent by the phone over the authenticated session (a sealed frame, after `ready`)
just before it disconnects, when the user forgets this Mac on the phone. Exactly
`{"t":"forget"}`, no other fields and no reply. The Mac removes that phone (the session's
public key) from its paired phones, as Forget in its own Settings does but without asking,
closes the connection and tells the user "<phone> unpaired this Mac". It's accepted from a
paired phone that is connected in the background too. A phone that is forgotten while the
Mac is unreachable is simply answered with `"paired":false` the next time it connects (Session,
step 2).

Files from the phone land in the Mac's private inbox (`~/Library/Application Support/Wallhop/Inbox`)
and go straight to the clipboard, so they can be pasted anywhere. They are moved to
Downloads › From Phone only when the user clicks Save; unsaved files are removed after a day.
The `ack` for `file_end` is sent once a file is safely in the inbox. Received files are
quarantined (`com.apple.quarantine`, agent "Lync"), so Gatekeeper checks them before they open.
A `file_start` with an id that's already in progress replaces it; chunks beyond the announced
`size` fail the file. A sender reports a batch as sent only after an `ack` for every
`file_end` (it gives up 30 s after the last chunk). If the connection drops mid-batch, the
receiver discards partial files and treats the batch as interrupted.

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
| `contacts` | Mac → phone | `id`, `q` (what's typed in the "To:" field, at least 1 character) | | `sms_result`, body `{"items": [...]}` |
| `sms_result` | phone → Mac | `id`, `err` (optional, e.g. `permission`, `unavailable`) | JSON | |
| `sms_changed` | phone → Mac | `thread` (optional) | | |

Thread: `{"thread", "address", "name", "snippet", "date" (ms), "unread"}`.
Message: `{"id", "body", "date" (ms), "out" (bool), "failed" (bool), "sim"}`.
SIM: `{"id" (subscription id), "label"}`; only listed on phones with more than one active SIM.

Contacts search: the Mac sends `{"t":"contacts","id":"<id>","q":"<query>"}` about 250 ms after
the user stops typing in the "To:" field of a new text. The phone answers with an
`sms_result` carrying the same `id` and the body `{"items": [{"name": "…", "number": "…"}, …]}`:
at most 20 contacts whose name or number contains `q`, one item per phone number. If it can't
search (Contacts not allowed for Texts, or the Texts feature is off) it answers
`{"t":"sms_result","id":"<id>","err":"unavailable"}` with no body, and the Mac shows "Allow
Contacts for Texts on your phone to search names"; a raw number can always be typed. Replies
are matched to requests by `id`, like the other requests here; the Mac uses only the reply to
its newest query and gives up on a request after 15 s.

`id` and `batch` are strings, unique per sender. Unknown message types are ignored, so newer
versions can add messages without breaking older ones.
