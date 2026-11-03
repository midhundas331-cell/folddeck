package io.folddeck.uitest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Method;

/**
 * Unlock, connect with microphone + camera sharing enabled, then hold the session
 * for `-e seconds N` so a laptop-side script (latency-check.py) can measure it.
 * The app process ends with the instrumentation, which is the disconnect.
 */
public final class SharingHold extends Instrumentation {
    private int seconds;
    private boolean focus, cycle;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        seconds = Integer.parseInt(args == null ? "60" : args.getString("seconds", "60"));
        focus = args != null && "true".equals(args.getString("focus"));
        cycle = args != null && "true".equals(args.getString("cycle"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            // Before the activity exists: PhoneSharing reads these in its constructor.
            getTargetContext().getSharedPreferences("folddeck", 0).edit()
                    .putBoolean("share_microphone", true).putBoolean("share_camera", true).commit();
            Activity activity = startActivitySync(new Intent().setClassName("io.folddeck.spike",
                    "io.folddeck.spike.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                call(field(activity, "lockOverlay"), "cancelBiometric");
                call(activity, "onUnlocked");
            });
            waitForIdleSync();
            SystemClock.sleep(300);
            runOnMainSync(() -> {
                call(activity, "connect");
                // Old builds gate sharing on window focus; force it for a baseline.
                if (focus) call(field(activity, "sharing"), "focus", true);
            });
            status("CONNECTING", "");
            if (cycle) cycle(activity);
            long end = SystemClock.uptimeMillis() + seconds * 1000L;
            while (SystemClock.uptimeMillis() < end) {
                Object sharing = field(activity, "sharing");
                status("HOLD", String.valueOf(field(sharing, "status")));
                SystemClock.sleep(1000);
            }
            result.putString("hold", "PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("hold", "FAIL: " + e);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    /** HOME, back, unlock: the same connection must pause, then decode video again. */
    private void cycle(Activity activity) throws Exception {
        SystemClock.sleep(6000);
        Object stream = field(activity, "stream");
        int before = (int) field(stream, "decoded");
        getUiAutomation().executeShellCommand("input keyevent HOME").close();
        SystemClock.sleep(3000);
        boolean paused = (boolean) call(stream, "isPaused");
        // singleTask: this brings the same instance back (startActivitySync would wait for a new one).
        getTargetContext().startActivity(new Intent().setClassName("io.folddeck.spike",
                "io.folddeck.spike.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemClock.sleep(1500);
        Activity again = activity;
        runOnMainSync(() -> {
            call(field(again, "lockOverlay"), "cancelBiometric");
            call(again, "onUnlocked");
        });
        SystemClock.sleep(1000);
        int at = (int) field(stream, "decoded");
        SystemClock.sleep(3000);
        int after = (int) field(stream, "decoded");
        boolean ok = paused && field(again, "stream") == stream && !(boolean) call(stream, "isPaused") && after > at;
        status("CYCLE", (ok ? "PASS" : "FAIL") + " paused=" + paused + " sameConnection=" + (field(again, "stream") == stream)
                + " decoded " + before + " -> " + at + " -> " + after);
        if (!ok) throw new AssertionError("pause/resume cycle failed");
    }

    /** Streams a line to `am instrument -r` as INSTRUMENTATION_STATUS: stage=... */
    private void status(String stage, String detail) {
        Bundle b = new Bundle();
        b.putString("stage", stage);
        b.putString("detail", detail);
        sendStatus(0, b);
    }

    private static Object field(Object owner, String name) {
        try {
            java.lang.reflect.Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Object call(Object owner, String name, Object... args) {
        for (Method m : owner.getClass().getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == args.length) {
            try { m.setAccessible(true); return m.invoke(owner, args); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        throw new AssertionError("Missing method " + name);
    }
}
