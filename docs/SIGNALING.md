# Private signaling protocol

Status: implemented prototype; local security-rule tests pass. Android runtime, authentication against a real project, and two-device call behavior must still be verified. This document describes the protocol, not a production reliability guarantee.

## Identity and zero-cost boundary

Use a **Firebase Spark** project with **no billing account attached**, email/password Firebase Authentication, and one Realtime Database. Do not upgrade to Blaze, enable phone/SMS authentication, deploy Cloud Functions, attach a paid relay, or create any metered fallback. No Firebase project was created or deployed by this implementation.

The owner creates the two Auth accounts in the Firebase console and manually provisions exactly two UID entries under `families/<familyId>/members`, each `true`. Passwords never belong in the repository or configuration JSON. The app signs in but has no signup UI. This is not a claim that Firebase's public signup API is disabled: with email/password auth enabled, outsiders may be able to create unprivileged Auth accounts, but the database allowlist still rejects them. Authentication and signaling quotas remain possible denial-of-service targets. With no billing account and no paid services, exhaustion must stop functionality, not trigger a paid fallback.

`SignalClient.loadPeer()` refuses malformed membership or anything other than exactly two entries. The database rules require both call participants to be administrator-approved members and prevent clients from adding members. Exactly-two membership is an administrator provisioning requirement as well as a client check; the rules do not count arbitrary admin-added members. Do not add a third member to this two-person prototype.

Example template: `signaling/family.seed.template.json`. Replace the family ID and both UID placeholders manually in the console. Never import it over unrelated data. A Firebase API key is application configuration, not a substitute for these security rules; do not commit real configuration nonetheless.

## Data shape

```
families/<familyId>/
  members/<uid>: true                         # admin-owned
  call/
    id: UUID                                 # fresh for each attempt
    caller: UID
    callee: UID
    createdAt: server timestamp
    expiresAt: client estimate of server time, capped by rules
    video: boolean                           # initial requested mode, not camera consent
    status: { callId, value }                 # ringing | accepted | ended
    offer: { callId, revision, sdp }           # caller only
    answer: { callId, revision, sdp }          # callee only
    restartRequested: { callId, revision }    # callee only
    ice/<senderUid>/c00..c63:
      { callId, revision, mid, line, sdp }
```

There is exactly one singleton call location per family, not public room discovery. Reads at `/`, `/families`, and the family parent are denied, even to members. Clients may read only their authorized family's members and call subtree. Anonymous and other authenticated identities are denied.

## Ownership and races

* A transaction at `call` can create a new call only if the old one is absent, ended, expired, or an unanswered ring is at least 45 seconds old. The server is the authority; simultaneous calls have one winner.
* Caller, callee, initial video intent, call ID, creation time and expiration cannot be changed during a call. Initial creation cannot smuggle in SDP, ICE or extra fields.
* Only the callee can change a fresh ring to `accepted`. Either participant can change a ringing/accepted call to `ended`. Ended calls cannot be resurrected.
* Status transactions carry their call ID. A delayed cancel from one call cannot end the next singleton call. SDP, ICE and restart requests also contain the call ID and are rejected if stale.
* Media negotiation is allowed only after acceptance. Only the caller creates offers. Initial revision is 1; each further offer increments by exactly 1, up to 32. A new offer waits until the previous offer has a matching answer, avoiding simultaneous renegotiation/glare.
* The callee can ask for a restart using the current revision; the caller remains the sole offer writer. The controller should coalesce repeated network events and serialize restarts. An initial negotiation that never gets an answer must time out rather than spawn overlapping offers.
* ICE candidates are sender-owned. One sender cannot overwrite the other sender's branch. Each sender has at most 64 slots; slots may be reused only for a newer current offer revision. Candidates and SDP are never accepted after the call expires.

`SignalClient` emits `onCall` before SDP/candidate events, emits only peer SDP/ICE, and deduplicates revision/slot callbacks. Its controller must buffer remote ICE until remote SDP is applied, buffer local ICE until local SDP has been committed, and ignore callbacks from a closed media session. The controller owns the media connection timeout, ring timer, recovery timer and permissions; this layer cannot establish that media was received.

## Retention and bounds

* Ring acceptance window: 45 seconds, enforced by rules.
* Lifetime: at most 2 hours, enforced by rules. The client chooses 1 hour 59 minutes 50 seconds to leave a clock/transport margin. The app should end media at expiration.
* SDP: at most 65,536 characters each for the latest offer and answer.
* ICE: at most 64 slots per participant, each candidate SDP at most 2,048 characters. Old slot contents may remain until overwritten; listeners ignore older revisions.
* At most 32 offer revisions per call. Once the limit is reached, end and create a fresh call.
* Unknown fields and client deletion of call records/fields are denied.

Expiry is an authorization/replacement deadline, **not automatic deletion**. No paid or background server cleanup exists. The singleton retains bounded signaling metadata until the next valid call replaces it, or the owner deletes it in the console. A crashed accepted call can remain busy until a participant explicitly ends it or it expires. A fresh process should not silently resume microphone/camera use from database state.

## Offline behavior and limits

Disk persistence is not enabled. `.info/connected` gates new writes and server time offset is used for expiration estimates. This reduces accidental offline signaling but cannot atomically prevent Firebase SDK in-memory write queueing if the connection disappears immediately after the guard. Server-enforced call IDs, revisions, state and expiry reject stale generations; app timeout/teardown still must stop media locally. Do not claim an absolute offline-queue prohibition.

There is deliberately no `onDisconnect` forced hangup: momentary signaling disconnects are expected during Wi-Fi/cellular handover, and forcing hangup would defeat recovery. A signaling outage cannot be repaired by a paid service. WebRTC connection/ICE behavior and user feedback are handled by the app, and direct-connect failure is a valid outcome without TURN.

Rules cannot provide a general per-minute request quota or protect against an authorized compromised family account exhausting free quota. A compromised administrator/project can alter membership or signaling. Revoke compromised accounts and review project access. This is a prototype access-control design, not a security audit.

## Privacy and encryption limits

Firebase receives account identifiers, call metadata, SDP and ICE. ICE/SDP can reveal network addresses and media capabilities. Firebase signaling is transported over TLS. WebRTC media uses its encrypted peer transport, but this app has no human-verifiable safety code, independent identity-key verification or audited application-level E2EE design. A trusted signaling service/project is part of the security model. Never call this an audited E2EE messenger. The signaling code does not record or upload microphone/video media.

## Local security tests

From `signaling/`:

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm test
```

The test command uses `demo-my-fam-call`; it does not deploy rules, create a real Firebase project, or log in. Java 21, Node/npm and a localhost port are required; the Firebase CLI downloads its free local Database Emulator on first run. Use a writable npm cache/HOME if necessary. In proxy-managed development environments, ensure `127.0.0.1` and `localhost` bypass the HTTP proxy; a proxy can otherwise produce `other side closed` while loading local rules.

Verification on 2026-10-31: **11 tests passed, 0 failed**, using Database Emulator v4.11.2, `@firebase/rules-unit-testing` 6.0.0, Firebase JS SDK 13.1.0 and Firebase CLI 15.34.2. Tests cover unauthorized access, membership writes, call races, cancellation, stale-generation rejection, role/revision ownership, ICE and SDP bounds, forbidden deletion, expiration, and maximum lifetime. Expected `permission_denied` warnings in output are assertions of successful rejection, not test failures. These tests do not replace real Android-device testing.

## Official references checked

* [Realtime Database rules API](https://firebase.google.com/docs/reference/security/database): rule variables, validation and inheritance.
* [Rules conditions and deletion caveats](https://firebase.google.com/docs/database/security/rules-conditions): non-cascading validation and skipped validation on deleted values; explicit write checks here prevent deletion loopholes.
* [Emulator rules testing](https://firebase.google.com/docs/rules/unit-tests): isolated authenticated/unauthenticated contexts and local rule tests.
* [Android MutableData API](https://firebase.google.com/docs/reference/android/com/google/firebase/database/MutableData): transaction implementation.
