# Security and privacy boundary

This is not audited, independently pentested, or an authenticated end-to-end-encryption product claim.

## Media and metadata

- WebRTC uses encrypted transport (DTLS-SRTP) between the negotiated peers. No app recording, media upload, transcription or analytics is implemented.
- There is no out-of-band fingerprint/safety-number verification or app-owned key authentication. A compromised Firebase project owner, signaling service, account or endpoint can affect the peer/SDP. Do not describe this as audited E2EE.
- Firebase sees account identities, timing, SDP and ICE metadata (which can expose IP addresses/network details). The media is not deliberately stored in Firebase. Public STUN sees network addresses. Peers learn each other's connectivity addresses.
- The last signaling record remains until replaced or owner-deleted. Expiration blocks use/reuse; it is not secure deletion. Firebase rules permit either current family member to replace expired/ended calls, but not arbitrary writes or membership changes.

## Access control

- Default deny at the root. Only the exact configured family members may read its members/call paths. No parent listing, anonymous rooms, public discovery, contact sync or self-enrollment.
- Account creation is absent in the UI; credentials are entered at sign-in and not written to source/logs by this app. Firebase SDK manages its usual authentication session in app-private storage. Android backup is disabled.
- Firebase Auth email/password can expose a public signup endpoint even if the UI has none. Unapproved Auth accounts must never gain RTDB access. API keys/family IDs are not secrets. Project API restrictions/App Check/abuse controls need separate evaluation; App Check is not included and is not a replacement for authentication/rules.
- Rules constrain immutable participants, live state transitions, source-owned SDP/ICE, bounded payloads/revision/candidate slots and lifetime. See the emulator tests and `SIGNALING.md`; tests cover rules logic, not all Android client races.
- The Firebase/Google owner account remains privileged. Protect it with MFA, review access, and keep billing unlinked. Never commit admin/service-account material. Revoke a lost phone's session/password and remove its membership before re-provisioning.

## Device behavior

The camera/mic are permission-gated and start only for an accepted call with local intent. A recipient may answer audio-only. Camera-off stops capture rather than merely hiding preview. Leaving the foreground closes media. Device/OEM behavior and permission interruptions still require the physical test matrix. Native dependency bugs, compromised phones, or another endpoint recording externally cannot be prevented by this app.

## Hard release gates

Do not call this production-ready until: independent security review; real-device lifecycle/media checks; tested cross-network handover; supported device/ABI/page-size check; signed release key management; verified private Firebase provisioning; and a tested operational no-billing/quota process. If background ringing is added, a trusted sender and notification/call permission model must be designed without embedding secrets or introducing paid usage.
