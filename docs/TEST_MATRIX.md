# Test matrix and acceptance gates

Automated outcomes are in VERIFICATION.md. Every row below is **NOT RUN on devices** unless later updated with phone models, Android versions, network/carrier, date and result. Do not replace this with an inferred “pass” from compilation.

| Area | Required checks | Acceptance |
|---|---|---|
| Setup/sign-in | Missing config, wrong password, offline login, outsider account, malformed or third family member | Clear error; no public room or capture; configured fresh install shows sign-in |
| Permissions | Deny mic; deny camera; deny permanently; revoke in Settings mid-call; accept audio-only | No crash/capture without permission; actionable recovery |
| Two-way audio | Same Wi-Fi then different Wi-Fi, California–India route; mute/unmute both ends | Audible in both directions; mute actually stops sending |
| Video | Both directions, video on/off, audio-to-video, switch front/back, no camera device | Camera indicator stops when off/ended; no stale frames; remote track present |
| Call races | Simultaneous dial, double Answer, cancel during remote Answer, decline before old accepted callback, hangup before claim returns, repeated dial | One bounded call; no media after decline/cancel; no resurrected call |
| Lifecycle | Rotate, Home, screen lock, process kill, return, app-switch permission sheet, incoming cellular call | Foreground limitation clear; mic/camera released; no automatic hidden resume |
| Audio output | Earpiece/speaker, wired connect/unplug, Android12+ BT connect/disconnect, focus loss | No unexpected speaker routing; no continued capture after focus/end |
| Handover | Wi-Fi→cellular and cellular→Wi-Fi, both directions, repeated handover, disconnect during SDP/ICE | Media recovers within20s or stops with explicit error; no endless spinner |
| Weak network | Loss, high RTT, low bandwidth, airplane mode, captive portal | Prefer audio; bounded timeout; no paid fallback; stable UI |
| Restrictive NAT | Symmetric/CGNAT or blocked UDP route | Honest direct-connect failure; no false connected success |
| Privacy | Mic/camera indicators and traffic after hangup/background; logs; APK contents | No recording/admin keys/secrets/SDP logs; media stops |
| Session safety | 45s unanswered, 30s initial failure,20s recovery failure,2h ceiling | Correct cancellation and cleanup; stale generation cannot modify replacement |
| Billing/quotas | Console Spark/no billing, quota-exhausted behavior, database payload growth | Unavailable at quota; no billable route/services automatically enabled |

Before handing out a release APK: signed build, key backup outside repo, verify checksum, install/update on both actual Android versions, measure CPU/battery/heat over a30-minute call, check native16KB page-size compatibility, independent rules and threat-model review. A successful emulator rules test does not verify media encryption implementation, UI behavior, real NAT traversal, or endpoint identity authenticity.
