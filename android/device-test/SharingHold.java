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
    private boolean focus;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        seconds = Integer.parseInt(args == null ? "60" : args.getString("seconds", "60"));
        focus = args != null && "true".equals(args.getString("focus"));
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
