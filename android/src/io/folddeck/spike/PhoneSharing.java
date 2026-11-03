package io.folddeck.spike;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.*;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Phone clipboard, microphone and camera for the laptop. Mic/camera availability is
 * switch + permission only, so they keep working while FoldDeck is hidden or locked
 * (SharingService keeps that legal); clipboard sync needs window focus. All state is
 * owned by the main thread.
 */
final class PhoneSharing {
    static final int PERMISSIONS = 81;
    private final Activity activity;
    private final SharedPreferences prefs;
    private final ClipboardManager clipboard;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardChanged = this::publishClipboard;
    private final HandlerThread cameraThread = new HandlerThread("phone-camera");
    private final Handler cameraHandler;
    private H264Stream stream;
    private boolean supported, focused, writingClipboard;
    private int wanted, ready, pendingPermission, serviceRunning;
    /** Laptop clipboard text that arrived without focus; applied on focus. */
    private String deferredText;
    private final CaptureGate gate = new CaptureGate();
    private int live;
    private final Runnable leaseCheck = new Runnable() {
        @Override public void run() {
            updateCapture();
            main.postDelayed(this, 250);
        }
    };
    private volatile AudioRecord microphone;   // recording now (read by the UI check)
    private volatile Thread micThread;         // non-null while the mic is wanted
    private volatile int cameraGeneration;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader images;
    private String lastText;
    String status = "Connect to your laptop to share phone inputs";
    Runnable changed = () -> {};

    PhoneSharing(Activity activity, SharedPreferences prefs) {
        this.activity = activity;
        this.prefs = prefs;
        wanted = prefs.getBoolean("share_clipboard", true) ? 1 : 0;
        if (prefs.getBoolean("share_microphone", true)) wanted |= 2;
        if (prefs.getBoolean("share_camera", true)) wanted |= 4;
        clipboard = activity.getSystemService(ClipboardManager.class);
        clipboard.addPrimaryClipChangedListener(clipboardChanged);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        main.post(leaseCheck);
    }

    boolean enabled(int bit) { return (wanted & bit) != 0; }
    int active() { return live | (ready & 1); }
    int available() { return ready; }

    static int allowedMask(int wanted, boolean mic, boolean camera) {
        return wanted & 7 & ~(mic ? 0 : 2) & ~(camera ? 0 : 4);
    }

    static int serviceTypes(int mask) {
        return ((mask & 2) != 0 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0)
                | ((mask & 4) != 0 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA : 0);
    }

    private int allowed() { return allowedMask(wanted, permitted(2), permitted(4)); }

    /** Worth keeping the connection while hidden: a phone mic/camera is offered. */
    boolean keepsConnection() { return stream != null && (allowed() & 6) != 0; }

    /**
     * Start, retype or stop SharingService. Starting needs a visible activity (mic and
     * camera are while-in-use permissions), so only foreground paths pass a non-zero
     * type set; stopping is allowed anywhere.
     */
    private void updateService() {
        int types = stream == null ? 0 : serviceTypes(allowed());
        if (types == serviceRunning) return;
        Intent intent = new Intent(activity, SharingService.class);
        try {
            if (types == 0) activity.stopService(intent);
            // startService, not startForegroundService: we are visible, so it is allowed, and a
            // refused startForeground() then just costs the background inputs instead of
            // crashing the app ("did not then call startForeground").
            else activity.startService(intent.putExtra(SharingService.TYPES, types));
            serviceRunning = types;
        } catch (RuntimeException e) {
            android.util.Log.w("FoldDeck/Share", "sharing service", e);
        }
    }

    private void save() {
        prefs.edit().putBoolean("share_clipboard", enabled(1))
                .putBoolean("share_microphone", enabled(2)).putBoolean("share_camera", enabled(4)).apply();
    }

    /** UI thread, activity visible: the one place a new stream starts the service. */
    void attach(H264Stream next) {
        reset();
        stream = next;
        next.setSharingListener(payload -> {
            long received = android.os.SystemClock.elapsedRealtime();
            main.post(() -> { if (stream == next) receive(payload, received); });
        });
        updateService();
    }

    void disconnect() {
        reset();
        updateService();
    }

    private void reset() {
        supported = false;
        stream = null;
        gate.configure(0);
        live = 0;
        ready = 0;
        lastText = null;
        deferredText = null;
        stopMic();
        stopCamera();
        status = "Connect to your laptop to share phone inputs";
        changed.run();
    }

    /**
     * Window focus gates only the clipboard (Android blocks background clipboard
     * reads). It must not reconfigure: that bumps the revision and would stop a mic
     * a laptop app is recording from.
     */
    void focus(boolean on) {
        focused = on;
        if (!on) return;
        if (deferredText != null) writeClipboard(deferredText);
        deferredText = null;
        publishClipboard();
        requestAllowedPermissions();
    }

    void toggle(int bit) {
        if ((wanted & bit) != 0) {
            wanted &= ~bit;
        } else {
            String permission = bit == 2 ? Manifest.permission.RECORD_AUDIO : Manifest.permission.CAMERA;
            if (bit != 1 && activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                pendingPermission = bit;
                activity.requestPermissions(new String[]{permission}, PERMISSIONS);
                return;
            }
            wanted |= bit;
        }
        save();
        configure();
        updateService();
    }

    void permissionResult() {
        int requested = pendingPermission;
        pendingPermission = 0;
        if (requested == 0) return;   // notifications only: nothing to reconfigure
        for (int bit : new int[]{2, 4}) if ((requested & bit) != 0) {
            if (permitted(bit)) wanted |= bit; else wanted &= ~bit;
        }
        save();
        main.post(this::configure);
        updateService();
    }

    private boolean permitted(int bit) {
        return activity.checkSelfPermission(bit == 2 ? Manifest.permission.RECORD_AUDIO : Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAllowedPermissions() {
        if (!supported || !focused || pendingPermission != 0) return;
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
        for (int bit : new int[]{2, 4}) if (enabled(bit) && !permitted(bit)) {
            pendingPermission |= bit;
            permissions.add(bit == 2 ? Manifest.permission.RECORD_AUDIO : Manifest.permission.CAMERA);
        }
        // Without it the service notification is hidden from the shade (it still runs).
        // Asked once: a denial must not come back on every focus.
        if ((wanted & 6) != 0 && !prefs.getBoolean("asked_notifications", false)
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("asked_notifications", true).apply();
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) activity.requestPermissions(permissions.toArray(new String[0]), PERMISSIONS);
    }

    private void configure() {
        int mask = allowed();
        gate.configure(mask);
        ready = live = 0;
        stopMic(); stopCamera();
        if (supported && stream != null) {
            stream.sendSharing(SharingProtocol.CONFIG, SharingProtocol.tagged(gate.revision, new byte[]{(byte) mask}));
            status = "Updating phone sharing…";
        }
        ready &= wanted;
        changed.run();
    }

    private void receive(byte[] payload, long received) {
        if (payload.length < 1) return;
        int kind = payload[0];
        if (kind == SharingProtocol.HELLO && payload.length == 2) {
            supported = payload[1] == 2;
            if (supported) { configure(); requestAllowedPermissions(); }
            else {
                gate.configure(0);
                ready = live = 0;
                stopMic(); stopCamera();
                status = "Sharing disconnected";
            }
        } else if (kind == SharingProtocol.READY && payload.length == 6) {
            gate.ready(SharingProtocol.revision(payload), payload[5]);
            ready = gate.ready;
            updateCapture();
            publishClipboard();
        } else if (kind == SharingProtocol.DEMAND && payload.length == 6) {
            gate.demand(SharingProtocol.revision(payload), payload[5], received);
            updateCapture();
        } else if (kind == SharingProtocol.STATUS) {
            status = new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
            android.widget.Toast.makeText(activity, status, android.widget.Toast.LENGTH_LONG).show();
        } else if (kind == SharingProtocol.CLIPBOARD && (ready & 1) != 0) {
            String text = new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
            if (focused) writeClipboard(text); else deferredText = text;
        }
        changed.run();
    }

    private void writeClipboard(String text) {
        if (text.equals(lastText)) return;
        writingClipboard = true;
        lastText = text;
        clipboard.setPrimaryClip(ClipData.newPlainText("Laptop", text));
        writingClipboard = false;
    }

    private void updateCapture() {
        int previous = live;
        String previousStatus = status;
        int next = supported ? gate.active(android.os.SystemClock.elapsedRealtime()) : 0;
        if (!permitted(2)) next &= ~2;
        if (!permitted(4)) next &= ~4;
        if ((next & 2) != 0) startMic(); else if (micThread != null) stopMic();
        if ((next & 4) != 0) startCamera(); else if (cameraStarting) stopCamera();
        live = next;
        if (supported) status = "Clipboard " + ((ready & 1) != 0 ? "synced" : "off")
                + " · Mic " + inputState(2) + " · Camera " + inputState(4);
        if (previous != live || !previousStatus.equals(status)) changed.run();
    }

    private String inputState(int bit) {
        return (live & bit) != 0 ? "live" : (ready & bit) != 0 ? "ready · idle" : enabled(bit) ? "waiting" : "disabled";
    }

    private void publishClipboard() {
        if (!focused || writingClipboard || (ready & 1) == 0 || stream == null) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) return;
        String text = clip.getItemAt(0).getText().toString();
        if (text.equals(lastText) || text.length() > SharingProtocol.MAX_TEXT) return;
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (data.length > SharingProtocol.MAX_TEXT) return;
        lastText = text;
        stream.sendSharing(SharingProtocol.CLIPBOARD, data);
    }

    private void fail(int bit, String message, int generation) {
        main.post(() -> {
            if (generation != (bit == 2 ? gate.revision : cameraGeneration)) return;
            wanted &= ~bit;
            configure();
            if ((allowed() & 6) == 0) updateService();   // only ever stops it from here
            status = message;
            changed.run();
        });
    }

    /**
     * Create and start the recorder on its own thread: DEMAND handling never blocks
     * the UI thread, and the first PCM leaves as soon as the HAL delivers it.
     */
    private void startMic() {
        if (micThread != null) return;
        int revision = gate.revision;
        H264Stream target = stream;
        long asked = android.os.SystemClock.elapsedRealtime();
        Thread[] self = new Thread[1];
        self[0] = new Thread(() -> {
            AudioRecord recorder = null;
            try {
                int min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 48000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 9600));
                long created = android.os.SystemClock.elapsedRealtime();
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone unavailable");
                if (micThread != self[0]) return;
                recorder.startRecording();
                long started = android.os.SystemClock.elapsedRealtime();
                microphone = recorder;
                byte[] pcm = new byte[1920];
                boolean first = true;
                while (micThread == self[0]) {
                    int n = recorder.read(pcm, 0, pcm.length);
                    if (n < 0) throw new IllegalStateException("Microphone read failed: " + n);
                    if (n == 0 || micThread != self[0]) continue;
                    target.sendSharing(SharingProtocol.MIC, SharingProtocol.tagged(revision, java.util.Arrays.copyOf(pcm, n)));
                    if (first) {
                        first = false;
                        android.util.Log.i("FoldDeck/Share", "mic: create " + (created - asked) + " ms, start "
                                + (started - created) + " ms, first PCM " + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
                    }
                }
            } catch (RuntimeException e) {
                if (micThread == self[0]) fail(2, "Microphone: " + e.getMessage(), revision);
            } finally {
                if (microphone == recorder) microphone = null;
                if (recorder != null) recorder.release();
            }
        }, "phone-microphone");
        micThread = self[0];
        micThread.start();
    }

    private void stopMic() {
        micThread = null;
        AudioRecord old = microphone;
        microphone = null;
        if (old != null) try { old.stop(); } catch (RuntimeException ignored) { }   // unblocks read()
    }

    private boolean cameraStarting;
    private void startCamera() {
        if (cameraStarting) return;
        cameraStarting = true;
        int generation = ++cameraGeneration;
        H264Stream target = stream;
        int revision = gate.revision;
        cameraHandler.post(() -> {
            if (generation != cameraGeneration) return;
            try {
                CameraManager manager = activity.getSystemService(CameraManager.class);
                String chosen = null;
                for (String id : manager.getCameraIdList()) {
                    chosen = id;
                    if (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) break;
                }
                if (chosen == null) throw new IllegalStateException("No phone camera");
                CameraCharacteristics info = manager.getCameraCharacteristics(chosen);
                boolean front = info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
                int sensorOrientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
                Size[] sizes = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
                Size size = java.util.Arrays.stream(sizes).min(java.util.Comparator.comparingLong(s ->
                        Math.abs((long) s.getWidth() * s.getHeight() - 640 * 480))).get();
                ImageReader reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
                images = reader;
                reader.setOnImageAvailableListener(source -> {
                    try (Image image = source.acquireLatestImage()) {
                        if (image == null || generation != cameraGeneration) return;
                        java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] jpeg = new byte[buffer.remaining()];
                        buffer.get(jpeg);
                        int rotation = activity.getDisplay() != null ? activity.getDisplay().getRotation() * 90 : 0;
                        int angle = (sensorOrientation + (front ? rotation : -rotation) + 360) % 360;
                        if (angle != 0) {
                            Bitmap original = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                            if (original == null) return;
                            Matrix matrix = new Matrix(); matrix.postRotate(angle);
                            Bitmap rotated = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);
                            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                            rotated.compress(Bitmap.CompressFormat.JPEG, 70, output);
                            jpeg = output.toByteArray(); rotated.recycle(); original.recycle();
                        }
                        if (jpeg.length + 5 <= SharingProtocol.MAX_PACKET && generation == cameraGeneration)
                            target.sendSharing(SharingProtocol.CAMERA, SharingProtocol.tagged(revision, jpeg));
                    } catch (RuntimeException e) { if (generation == cameraGeneration) fail(4, "Camera: " + e.getMessage(), generation); }
                    cameraHandler.postDelayed(() -> capture(generation), 100);
                }, cameraHandler);
                manager.openCamera(chosen, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice device) {
                        if (generation != cameraGeneration) {
                            try { device.close(); } catch (RuntimeException ignored) { }
                            return;
                        }
                        camera = device;
                        try {
                            device.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession created) {
                                    if (generation != cameraGeneration) {
                                        try { created.close(); } catch (RuntimeException ignored) { }
                                        return;
                                    }
                                    session = created;
                                    capture(generation);
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                                    if (generation == cameraGeneration) fail(4, "Camera configuration failed", generation);
                                }
                            }, cameraHandler);
                        } catch (CameraAccessException e) { fail(4, "Camera: " + e.getMessage(), generation); }
                    }
                    @Override public void onDisconnected(CameraDevice device) {
                        if (camera == device) { session = null; camera = null; }
                        try { device.close(); } catch (RuntimeException ignored) { }
                        if (generation == cameraGeneration) fail(4, "Phone camera disconnected", generation);
                    }
                    @Override public void onError(CameraDevice device, int error) {
                        if (camera == device) { session = null; camera = null; }
                        try { device.close(); } catch (RuntimeException ignored) { }
                        if (generation == cameraGeneration) fail(4, "Camera error " + error, generation);
                    }
                }, cameraHandler);
            } catch (CameraAccessException | RuntimeException e) { fail(4, "Camera: " + e.getMessage(), generation); }
        });
    }

    private void capture(int generation) {
        if (generation != cameraGeneration || camera == null || session == null) return;
        try {
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(images.getSurface());
            request.set(CaptureRequest.JPEG_QUALITY, (byte) 70);
            request.set(CaptureRequest.JPEG_ORIENTATION, 0);
            session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                    if (generation == cameraGeneration) fail(4, "Phone camera capture failed", generation);
                }
            }, cameraHandler);
        } catch (CameraAccessException | RuntimeException e) { fail(4, "Camera: " + e.getMessage(), generation); }
    }

    private void stopCamera() {
        cameraStarting = false;
        ++cameraGeneration;
        cameraHandler.post(() -> {
            if (session != null) { try { session.close(); } catch (RuntimeException ignored) { } session = null; }
            if (camera != null) { try { camera.close(); } catch (RuntimeException ignored) { } camera = null; }
            if (images != null) { try { images.close(); } catch (RuntimeException ignored) { } images = null; }
        });
    }

    void close() {
        disconnect();
        main.removeCallbacks(leaseCheck);
        clipboard.removePrimaryClipChangedListener(clipboardChanged);
        cameraThread.quitSafely();
    }
}
