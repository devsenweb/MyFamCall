package com.myfamcall.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Raw WebRTC; no vendor calling service, relay credentials, recordings, or media upload. */
public final class RtcEngine implements AutoCloseable {
    public interface Listener {
        void onLocalDescription(String type, String sdp);
        void onLocalIce(String mid, int line, String candidate);
        void onState(String state);
        void onRemoteAnswerApplied();
        void onWeakNetwork();
        void onError(String message);
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MediaCleanup cleanup = new MediaCleanup();
    private final EglBase egl = EglBase.create();
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private AudioSource audioSource;
    private AudioTrack audio;
    private VideoSource videoSource;
    private VideoTrack video;
    private VideoTrack remoteVideo;
    private RtpSender videoSender;
    private CameraVideoCapturer camera;
    private SurfaceTextureHelper cameraThread;
    private final SurfaceViewRenderer localView, remoteView;
    private boolean closed, capturing, remoteDescriptionSet, localInitialized, remoteInitialized, front = true;
    private final List<IceCandidate> pendingIce = new ArrayList<>();
    private int weakSamples, negotiations;
    private String statsPair="";
    private double sentBytes=-1,receivedBytes=-1;
    private final java.util.Set<String> localUfrags=new java.util.HashSet<>();
    private final List<IceCandidate> gatheredIce=new ArrayList<>();

    public RtcEngine(Context context, SurfaceViewRenderer local, SurfaceViewRenderer remote,
                     boolean videoEnabled, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        localView = local; remoteView = remote;
        try {
        local.init(egl.getEglBaseContext(), null); localInitialized=true;
        remote.init(egl.getEglBaseContext(), null); remoteInitialized=true;
        local.setMirror(true); local.setZOrderMediaOverlay(true);
        local.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        remote.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this.context)
                .setEnableInternalTracer(false).createInitializationOptions());
        JavaAudioDeviceModule adm = JavaAudioDeviceModule.builder(this.context)
                .setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true)
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    public void onWebRtcAudioRecordInitError(String error) { fail("Microphone unavailable."); }
                    public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode code, String error) { fail("Microphone could not start."); }
                    public void onWebRtcAudioRecordError(String error) { fail("Microphone interrupted."); }
                }).createAudioDeviceModule();
        try {
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm)
                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                    .createPeerConnectionFactory();
        } finally {
            // The factory retains its own reference. Release ours even when creation fails.
            adm.release();
        }
        // No TURN. These public STUN servers provide address discovery, not a media relay/SLA.
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.singletonList(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()));
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        peer = factory.createPeerConnection(config, new PeerConnection.Observer() {
            public void onSignalingChange(PeerConnection.SignalingState state) {}
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                if(state==PeerConnection.IceConnectionState.CONNECTED || state==PeerConnection.IceConnectionState.COMPLETED) post(() -> { if(peer!=null && peer.connectionState()==PeerConnection.PeerConnectionState.CONNECTED)listener.onState("CONNECTED"); });
            }
            public void onConnectionChange(PeerConnection.PeerConnectionState state) { post(() -> listener.onState(state.name())); }
            public void onIceConnectionReceivingChange(boolean receiving) {}
            public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
            public void onIceCandidate(IceCandidate c) { post(() -> {
                if(localUfrags.isEmpty()){if(gatheredIce.size()<128)gatheredIce.add(c);}
                else emitIce(c);
            }); }
            public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            public void onAddStream(MediaStream stream) {}
            public void onRemoveStream(MediaStream stream) {}
            public void onDataChannel(DataChannel channel) { channel.close(); }
            public void onRenegotiationNeeded() { /* Designated caller negotiates; never glare. */ }
            public void onTrack(RtpTransceiver transceiver) {
                MediaStreamTrack track = transceiver.getReceiver().track();
                if (track instanceof VideoTrack) post(() -> {
                    if (remoteVideo != null) remoteVideo.removeSink(remoteView);
                    remoteVideo = (VideoTrack) track; remoteVideo.addSink(remoteView);
                });
            }
        });
        if (peer == null) throw new IllegalStateException("WebRTC could not create a connection");
        audioSource = factory.createAudioSource(new MediaConstraints());
        audio = factory.createAudioTrack("family-audio", audioSource);
        RtpSender audioSender = peer.addTrack(audio, Collections.singletonList("family"));
        setBitrate(audioSender, 32_000, null);
        // Always negotiate a video track; audio-only never starts the camera. Toggle needs no new offer.
        videoSource = factory.createVideoSource(false);
        video = factory.createVideoTrack("family-video", videoSource);
        video.setEnabled(false); video.addSink(localView);
        videoSender = peer.addTrack(video, Collections.singletonList("family"));
        setBitrate(videoSender, 300_000, 15);
        if (videoEnabled) setVideoEnabled(true);
        main.postDelayed(stats, 5_000);
        } catch (RuntimeException | Error error) { close(); throw error; }
    }
    private void post(Runnable runnable) { main.post(() -> { if (!closed) runnable.run(); }); }
    private void fail(String message) { post(() -> listener.onError(message)); }
    private SdpObserver observer(Runnable success) {
        return new SdpObserver() {
            public void onCreateSuccess(SessionDescription sdp) {}
            public void onSetSuccess() { post(success); }
            public void onCreateFailure(String error) { fail("Session negotiation failed."); }
            public void onSetFailure(String error) { fail("Session description rejected."); }
        };
    }
    private SdpObserver created() {
        return new SdpObserver() {
            public void onCreateSuccess(SessionDescription sdp) { post(() ->
                    peer.setLocalDescription(observer(() -> {
                        localUfrags.clear();localUfrags.addAll(IceGeneration.ufrags(sdp.description));
                        listener.onLocalDescription(sdp.type.canonicalForm(), sdp.description);
                        for(IceCandidate candidate:gatheredIce)emitIce(candidate);gatheredIce.clear();
                    }), sdp)); }
            public void onSetSuccess() {}
            public void onCreateFailure(String error) { fail("Could not negotiate the call."); }
            public void onSetFailure(String error) { fail("Could not set session description."); }
        };
    }
    public void createOffer(boolean restart) {
        if (closed) return;
        remoteDescriptionSet = false; negotiations++; localUfrags.clear(); gatheredIce.clear();
        MediaConstraints constraints = new MediaConstraints();
        if (restart) constraints.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
        peer.createOffer(created(), constraints);
    }
    public void acceptOffer(String sdp) {
        if (closed) return;
        remoteDescriptionSet = false; negotiations++; localUfrags.clear(); gatheredIce.clear();
        peer.setRemoteDescription(observer(() -> {
            remoteDescriptionSet = true; flushIce();
            peer.createAnswer(created(), new MediaConstraints());
        }), new SessionDescription(SessionDescription.Type.OFFER, sdp));
    }
    public void acceptAnswer(String sdp) {
        if (closed) return;
        peer.setRemoteDescription(observer(() -> { remoteDescriptionSet = true; flushIce(); listener.onRemoteAnswerApplied(); }),
                new SessionDescription(SessionDescription.Type.ANSWER, sdp));
    }
    public void addRemoteIce(String mid, int line, String candidate) {
        if (closed) return;
        IceCandidate ice = new IceCandidate(mid, line, candidate);
        if (remoteDescriptionSet) peer.addIceCandidate(ice);
        else if (pendingIce.size() < 128) pendingIce.add(ice);
    }
    private void emitIce(IceCandidate ice){
        if(IceGeneration.matches(ice.sdp,localUfrags,negotiations==1))listener.onLocalIce(ice.sdpMid,ice.sdpMLineIndex,ice.sdp);
    }
    private void flushIce() { for (IceCandidate ice : pendingIce) peer.addIceCandidate(ice); pendingIce.clear(); }
    public void setMuted(boolean muted) { if (!closed) audio.setEnabled(!muted); }
    public boolean setVideoEnabled(boolean enabled) {
        if (closed) return false;
        try {
            // Stop sending first: a slow or failed hardware stop must not keep video enabled.
            if (!enabled) video.setEnabled(false);
            if (enabled && camera == null) {
                CameraEnumerator cameras = Camera2Enumerator.isSupported(context) ? new Camera2Enumerator(context) : new Camera1Enumerator(true);
                String chosen = null;
                for (String name : cameras.getDeviceNames()) {
                    if (chosen == null || cameras.isFrontFacing(name)) chosen = name;
                    if (cameras.isFrontFacing(name)) break;
                }
                if (chosen == null) { fail("No camera found. Use audio-only."); return false; }
                front = cameras.isFrontFacing(chosen); localView.setMirror(front);
                camera = cameras.createCapturer(chosen, new CameraVideoCapturer.CameraEventsHandler() {
                    public void onCameraError(String error) { fail("Camera unavailable. Restart as an audio call."); }
                    public void onCameraDisconnected() { fail("Camera disconnected."); }
                    public void onCameraFreezed(String error) { fail("Camera froze. Restart as an audio call."); }
                    public void onCameraOpening(String cameraName) {}
                    public void onFirstFrameAvailable() {}
                    public void onCameraClosed() {}
                });
                if (camera == null) { fail("Camera could not open."); return false; }
                cameraThread = SurfaceTextureHelper.create("FamilyCamera", egl.getEglBaseContext());
                camera.initialize(cameraThread, context, videoSource.getCapturerObserver());
            }
            if (enabled && !capturing) { camera.startCapture(640, 360, 15); capturing = true; }
            if (!enabled && capturing) { camera.stopCapture(); capturing = false; localView.clearImage(); }
            video.setEnabled(enabled); return true;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); fail("Camera stopped unexpectedly."); }
        catch (RuntimeException e) { fail("Camera permission or hardware unavailable."); }
        return false;
    }
    public void switchCamera() {
        if (!closed && camera != null && capturing) camera.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            public void onCameraSwitchDone(boolean isFront) { post(() -> { front = isFront; localView.setMirror(front); }); }
            public void onCameraSwitchError(String error) { fail("Could not switch camera."); }
        });
    }
    private void setBitrate(RtpSender sender, int bits, Integer fps) {
        if (sender == null) return;
        RtpParameters params = sender.getParameters();
        for (RtpParameters.Encoding encoding : params.encodings) { encoding.maxBitrateBps = bits; if (fps != null) encoding.maxFramerate = fps; }
        sender.setParameters(params);
    }
    private final Runnable stats = new Runnable() {
        public void run() {
            if (closed) return;
            peer.getStats(report -> {
                boolean weak = false, traffic = false;
                for (RTCStats stat : report.getStatsMap().values()) {
                    if (!"candidate-pair".equals(stat.getType())) continue;
                    Map<String,Object> m = stat.getMembers();
                    if (!"succeeded".equals(m.get("state")) || !Boolean.TRUE.equals(m.get("nominated"))) continue;
                    weak |= CallPolicy.weakNetwork(number(m.get("currentRoundTripTime")), number(m.get("availableOutgoingBitrate")));
                    double sent=number(m.get("bytesSent")), received=number(m.get("bytesReceived"));
                    traffic |= stat.getId().equals(statsPair) && sentBytes>=0 && sent>sentBytes && received>receivedBytes;
                    statsPair=stat.getId();sentBytes=sent;receivedBytes=received;
                }
                final boolean poor = weak, healthyTraffic=traffic;
                post(() -> {
                    if(healthyTraffic && peer.connectionState()==PeerConnection.PeerConnectionState.CONNECTED)listener.onState("CONNECTED");
                    weakSamples = poor ? weakSamples + 1 : 0;
                    if (weakSamples >= 2) { setBitrate(videoSender, 120_000, 10); listener.onWeakNetwork(); weakSamples = 0; }
                });
            });
            main.postDelayed(this, 5_000);
        }
    };
    private static double number(Object value) { return value instanceof Number ? ((Number)value).doubleValue() : 0; }
    public void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        // Separate each release: one faulty camera/driver must not keep the microphone alive,
        // prevent PeerConnection disposal, or leave an EGL/capture thread behind.
        List<Exception> failures = cleanup.releaseAll(
                () -> { if (audio != null) audio.setEnabled(false); },
                () -> { if (video != null) video.setEnabled(false); },
                () -> {
                    if (camera != null && capturing) {
                        try { camera.stopCapture(); } finally { capturing = false; }
                    }
                },
                () -> { if (camera != null) camera.dispose(); },
                () -> { if (remoteVideo != null) remoteVideo.removeSink(remoteView); },
                () -> { if (video != null) video.removeSink(localView); },
                () -> { if (peer != null) peer.close(); },
                () -> { if (peer != null) peer.dispose(); },
                () -> { if (audio != null) audio.dispose(); },
                () -> { if (video != null) video.dispose(); },
                () -> { if (audioSource != null) audioSource.dispose(); },
                () -> { if (videoSource != null) videoSource.dispose(); },
                () -> { if (cameraThread != null) cameraThread.dispose(); },
                () -> { if (factory != null) factory.dispose(); },
                () -> { if (localInitialized) localView.release(); },
                () -> { if (remoteInitialized) remoteView.release(); },
                () -> egl.release()
        );
        pendingIce.clear(); gatheredIce.clear(); localUfrags.clear();
        for (Exception failure : failures) {
            // Never log signaling, addresses, microphone data, frames, or exception messages.
            Log.w("MyFamCall", "Media cleanup failure: " + failure.getClass().getSimpleName());
        }
    }
}
