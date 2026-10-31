package com.myfamcall.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/** Foreground communication audio, restoring prior mode/focus on hangup. */
public final class AudioRoute implements AutoCloseable {
    private final AudioManager manager;
    private final int previousMode;
    private final boolean previousSpeaker;
    private final AudioFocusRequest focus;
    private boolean closed;
    private List<AudioDeviceInfo> offeredDevices = new ArrayList<>();
    public AudioRoute(Context context, Runnable interruption) {
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        previousMode = manager.getMode(); previousSpeaker = manager.isSpeakerphoneOn();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change -> {
                    if (!closed && (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) interruption.run();
                }).build();
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IllegalStateException("Another app is using call audio.");
        manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        manager.setSpeakerphoneOn(false);
    }
    public String[] labels() {
        if (Build.VERSION.SDK_INT >= 31) {
            List<String> labels = new ArrayList<>();
            offeredDevices = new ArrayList<>(manager.getAvailableCommunicationDevices());
            for (AudioDeviceInfo device : offeredDevices) labels.add(label(device));
            return labels.toArray(new String[0]);
        }
        return new String[]{"Phone / wired headset", "Speaker"};
    }
    public boolean select(int index) {
        if (closed) return false;
        if (Build.VERSION.SDK_INT >= 31) {
            List<AudioDeviceInfo> devices = offeredDevices;
            return index >= 0 && index < devices.size() && manager.setCommunicationDevice(devices.get(index));
        }
        manager.setSpeakerphoneOn(index == 1); return true;
    }
    private String label(AudioDeviceInfo device) {
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "Phone earpiece";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Speaker";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired headset";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth headset";
            default: return device.getProductName().toString();
        }
    }
    public void close() {
        if (closed) return; closed = true;
        if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice();
        manager.setSpeakerphoneOn(previousSpeaker); manager.setMode(previousMode); manager.abandonAudioFocusRequest(focus);
    }
}
