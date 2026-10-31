package com.myfamcall.app;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Authenticated, bounded signaling only. Never sends or records media. */
public final class SignalClient implements AutoCloseable {
    public interface Listener {
        void onCall(Call call);
        void onOffer(int revision, String sdp);
        void onAnswer(int revision, String sdp);
        void onCandidate(int revision, String mid, int line, String sdp);
        void onRestartRequested(int revision);
        void onError(String message);
    }
    public interface Completion { void complete(Call call, String error); }
    public interface PeerCompletion { void complete(String peerUid, String error); }
    public static final class Call {
        public final String id, caller, callee, state;
        public final long createdAt, expiresAt;
        public final boolean video;
        private Call(DataSnapshot s) {
            id = str(s.child("id")); caller = str(s.child("caller"));
            callee = str(s.child("callee")); state = str(s.child("status/value"));
            createdAt = number(s.child("createdAt")); expiresAt = number(s.child("expiresAt"));
            video = Boolean.TRUE.equals(s.child("video").getValue(Boolean.class));
        }
        public boolean isCaller(String uid) { return caller.equals(uid); }
        public boolean isLive(long now) {
            return expiresAt > now && (state.equals("accepted") ||
                    (state.equals("ringing") && createdAt + 45_000 > now));
        }
    }

    private final String uid;
    private final Listener listener;
    private final DatabaseReference family, callRef, connectedRef, offsetRef;
    private boolean connected, closed, listening;
    private long serverOffset;
    private Call current;
    private String observedId = "";
    private int deliveredOffer, deliveredAnswer, deliveredRestart;
    private int outgoingRevision, outgoingSlot, requestedRevision;
    private final Set<String> deliveredCandidates = new HashSet<>();
    private final ValueEventListener connectedListener = new ValueEventListener() {
        @Override public void onDataChange(DataSnapshot s) { connected = Boolean.TRUE.equals(s.getValue(Boolean.class)); }
        @Override public void onCancelled(DatabaseError e) { connected = false; }
    };
    private final ValueEventListener offsetListener = new ValueEventListener() {
        @Override public void onDataChange(DataSnapshot s) { serverOffset = number(s); }
        @Override public void onCancelled(DatabaseError e) { serverOffset = 0; }
    };
    private final ValueEventListener callListener = new ValueEventListener() {
        @Override public void onDataChange(DataSnapshot s) { consume(s); }
        @Override public void onCancelled(DatabaseError e) { if (!closed) listener.onError("Family signaling access denied or unavailable: " + e.getMessage()); }
    };

    public SignalClient(FirebaseDatabase db, String familyId, String ownUid, Listener listener) {
        if (familyId == null || !familyId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid family ID");
        this.uid = ownUid; this.listener = listener;
        family = db.getReference("families").child(familyId);
        callRef = family.child("call");
        connectedRef = db.getReference(".info/connected");
        offsetRef = db.getReference(".info/serverTimeOffset");
        connectedRef.addValueEventListener(connectedListener);
        offsetRef.addValueEventListener(offsetListener);
        // Do not enable disk persistence: old signaling must not replay after app restart.
    }

    public void listen() {
        if (!closed && !listening) { listening = true; callRef.addValueEventListener(callListener); }
    }
    public boolean isConnected() { return connected && !closed; }
    public long serverNow() { return System.currentTimeMillis() + serverOffset; }

    /** Membership is administrator-owned. A third member makes this two-person client refuse calls. */
    public void loadPeer(PeerCompletion completion) {
        family.child("members").get().addOnCompleteListener(task -> {
            if (closed) return;
            if (!task.isSuccessful()) { completion.complete(null, "Unable to read the private family allowlist."); return; }
            DataSnapshot members = task.getResult();
            if (members.getChildrenCount() != 2 || !Boolean.TRUE.equals(members.child(uid).getValue())) {
                completion.complete(null, "Exactly two administrator-approved family accounts are required."); return;
            }
            String peer = null;
            for (DataSnapshot m : members.getChildren()) {
                if (!Boolean.TRUE.equals(m.getValue())) { completion.complete(null, "Invalid family membership configuration."); return; }
                if (!uid.equals(m.getKey())) peer = m.getKey();
            }
            completion.complete(peer, peer == null ? "Family partner is missing." : null);
        });
    }

    public void begin(String peerUid, boolean video, Completion completion) {
        if (!ready(completion)) return;
        final String id = UUID.randomUUID().toString();
        final Map<String, Object> fresh = new HashMap<>();
        fresh.put("id", id); fresh.put("caller", uid); fresh.put("callee", peerUid);
        fresh.put("createdAt", ServerValue.TIMESTAMP);
        // Ten-second margin tolerates the small delay before the server evaluates its clock.
        fresh.put("expiresAt", serverNow() + 7_190_000L);
        fresh.put("video", video); fresh.put("status", status(id, "ringing"));
        callRef.runTransaction(new Transaction.Handler() {
            @Override public Transaction.Result doTransaction(MutableData data) {
                if (closed || !connected) return Transaction.abort();
                if (data.getValue() != null) {
                    String state = valueString(data.child("status/value").getValue());
                    long expiry = valueNumber(data.child("expiresAt").getValue());
                    long created = valueNumber(data.child("createdAt").getValue());
                    if (!state.equals("ended") && expiry > serverNow() &&
                            !(state.equals("ringing") && created + 45_000 <= serverNow())) return Transaction.abort();
                }
                data.setValue(fresh); return Transaction.success(data);
            }
            @Override public void onComplete(DatabaseError error, boolean committed, DataSnapshot snapshot) {
                if (closed) {
                    if (error == null && committed && snapshot != null) finishAbandonedClaim(str(snapshot.child("id")));
                    return;
                }
                if (error != null) completion.complete(null, error.getMessage());
                else if (!committed) completion.complete(null, "A call is already active, or signaling is offline.");
                else completion.complete(new Call(snapshot), null);
            }
        }, false);
    }
    // Closing while a claim commits must not leave the other phone ringing indefinitely.
    // This best-effort transaction remains call-ID-bound even if delivered after reconnection.
    private void finishAbandonedClaim(String callId) {
        if (callId.isEmpty()) return;
        callRef.child("status").runTransaction(new Transaction.Handler() {
            @Override public Transaction.Result doTransaction(MutableData data) {
                if (!callId.equals(valueString(data.child("callId").getValue())) ||
                        "ended".equals(valueString(data.child("value").getValue()))) return Transaction.abort();
                data.setValue(status(callId, "ended")); return Transaction.success(data);
            }
            @Override public void onComplete(DatabaseError error, boolean committed, DataSnapshot snapshot) {
                // No retry loop or media restart. Server expiry remains the final fallback.
            }
        }, false);
    }

    public void accept(String callId, Completion completion) { setStatus(callId, "accepted", completion); }
    public void end(String callId, Completion completion) { setStatus(callId, "ended", completion); }

    private void setStatus(String callId, String state, Completion completion) {
        if (!ready(completion)) return;
        callRef.child("status").runTransaction(new Transaction.Handler() {
            @Override public Transaction.Result doTransaction(MutableData data) {
                // Once an end was requested while online, keep its ID-bound cleanup valid
                // across close()/a network handover. Never keep a late acceptance alive.
                if ((state.equals("accepted") && (closed || !connected)) ||
                        !callId.equals(valueString(data.child("callId").getValue()))) return Transaction.abort();
                String old = valueString(data.child("value").getValue());
                if (state.equals(old) || old.equals("ended") ||
                        (state.equals("accepted") && !old.equals("ringing"))) return Transaction.abort();
                data.setValue(status(callId, state)); return Transaction.success(data);
            }
            @Override public void onComplete(DatabaseError error, boolean committed, DataSnapshot snapshot) {
                if (closed) return;
                boolean same = snapshot != null && callId.equals(str(snapshot.child("callId"))) && state.equals(str(snapshot.child("value")));
                completion.complete(current, error != null ? error.getMessage() : (committed || same ? null : "Call changed before the action completed."));
            }
        }, false);
    }

    public void offer(String callId, int revision, String sdp, Completion completion) {
        putSdp("offer", callId, revision, sdp, completion);
    }
    public void answer(String callId, int revision, String sdp, Completion completion) {
        putSdp("answer", callId, revision, sdp, completion);
    }
    private void putSdp(String role, String callId, int revision, String sdp, Completion completion) {
        if (!ready(completion)) return;
        Map<String, Object> value = envelope(callId, revision); value.put("sdp", sdp);
        callRef.child(role).setValue(value).addOnCompleteListener(task -> {
            if (!closed) completion.complete(current, task.isSuccessful() ? null : "Unable to publish " + role + ".");
        });
    }
    public void candidate(String callId, int revision, String mid, int line, String sdp) {
        if (current == null || !callId.equals(current.id) || !current.state.equals("accepted") || revision < outgoingRevision) return;
        if (!isConnected()) { if (!closed) listener.onError("Signaling is offline; candidate was not sent."); return; }
        if (revision != outgoingRevision) { outgoingRevision = revision; outgoingSlot = 0; }
        if (outgoingSlot >= 64) { listener.onError("ICE candidate limit reached; try a new call."); return; }
        Map<String, Object> value = envelope(callId, revision);
        value.put("mid", mid == null ? "" : mid); value.put("line", line); value.put("sdp", sdp);
        String slot = String.format(Locale.ROOT, "c%02d", outgoingSlot++);
        callRef.child("ice").child(uid).child(slot).setValue(value).addOnFailureListener(e -> {
            if (!closed) listener.onError("An ICE candidate was rejected or signaling is unavailable.");
        });
    }
    public void requestRestart(String callId, int revision) {
        if (!isConnected() || current == null || !callId.equals(current.id) || revision <= requestedRevision) return;
        requestedRevision = revision;
        callRef.child("restartRequested").setValue(envelope(callId, revision)).addOnFailureListener(e -> {
            if (requestedRevision == revision) requestedRevision = 0;
            if (!closed) listener.onError("Unable to request network recovery.");
        });
    }

    private void consume(DataSnapshot s) {
        if (closed) return;
        if (!s.exists()) { current = null; listener.onCall(null); return; }
        Call call = new Call(s);
        if (!call.id.equals(observedId)) {
            observedId = call.id; deliveredOffer = deliveredAnswer = deliveredRestart = 0;
            outgoingRevision = outgoingSlot = requestedRevision = 0; deliveredCandidates.clear();
        }
        current = call;
        listener.onCall(call); // Controller must see accepted/new identity before any SDP/ICE event.
        if (closed || !call.state.equals("accepted") || !call.isLive(serverNow())) return;
        boolean caller = call.isCaller(uid);
        int revision = (int) number(s.child("offer/revision"));
        if (!caller && revision > deliveredOffer) {
            deliveredOffer = revision; listener.onOffer(revision, str(s.child("offer/sdp")));
        }
        int answerRevision = (int) number(s.child("answer/revision"));
        if (caller && answerRevision == revision && answerRevision > deliveredAnswer) {
            deliveredAnswer = answerRevision; listener.onAnswer(answerRevision, str(s.child("answer/sdp")));
        }
        int restart = (int) number(s.child("restartRequested/revision"));
        if (caller && restart > deliveredRestart) {
            deliveredRestart = restart; listener.onRestartRequested(restart);
        }
        String peer = caller ? call.callee : call.caller;
        for (DataSnapshot ice : s.child("ice").child(peer).getChildren()) {
            int candidateRevision = (int) number(ice.child("revision"));
            String key = candidateRevision + ":" + ice.getKey();
            if (candidateRevision == revision && deliveredCandidates.add(key)) {
                listener.onCandidate(candidateRevision, str(ice.child("mid")), (int) number(ice.child("line")), str(ice.child("sdp")));
            }
        }
    }
    private boolean ready(Completion completion) {
        if (!isConnected()) { completion.complete(null, "Signaling is offline. Reconnect before trying again."); return false; }
        return true;
    }
    private static Map<String, Object> status(String callId, String value) {
        Map<String, Object> m = new HashMap<>(); m.put("callId", callId); m.put("value", value); return m;
    }
    private static Map<String, Object> envelope(String callId, int revision) {
        Map<String, Object> m = new HashMap<>(); m.put("callId", callId); m.put("revision", revision); return m;
    }
    private static String str(DataSnapshot s) { return valueString(s.getValue()); }
    private static String valueString(Object v) { return v instanceof String ? (String) v : ""; }
    private static long number(DataSnapshot s) { return valueNumber(s.getValue()); }
    private static long valueNumber(Object v) { return v instanceof Number ? ((Number) v).longValue() : 0L; }
    @Override public void close() {
        closed = true; connected = false;
        callRef.removeEventListener(callListener); connectedRef.removeEventListener(connectedListener);
        offsetRef.removeEventListener(offsetListener); deliveredCandidates.clear();
    }
}
