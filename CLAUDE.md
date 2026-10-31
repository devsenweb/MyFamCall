# Claude handoff: My Fam Call

User intent: private family-only installed Android APK, initially Senthil (California) and wife Shalini (India), two-person audio/video. **Absolutely no paid usage**, billing, rented infrastructure, paid TURN or paid fallback. No iOS/groups/AI now. User wants source in their own GitHub and planning notes in Google Drive.

Planning document: https://docs.google.com/document/d/1wquK8iVv1kPLcxIP4itJu0Z5laKd0-lbhzAuJe_7S9U (access depends on owner's Google account).

## First read

README, docs/VERIFICATION.md, docs/ARCHITECTURE.md, docs/SECURITY.md, docs/SIGNALING.md, docs/TEST_MATRIX.md. This is a working source milestone, not proven India–USA calling. Observe exact performed/unperformed verification, and re-run after edits.

## Non-negotiable guardrails

- Keep Firebase Spark and billing unlinked. Never “fix reliability” by silently adding TURN/Blaze/paid SDK/cloud functions.
- Do not claim serverless-literal, universal reachability or audited E2EE.
- No real project, account, credential, rules deployment or GitHub remote was created by this build task. The connected owner account is `devsenweb`; a new dedicated repository is required. Verify repo/privacy and authorization before pushing. Do not invent another destination.
- Never add admin/service-account or FCM-server credentials to an APK. No unrestricted or anonymous public signaling.
- Keep local media consent, call ID/revision guards, bounded timeouts, source ownership and default-deny rules. No recording or analytics.
- Do not state background ringing works. Both apps must remain foreground; `onStop` hangs up. Reliable incoming calls are a separate milestone.

## Suggested order of work

1. Build/test on the configured Android environment. Review dependency provenance/security and ABI/page-size support.
2. With owner authorization provision exact two private Firebase accounts/membership/rules on Spark. Keep config untracked. Run emulator tests first.
3. Install on two actual phones. Follow TEST_MATRIX: permissions, accept/cancel race, reject, repeated calls, call focus, camera releases, sign out/background, bidirectional media, cellular/Wi-Fi changes.
4. Fix device-observed failures, add executable regression tests for controller state (the current pure tests only cover policy; rules tests do not exercise Activity lifecycle). Separate controller from Activity to make richer race tests possible.
5. Only then evaluate background incoming calls with a secure, truly no-paid-usage trusted sender. Show the user tradeoffs before changing architecture.
6. Private GitHub source publication is pending creation/verification of a dedicated repository under `devsenweb`. Do not modify the existing `ai-news-aggregator-app` repository. No CI is enabled by default (avoid surprise metered Actions usage); run locally unless owner approves an explicitly no-cost CI route.

## Known limitations to preserve in reports

No physical call or real Firebase project test has occurred. NAT blocking may never be fixable with STUN alone. Signaling TTL is validation, not deletion. Temporary offlining can leave in-memory SDK writes and a stale record; call IDs/revisions/expiry constrain replay. Same-account multi-device calling unsupported. Bluetooth support is limited by Android version and untested. No trusted push sender, release signing key, Play listing, SSO/recovery UX or independent audit.
