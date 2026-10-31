# Architecture and decision record

Status: incremental foreground prototype. Decision date: 2026-10-31.

```
Android A ───── Firebase Auth + RTDB (Spark): membership, SDP, ICE, call state ───── Android B
Android A ═════════════ WebRTC DTLS-SRTP peer media, when reachable ═════════════ Android B
       └──────── public STUN address discovery, not media relay ────────────────┘
```

## Decisions

1. **Android first, two people only.** Both family members have access to Android. Native Java and platform widgets keep the first milestone small and readable. Minimum API 26, target/compile 35. This is not an iOS or group-calling architecture commitment.
2. **Zero paid usage outranks reachability.** No TURN entry or automatic hosted fallback. The correct result when direct connectivity fails is an actionable failure, not an undisclosed charge. A universally reliable calling service cannot be promised under this restriction.
3. **Firebase Spark as managed signaling, not media.** Two manually provisioned authenticated identities; server-owned exact membership; singleton transactional call prevents simultaneous rooms/glare. See `SIGNALING.md` for rule-enforced role and schema ownership. Browser/web alternatives were not selected because an installed Android APK is requested.
4. **No trusted push sender invented.** Client-only DB listeners cannot wake a killed app reliably. No cloud function is deployed, and no FCM administrative credential is placed in the APK. Background ringing requires a separately approved zero-cost trusted sender design and Android lifecycle implementation.
5. **Explicit local media consent.** No camera/microphone starts while merely ringing. The recipient must locally choose Answer. A server state alone does not authorize recipient capture. Each new Activity lifetime requires fresh call intent.
6. **Initiator always owns offers.** Revision starts at 1 and is bounded at 32. Network changes/disconnect request an ICE restart; callee requests caller renegotiation rather than producing competing offers. SDP must be published before queued local ICE is sent. Remote candidates are buffered until compatible SDP is applied. Local candidates are matched against SDP ICE username fragments; ambiguous legacy candidates without a fragment are accepted only in the first negotiation, never relabeled during a restart. Call ID and revision reject stale generations server-side.
7. **Bounded failure.** Ring 45s, initial connection 30s, recovery20s, session just under2h, restart cooldown5s. These are conservative prototype policies, not measured India–USA tuning. A session reaching its cap requires a new call.
8. **Foreground privacy over unfinished background service.** `onStop` closes capture and best-effort terminates signaling. Configuration changes handled in the Activity avoid rotating into a new call. Process death and offline end can leave signaling until stale; expiry/replacement is not guaranteed immediate deletion.
9. **Weak links prefer audio.** Start 640×360 at15fps and cap outgoing video300kbps/audio32kbps. Two successive weak selected-candidate estimates (RTT>0.8s or outgoing bandwidth<180kbps) disable local camera; no automatic camera re-enable. WebRTC retains its own bandwidth/congestion control.

## Risks / next decisions

- Public STUN is best effort and exposes source IP; no server SLA.
- Handover can fail because signaling and media lose connectivity together. Real carriers/NATs need device testing; successful SDP is not evidence of recovered media.
- Two signed-in devices using the same account are not a supported use case. No multi-device presence arbitration exists.
- One latest call is stored; there is no history UI, scheduled deletion, or server-side TTL janitor. Bounded records limit accumulation but do not replace a retention policy.
- Firebase Spark quota/plan checks are operational, not enforced by APK. Do not attach billing.
- Dependency versions are pinned to known published compatible versions, not claimed newest. Native WebRTC supply-chain/security updates and 16KB Android page-size compatibility must be reviewed before distribution to newer devices/Play.
