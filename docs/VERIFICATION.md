# Verification record — 2026-10-31

## Verified on the final preview source

| Check | Actual outcome |
|---|---|
| Android `:app:assembleDebug` | **PASS**; real APK generated |
| Android `:app:lintDebug` | **PASS**, 0 errors; 22 warnings retained (20 English inline UI-string warnings, a newer Firebase BoM, and an Android backup-policy lint warning) |
| Android `:app:testDebugUnitTest` | **PASS**, 6 JUnit tests, 0 failures/errors |
| `scripts/test-core.sh` | **PASS**, 23 assertions (timeout/revision/restart/weak-network/ICE-generation policy) |
| Local Firebase Database emulator security tests | **PASS**, 11 tests, 0 failures, emulator4.11.2, demo project only |
| APK signing check | **PASS** via `apksigner verify --verbose`; debug signer, APK signature v2 |
| JSON and signaling JS syntax checks | **PASS** |
| Configuration/secret review | No live Firebase config, passwords, service-account/admin keys, release keystore, or remote credentials added to tracked source |
| GitHub remote publication | **NOT DONE** at this checkpoint; dedicated private repository under connected owner `devsenweb` pending |

The Android SDK and official Gradle/JDK were acquired into `/tmp` only for this cloud build; no system-wide installation or host-setting modification. Gradle8.11.1 and Temurin JDK21 archive SHA-256 were checked against their official download metadata. Initial build attempts exposed the unavailable compiler launcher, read-only default caches, and ephemeral proxy settings. These were resolved with temporary local tools/caches and the workspace-provided trust certificate, not by disabling TLS verification. Final full build succeeded after fixes.

Build output notes: deprecated platform APIs remain for older Android compatibility; native libraries could not be symbol-stripped in this environment and were packaged as provided. The lint tool's optional metrics initialization reported an unwritable home path; lint still ran and produced its complete report. No analytics SDK is included in the application.

## Delivered preview APK

- Filename: `My_Fam_Call_0.1.0_preview_unconfigured.apk`
- Bytes: **48,277,340**
- SHA-256: `353a21053101e5d43d4c073c169dfd50d9ef27b564d65d1659d32e708429fdc1`
- Version: `0.1.0-foreground-prototype` (code1); Android8/API26 minimum, target35.
- Built **without** `firebase-config.json`; it intentionally shows setup instructions. It is not a configured family calling service.
- Debug signature, not a production release key. No automatic installation performed.

## Optional smaller ARM64 preview

An additional packaging-only build with `-Parm64Only` passed assemble/lint/unit-test tasks. It contains **arm64-v8a only**, not 32-bit ARM or x86. APK v2 signature verifies. No calling behavior changed.

- `My_Fam_Call_0.1.0_arm64_preview_unconfigured.apk`
- Bytes: **16,270,312**
- SHA-256: `bc4a5d0abcf5d5f756dc0e2f204056d0abdec82d6ea3cd1fa9254ad46e8abfac`
- The universal preview above was preserved unchanged.

## Independent code review changes included

- Fresh configured install correctly refreshes the sign-in UI.
- Per-call local answer consent; bounded dismissed-call set and operation token prevent stale callbacks reopening declined/canceled calls.
- Caller waits for applied remote answer before another offer.
- Partial WebRTC initialization cleans up native resources.
- Recovery observes ICE state and bidirectional selected-candidate traffic, not only an aggregate connection-state transition, avoiding an obvious seamless-handover false timeout.
- Local ICE username fragments bind candidates to their actual negotiation; delayed old-generation candidates are not relabeled as new.
- Available audio-route selection preserves the displayed device snapshot.
- Database terminal cleanup can survive immediate client closure; stale session writes remain call-ID-bound.

Code review and passing policy/rules tests are **not** proof that every asynchronous Activity/WebRTC race is covered. The controller still needs richer executable state-machine/device tests.

## Not run / remaining gates

No Android emulator UI run, physical-phone installation, actual microphone/camera check, real Firebase project/rules deployment, two-phone media call, India–USA network test, Wi-Fi/cellular handover, Bluetooth/headset test, battery/heat test, native16KB-page device test, or independent security audit. No background ringing/service, release signing key, or trusted push sender exists. Follow TEST_MATRIX.md before relying on this for family calls.

Relevant local logs (not necessary to use the source): `/tmp/myfam-tools/build-delivery.log`, `/tmp/myfam-rules-final.log`; Android reports under `app/build/reports/` (gitignored). Source tests are included so results can be reproduced in another environment.
