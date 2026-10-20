# Wallhop link protocol, v2

How the phone app and the Mac app talk. Both implementations must match this file exactly:
Mac in `mac/Sources/WallhopMac/Core`, Android in `android/app/src/main/java/app/wallhop/core`.

> v2 (October 2026) changes only pairing: the 6-digit code now comes from a commit-then-reveal
> exchange of nonces (below), and `pair` must carry `"v":2`. The session handshake, sealed frames
> and messages are unchanged from v1.
>
> Status: development. The handshake below is a small custom design built from standard parts
> (X25519, HKDF-SHA256, ChaCha20-Poly1305). Before a public release, replace it with an audited
> Noise protocol implementation and get it reviewed.

## Transport

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

1. Phone → Mac `{"t":"pair","v":2,"name":"Nothing Phone (3)","pub":"<phone static>","commit":"<c>"}`
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

`pair_no` may carry `"err"`: `"version"` (the `pair` frame wasn't `"v":2`; the phone should ask
the user to update), `"busy"` (another phone's pairing request is already on screen; try
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
sends the usual v2 `pair` frame (`v`, `name`, `pub`, `commit`) plus
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

### Session (every connection)

1. Phone → Mac `{"t":"hello","v":2,"name":"…","pub":"<phone static>","eph":"<phone ephemeral>"}`
   (the Mac doesn't check `v` here; the session is the same as v1). A changed `name` is only
   saved once the phone's `ready` has decrypted, which proves it holds its key.
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
Mac is unreachable is simply answered with `unpaired` the next time it connects (Session,
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
