package com.myfamcall.app;

/** Pure policy: deterministic and executable without Android/Firebase. */
public final class CallPolicy {
    public static final long RING_MS = 45_000;
    public static final long CONNECT_MS = 30_000;
    public static final long RECOVER_MS = 20_000;
    public static final long RESTART_COOLDOWN_MS = 5_000;
    public static final int MAX_REVISIONS = 32;
    private CallPolicy() {}
    public static boolean incomingFresh(long serverNow, long createdAt, long expiresAt) {
        return serverNow >= createdAt - 5_000 && serverNow < createdAt + RING_MS && serverNow < expiresAt;
    }
    public static boolean shouldRestart(boolean accepted, boolean negotiating, int revision,
                                        long elapsedNow, long lastRestart) {
        return accepted && !negotiating && revision > 0 && revision < MAX_REVISIONS
                && elapsedNow - lastRestart >= RESTART_COOLDOWN_MS;
    }
    public static boolean validRemoteRevision(int incoming, int current, boolean offer) {
        return incoming > 0 && incoming <= MAX_REVISIONS && (offer ? incoming > current : incoming == current);
    }
    public static boolean weakNetwork(double rttSeconds, double availableOutgoingBitrate) {
        return rttSeconds > 0.8 || (availableOutgoingBitrate > 0 && availableOutgoingBitrate < 180_000);
    }
}
