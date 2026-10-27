# Publishing Lync on Google Play

Everything the Play Console asks for, ready to copy. Check Google's current rules before submitting:
policies change, and this file can't.

## 1. Build

| Item | Value |
|---|---|
| Package name | `com.santhoshsunkara.lync` (permanent: it can never change once uploaded) |
| Version | 1.0.0 (versionCode 4). Raise `versionCode` in `android/app/build.gradle.kts` for every upload |
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

**Graphics:**

| Asset | File |
|---|---|
| App icon, 512 × 512 | `brand/icon/out/play-icon-512.png` |
| Feature graphic, 1024 × 500 | `brand/store/out/feature-graphic.png` (built by `brand/store/feature_graphic.py`) |
| Phone screenshots, 2–8, 9:16 | `brand/store/out/screenshots/` (taken on the phone with sample data) |

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

Why "No" holds: Play's own exemption covers data that is end-to-end encrypted so that only the
sender and the recipient can read it, with no key available to the developer or anyone in between.
Lync's link is exactly that (Noise IK between the user's own two devices, keys made on them at
pairing; `docs/PROTOCOL.md`), and nothing goes to any server.

**Advertising ID:** No (the app has no `AD_ID` permission and no ads SDK).

If a reviewer disagrees, the fallback is to declare SMS, call log, contacts, and "other in-app messages"
as **collected, processed ephemerally, not shared, for app functionality**.

### Sensitive permissions

These need extra declarations, and they are where a rejection is most likely.

**SMS and Call Log** (`RECEIVE_SMS`, `READ_SMS`, `SEND_SMS`, `READ_CALL_LOG`): fill in the
Permissions Declaration Form.
- Choose the exception **"Cross-device synchronization or transfer of SMS or calls"** (Play's own
  example is phone to laptop). It covers exactly these four permissions. (Checked October 2026:
  support.google.com/googleplay/android-developer/answer/10208820.)
- Core function, ready to paste: "Lync syncs the user's SMS and calls from their Android phone to their
  own Mac: they read and reply to texts, see who is calling and answer or decline, and get one-time
  codes there, over an end-to-end encrypted link between the two devices. Nothing goes to any server."
- Why no alternative works: the SMS Retriever API only delivers messages meant for the app itself, and
  the user needs every incoming text and call on the Mac.
- Say that Lync isn't the default SMS app, and that each feature is off until the user turns it on,
  after an in-app explanation.
- Attach a short video showing a text and a call on the Mac (below).
- Reading SMS only to find login codes is **not** an allowed reason on its own ("account verification
  via SMS" is a listed invalid use). Present codes as part of the sync, which is how the app works.

**Foreground service** (`connectedDevice`): in the Console, describe the always-on link to the paired
Mac and attach a video showing the persistent notification.

**Full-screen intent** (`USE_FULL_SCREEN_INTENT`, for "Ring my phone" over the lock screen): in the
declaration, say the app does **not** have calling or alarm as its core function. Play then doesn't
grant it by default on Android 14+; Settings › Notifications › "Ring over the lock screen" opens
Android's page for it and shows whether it's on. Without it the ring still sounds and shows as a
heads-up alert. Nothing breaks either way.

**Battery (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`): not used.** Play accepts that permission for a
companion app only when its connection gives the device internet access, which Lync's doesn't. So
"Run in background" explains the step, then opens Lync's App info page (Battery › Unrestricted),
which needs no permission and no declaration; coming back, the switch shows whether it worked.

**Notification access:** no form, but the in-app prominent disclosure rule applies (below).

**Prominent disclosure:** Play requires an in-app explanation before the system permission prompt for
SMS, call log, contacts and notifications.
- Lync's Settings rows say what each feature does before you switch it on.
- Make sure each one also says plainly that the data goes **only to your paired Mac**.

**If SMS/Call Log is rejected:** ship a `play` flavour without those permissions (codes from app
notifications still work), and keep the full version on GitHub and F-Droid.

## 5. Demo videos for the declarations

Short screen recordings (30 to 60 seconds, unlisted YouTube links in the forms):

1. **SMS and calls:** a text arriving on the phone and shown on the Mac, a reply typed on the Mac and
   sent; a call ringing on the phone and answered from the Mac's notch.
2. **Foreground service:** the persistent "Connected to <Mac>" notification, and the link staying up
   with the screen off.

Record the phone with `adb shell screenrecord` and the Mac with ⇧⌘5, side by side.

## 6. Before you press "Send for review"

Done in the repo:
- [x] Handshake moved to Noise (Noise_IK_25519_ChaChaPoly_SHA256), checked against the official test
      vectors and between the two apps; the Mac's key sealed by the Secure Enclave; development
      switches compiled out of release Mac builds (`docs/PROTOCOL.md`, v3).
- [x] Licence: Apache 2.0 (`LICENSE`).
- [x] Feature graphic and screenshots (`brand/store/out/`).
- [x] Release bundle builds signed with the upload key; native libraries 16 KB aligned (Android 15+).
- [x] In-app disclosure before each permission prompt says what is read and that it goes only to the
      paired Mac, encrypted.
- [x] "Get update" on the phone opens Lync's Play Store page when it was installed from Play
      (Play's rule: an app from Play updates only through Play); the Mac's update shares the Mac
      download link instead of opening it here.

Only you can do:
- [ ] Back up the upload key and its password (`~/Documents/signing/`).
- [ ] Make the privacy policy reachable at a public URL (make the repo public, or any page host).
- [ ] Record the two demo videos (above).
- [ ] Do a trademark search for "Lync" (a former Microsoft product name) in the countries you launch in.
- [ ] Pay the $25, verify your identity, and run the closed test: 12+ testers for 14 days.
- [ ] For the Mac app download: an Apple Developer account ($99 a year), then
      `SIGN_IDENTITY="Developer ID Application: …" NOTARY_PROFILE=<profile> scripts/build-app.sh release dmg`
      and attach `build/Lync.dmg` to a GitHub release (the phone's "Get Lync for Mac" opens the releases page).
- [ ] An independent review of the link's cryptography is still worth having before a wide launch.
