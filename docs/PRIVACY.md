# Lync privacy policy

_Last updated: 19 October 2026_

Lync connects your Android phone to your own Mac. This policy explains what the Android app and the Mac
app do with your data.

## The short version

- Lync has **no servers, no accounts, no analytics, no ads and no tracking**.
- Data goes **only from your phone to the Mac you paired it with**, directly over your local network,
  end-to-end encrypted. The developer never receives any of it.
- Nothing is sold or shared with anyone.

## What the app reads, and why

Each feature has its own switch in the app's Settings, and you can turn any of them off at any time.
Nothing below is read while its switch is off or its Android permission isn't granted.

| Feature | Data | What happens to it |
|---|---|---|
| Codes from messages | Incoming SMS (`RECEIVE_SMS`, `READ_SMS`) | Lync looks for one-time codes. Only the code and the sender's name are sent to your Mac |
| Texts | Your SMS conversations (`READ_SMS`, `SEND_SMS`) | Shown on your Mac so you can read and reply. Replies are sent as SMS from your phone |
| Calls | Phone state, the caller's number (`READ_PHONE_STATE`, `READ_CALL_LOG`), contact names (`READ_CONTACTS`) | The caller's name or number is shown on your Mac, where you can answer or decline (`ANSWER_PHONE_CALLS`) |
| Notifications | Notifications from the apps you pick (notification access) | Shown on your Mac. Codes in them are copied the same way as SMS codes |
| Pairing | The camera (`CAMERA`) | Only to scan the QR code your Mac shows. No images are saved or sent |
| Files and links | Files, text and links you choose to share | Sent to your Mac only when you send them |

Lync also shares your phone's battery level, its name and its wallpaper colours with your Mac, so the
Mac app can show them.

## How it is sent

The phone and the Mac find each other on your local network (Bonjour/mDNS) and talk over a direct
connection that is encrypted with keys created on your devices during pairing (X25519,
ChaCha20-Poly1305). Data never passes through the internet or anyone else's computer.

## What is stored

- **On your phone:** your pairing keys, your settings, the apps you chose for notifications, and a short
  list of recent activity (for example, which codes were copied).
  Lync's data is excluded from Android backups, so it never leaves the phone that way.
- **On your Mac:** the pairing keys, your settings, and recent items (codes, notifications, messages)
  so you can see them in the Mac app. You can clear these in the Mac app.

Uninstalling the app removes everything it stored on that device.

## Children

Lync isn't directed at children under 13 and doesn't knowingly collect anything from anyone.

## Changes

If this policy changes, the new version will be published here with a new date.

## Contact

Questions: open an issue at https://github.com/santhoshsunkara24/Lync/issues
