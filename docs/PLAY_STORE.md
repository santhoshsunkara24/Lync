# Publishing Lync on Google Play

Everything the Play Console asks for, ready to copy. Check Google's current rules before submitting:
policies change, and this file can't.

## 1. Build

| Item | Value |
|---|---|
| Package name | `com.santhoshsunkara.lync` (permanent: it can never change once uploaded) |
| Version | 1.0.0 (versionCode 3). Raise `versionCode` in `android/app/build.gradle.kts` for every upload |
| Upload file | `android/app/build/outputs/bundle/release/app-release.aab` |
| Build command | `cd android && ./gradlew :app:bundleRelease` |
| Target SDK | 36 |

**Signing.** Release builds are signed with the upload key in `~/Documents/signing/` (`lync-upload.jks`,
password in `lync-upload.properties`). Gradle finds it through `lync.signing` in
`~/.gradle/gradle.properties`. Neither file is in this repo.

- **Back up both files somewhere safe** (a password manager or an encrypted drive). If you lose the
  upload key, Google can reset it, but that takes days. GitHub APK users can't update without it at all.
- Turn on **Play App Signing** when asked (the default). Google then keeps the key that signs what
  users download, and this key only proves uploads come from you.
- Upload key SHA-256 fingerprint:
  `BD:88:B2:D8:8E:33:D3:A8:F0:2C:F6:81:73:1C:49:61:EC:37:7C:92:81:F4:44:C6:18:C9:21:CB:05:41:99:8A`

**Glyph SDK.** Build the Play bundle with Nothing's Glyph SDK in `android/app/libs/` (see the README
there), or Nothing phones lose the Glyph feature. Its licence allows using it inside your app, forbids
redistributing the file itself (so it stays out of the repo), and says commercial use needs Nothing's
written permission. Lync is free, but if you ever charge for it, ask Nothing first (GDKsupport@nothing.tech).

## 2. Developer account

- **Fee:** $25, once.
- **Identity:** a personal account needs ID verification.
- **Testing rule for new personal accounts:** before production access, run a **closed test with at least
  12 testers who stay opted in for 14 days in a row**. Plan for this: ask friends, the Nothing community, or
  Reddit/Discord testers early. Testers install from a Play link, so they need a Google account and an
  Android phone (and ideally a Mac to actually test).

## 3. Store listing

**App name:** Lync

**Short description (80 max):**
Codes, calls, texts and files from your Android phone, in your Mac's notch.

**Full description:**

> Lync connects your Android phone to your Mac, the way iPhone and Mac work together.
>
> **One-time codes, already copied.** When a login code arrives by SMS or in an app, it's on your
> Mac's clipboard before you pick up your phone. Paste it and you're in.
>
> **Calls on your Mac.** See who's calling in the notch and answer or decline without reaching for
> your phone.
>
> **Texts and notifications.** Read and reply to messages from your Mac, and choose which apps'
> notifications show up there.
>
> **Send anything.** Share files, photos and links from your phone straight to your Mac, or drop them
> on the notch to send them back.
>
> **Ring your phone.** Lost it in the couch? Ring it from your Mac, even on silent.
>
> **Private by design.** No accounts, no cloud, no ads, no tracking. Your phone talks only to your own
> Mac, directly over your Wi-Fi, end-to-end encrypted. Every feature has its own switch.
>
> Lync is free and open source. You'll need the free Lync app for Mac (macOS 14 or later), on the same
> Wi-Fi network as your phone.

**Category:** Tools. **Tags:** productivity, connectivity.
**Contact email:** santhoshreddy1058@gmail.com.
**Privacy policy URL:** host `docs/PRIVACY.md` publicly. The simplest way is to make the repo public; the
file's GitHub link then works as the URL. Otherwise use any free page host.

**Graphics** (all in `brand/kit/out/`):

| Asset | File |
|---|---|
| App icon, 512 × 512 | `play-icon-512.png` |
| Feature graphic, 1024 × 500 | `play-feature-1024x500.png` |
| Phone screenshots, 2–8, 9:16 | Still to do: take them on the phone (Home, a code arriving, Calls, Settings) |

## 4. App content (Policy section)

**Ads:** No.
**Target audience:** 18 and over (keeps the app out of the Families policy).
**Content rating:** fill in the questionnaire; Lync has no user-generated content shared with others,
no violence, no gambling. Expect "Everyone" / PEGI 3.
**News app / health / financial / government:** No to all.

### Data safety

Google counts data as "collected" only when it leaves the device for the developer or a third party.
Lync only sends data to the user's own paired Mac, end-to-end encrypted, and the developer never receives
any of it, so the honest answers are:

- Does the app collect or share any required user data types? **No**
- Is all user data encrypted in transit? **Yes**
- Do you provide a way for users to request their data be deleted? Uninstalling deletes everything;
  there's no account or server data.

If a reviewer disagrees, the fallback is to declare SMS, call log, contacts, and "other in-app messages"
as **collected, processed ephemerally, not shared, for app functionality**.

### Sensitive permissions

These need extra declarations, and they are where a rejection is most likely.

**SMS and Call Log** (`RECEIVE_SMS`, `READ_SMS`, `SEND_SMS`, `READ_CALL_LOG`): fill in the
Permissions Declaration Form.
- Core function: "Connected device companion: lets the user read and send SMS and see and handle calls
  from their paired computer."
- Explain that Lync isn't the default SMS app and that each feature is off until the user turns it on.
- Attach a short video showing a text and a call on the Mac.
- Play's allowed exceptions include companion apps that send and receive SMS from a connected device.
  KDE Connect and Microsoft's Link to Windows are approved this way.
- Reading SMS only to find login codes is **not** an allowed reason on its own. Present codes as part of
  the companion feature.

**Foreground service** (`connectedDevice`): in the Console, describe the always-on link to the paired
Mac and attach a video showing the persistent notification.

**`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`:**
- Justify it as needed to keep the link to the paired device alive.
- If Play rejects it, remove the permission. The app can open the battery settings page instead, with
  `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, which needs no permission.

**Notification access:** no form, but the in-app prominent disclosure rule applies (below).

**Prominent disclosure:** Play requires an in-app explanation before the system permission prompt for
SMS, call log, contacts and notifications.
- Lync's Settings rows say what each feature does before you switch it on.
- Make sure each one also says plainly that the data goes **only to your paired Mac**.

**If SMS/Call Log is rejected:** ship a `play` flavour without those permissions (codes from app
notifications still work), and keep the full version on GitHub and F-Droid.

## 5. Before you press "Send for review"

- [ ] Back up the upload key and its password.
- [ ] Make the privacy policy reachable at a public URL.
- [ ] Take 4+ phone screenshots.
- [ ] Record the SMS/call and foreground service demo videos.
- [ ] Get a security review of the link protocol: `docs/PROTOCOL.md` says the handshake should move to
      an audited Noise implementation before a public release.
- [ ] Do a trademark search for "Lync" (a former Microsoft product name) in the countries you launch in.
- [ ] Choose an open-source licence (add `LICENSE`).
- [ ] Run the closed test: 12+ testers for 14 days.
