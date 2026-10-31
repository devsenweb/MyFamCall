# My Fam Call

A private, two-person Android audio/video calling **foreground prototype** for Senthil in California and Shalini in India. Source-first, for continued work with Claude. Not a production replacement for a dependable calling service yet.

## The hard boundary: no paid usage

- Raw WebRTC sends media directly between phones. Public STUN performs address discovery. **No TURN, media server, paid calling API, billing account, or paid fallback.**
- Firebase **Spark** Authentication (email/password) and Realtime Database carry identity and signaling only. Keep billing unlinked. Do not upgrade to Blaze or enable billable services. Quota exhaustion must mean unavailable calls, not charges.
- This is **not literally serverless**: Firebase and STUN are external services. Their availability/terms can change. No promise of universal direct connectivity.
- Restrictive NATs, firewalls, blocked UDP, or some cellular networks may prevent calls. The UI times out with an explicit no-relay failure. Switching networks may help, but audio-only cannot solve every NAT failure.
- Existing phone internet/mobile-data charges are outside app-service usage and still apply. No Play Store publication or fee is required to sideload a locally built APK.

## Implemented in source

- Android 8+ native Java UI; administrator-provisioned email/password sign-in; exact two-member family allowlist.
- Atomic single-call claim, accept/decline/cancel/hangup, stale-session guards, local answer consent, 45-second ringing and 30-second connection limits.
- P2P audio/video, explicit microphone/camera permissions, mute, camera on/off, switch camera, speaker/earpiece routing. Android 12+ offers available communication devices; older Bluetooth routing is not implemented.
- One designated caller handles SDP offers; revision-tagged signaling, buffered ICE, bounded ICE restarts after network change/disconnection, 20-second recovery limit. Continual gathering is enabled.
- Conservative 640×360/15fps capture and bitrate caps; sustained poor outgoing-network estimates turn the local camera off to preserve audio. Not a guarantee of network quality.
- Media capture stops on hangup and when the Activity leaves the foreground. Screen stays awake during a call. No media recording, analytics, ads, AI, groups, iOS, or address-book access.
- Locked Firebase rules and executable emulator security tests. No credentials, membership UIDs, or live project configuration are included.

## Deliberately not implemented / not verified

**Both phones must keep the app open.** No push sender, FCM, incoming notification, full-screen call UI, Telecom integration, foreground call service, killed-app ringing, or lock-screen/background calling. Leaving the app ends the call. These are separate work, not features hidden behind a setup step.

No live Firebase project has been created or deployed. No two-phone media call, India–USA carrier path, Bluetooth device, or Wi-Fi↔cellular handover has been validated. Review `docs/TEST_MATRIX.md` before relying on it. See `docs/VERIFICATION.md` for the precise build/test result; source implementation is not device verification.

## Build locally

Requirements: JDK 17 or 21, Android SDK Platform 35 / Build Tools 35.0.0, internet to fetch pinned open-source dependencies. AGP 8.9.2 / Gradle 8.11.1 are pinned. Import this directory in Android Studio, or set `ANDROID_HOME` / an untracked `local.properties` with `sdk.dir=...`.

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
./scripts/test-core.sh
# Optional smaller preview for ARM64 phones only:
./gradlew -Parm64Only :app:assembleDebug
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`. Without configuration the app intentionally opens a setup-needed screen, not an open/public room. A debug APK is for private testing, not a signed distribution release. Create and safeguard a release signing key **outside git** before stable family distribution; preserving that key is necessary for future upgrades.

## Private Firebase setup (owner action; not performed here)

1. Create/select a Firebase project on **Spark**, with **no billing account**. Do not enable Analytics or billable extras for this prototype. Use email/password, not SMS phone authentication.
2. Register Android package `com.myfamcall.app`; create a Realtime Database in locked mode. Choose a region knowingly: signaling metadata resides there, but the app has no hosted media relay.
3. Enable Authentication email/password and **administrator-create only the two intended users**. No signup UI is included. Secure the accounts with unique strong passwords. Keep the Firebase/Google project owner account protected with MFA.
4. Review and run the rules tests below, then explicitly deploy `signaling/database.rules.json` to the selected database. Never use test-mode/read-all/write-all rules. The Firebase CLI deployment is an owner-authorized external change, not performed by this repository.
5. Using the Firebase console as administrator, create exactly:
   ```json
   {"families":{"YOUR_PRIVATE_FAMILY_ID":{"members":{"SENTHIL_AUTH_UID":true,"SHALINI_AUTH_UID":true}}}}
   ```
   Use a random family ID of 8–64 letters/digits/underscores/hyphens. Do not write a `call` record yourself. Clients cannot grant membership.
6. Copy `config/firebase-config.example.json` to `app/src/main/assets/firebase-config.json` (create `assets/` if needed), then supply the actual Android Firebase app ID, API key, project ID, database URL and family ID. This file is gitignored. Firebase client API keys are identifiers, **not an authorization boundary**; the allowlist and rules protect signaling. Never place a service-account JSON, admin key, FCM server credential, or user password in the app/repo.
7. Rebuild/install the APK on both phones and sign in with their separate accounts. First test on one Wi-Fi, then different networks, then the actual India–USA path. Keep both apps visible. Never claim success before checking both directions of audio/video and teardown.
8. Confirm Spark/billing state in the console and watch database quota manually. Spark currently lists 100 simultaneous connections, 1 GB stored data and 10 GB/month downloaded data. No automated quota/plan enforcement is implemented in the app. Project-owner changes can invalidate the no-charge arrangement.

A public Firebase client API key may permit creation of additional **Auth** accounts through Firebase's API even without an app signup screen; those accounts still cannot access this family database. Authentication quota abuse and project-owner compromise remain risks. See `docs/SECURITY.md`.

## Rules tests (local only, no Firebase login required)

```sh
cd signaling
npm ci
npm test
```

This uses the demo project ID and local emulator, not a live backend. Java21 and Node20+ must be available for the emulator test tools. Pinning and `package-lock.json` are included. Local emulator ports can be changed in `signaling/firebase.json` if occupied.

## Handoff / structure

- `app/.../MainActivity.java`: UI, explicit consent, call lifecycle, timeouts and network recovery.
- `RtcEngine.java`: raw WebRTC and media lifecycle; `AudioRoute.java`: foreground audio.
- `SignalClient.java`: authenticated bounded signaling; `CallPolicy.java`: pure timing/revision policy.
- `signaling/`: database rules, emulator configuration, test fixtures.
- `docs/ARCHITECTURE.md`, `docs/SECURITY.md`, `docs/TEST_MATRIX.md`, `docs/VERIFICATION.md`, `CLAUDE.md`.

No GitHub remote is assumed. The user requested their own GitHub, but creation of a separate repository/publication is still pending. The connected account has been identified as `devsenweb`; do not use an existing unrelated repository. Do not push elsewhere or create a public repository as a substitute.

## Primary references checked 2026-10-31

[Android AGP 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes), [Firebase Android setup](https://firebase.google.com/docs/android/setup), [Firebase BoM release notes](https://firebase.google.com/support/release-notes/android), [Firebase pricing](https://firebase.google.com/pricing), [Firebase rules](https://firebase.google.com/docs/database/security), [WebRTC Android source](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/PeerConnection.java), [GetStream raw WebRTC library](https://github.com/GetStream/webrtc-android), [Android communication audio routing](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)). This project uses the open-source raw WebRTC artifact, not the Stream calling service.
