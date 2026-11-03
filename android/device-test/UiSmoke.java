package io.folddeck.uitest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Signed, separately installed instrumentation. No test entry points in the production APK. */
public final class UiSmoke extends Instrumentation {
    private Activity activity;
    private final Map<String, Object> saved = new HashMap<>();
    private SharedPreferences prefs;
    private SharedPreferences backup;
    private int screenshots;
    private boolean record;
    private int rotation = -1;
    private boolean leaveBoth;
    private boolean shareTest;
    private android.content.ClipData originalClip;
    private boolean clipboardTested;
    private String physicalDisplay;
    private android.os.ParcelFileDescriptor video;
    private final java.util.List<Long> frameTimes = new java.util.ArrayList<>();
    private android.view.Window.OnFrameMetricsAvailableListener metrics;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        record = args != null && "true".equals(args.getString("record"));
        leaveBoth = args != null && "true".equals(args.getString("leaveBoth"));
        shareTest = args != null && "true".equals(args.getString("sharing"));
        if (args != null && args.containsKey("display")) physicalDisplay = Long.toString(Long.parseLong(args.getString("display")));
        if (args != null) rotation = Integer.parseInt(args.getString("rotation", "-1"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            SharedPreferences crashes = getTargetContext().getSharedPreferences("folddeck_crash", 0);
            if (crashes.getString("trace", "").contains("io.folddeck.uitest")) crashes.edit().remove("trace").commit();
            if (rotation >= 0) getUiAutomation().setRotation(rotation);
            activity = startActivitySync(new Intent().setClassName("io.folddeck.spike",
                    "io.folddeck.spike.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            prefs = activity.getSharedPreferences("folddeck", 0);
            backup = activity.getSharedPreferences("folddeck_ui_test_backup", 0);
            if (backup.getBoolean("pending", false)) {
                for (Map.Entry<String, ?> e : backup.getAll().entrySet()) if (!e.getKey().equals("pending")) {
                    saved.put(e.getKey(), e.getValue() instanceof String ? null : e.getValue());
                }
                restorePreferences();
                saved.clear();
            }
            for (String key : new String[]{"layout_portrait", "layout_landscape", "layout_cover", "hide_keyboard",
                    "show_shortcuts", "mouse_auto_capture", "laptop_audio", "share_clipboard", "share_microphone", "share_camera"}) remember(key);
            for (int p = 0; p < 3; p++) for (String k : new String[]{"resize_monitor_", "resize_pad_", "resize_bar_", "resize_height_", "resize_wheel_", "resize_buttons_"}) remember(k + p);
            SharedPreferences.Editor snapshot = backup.edit().putBoolean("pending", true);
            for (Map.Entry<String, Object> e : saved.entrySet()) {
                Object value = e.getValue();
                if (value == null) snapshot.putString(e.getKey(), "absent");
                else if (value instanceof Float) snapshot.putFloat(e.getKey(), (Float) value);
                else if (value instanceof Integer) snapshot.putInt(e.getKey(), (Integer) value);
                else if (value instanceof Boolean) snapshot.putBoolean(e.getKey(), (Boolean) value);
            }
            snapshot.commit();
            ui(() -> {
                metrics = (window, frame, dropped) -> {
                    if (get("resizeOverlay") != null) frameTimes.add(frame.getMetric(android.view.FrameMetrics.TOTAL_DURATION));
                };
                activity.getWindow().addOnFrameMetricsAvailableListener(metrics, new android.os.Handler(android.os.Looper.getMainLooper()));
                prefs.edit().putInt("layout_portrait", 0).putInt("layout_landscape", 0).putInt("layout_cover", 0)
                        .putBoolean("hide_keyboard", false).putBoolean("show_shortcuts", true)
                        .putBoolean("mouse_auto_capture", false).putBoolean("laptop_audio", false).apply();
                SharedPreferences.Editor reset = prefs.edit();
                for (int p = 0; p < 3; p++) for (String k : new String[]{"resize_monitor_", "resize_pad_", "resize_bar_", "resize_height_", "resize_wheel_", "resize_buttons_"}) reset.remove(k + p);
                reset.apply();
                call(get("lockOverlay"), "cancelBiometric");
                if (record) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                call(activity, "onUnlocked");
                call(activity, "applyCustomization");
            });
            settle();
            if (record && physicalDisplay != null) {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                activity.getDisplay().getRealMetrics(dm);
                float scale = 1440f / Math.max(dm.widthPixels, dm.heightPixels);
                int w = Math.round(dm.widthPixels * scale / 2) * 2;
                int h = Math.round(dm.heightPixels * scale / 2) * 2;
                video = getUiAutomation().executeShellCommand("screenrecord --display-id " + physicalDisplay
                        + " --size " + w + "x" + h + " --bit-rate 8000000 --time-limit 90 /sdcard/folddeck-ui-test.mp4");
                SystemClock.sleep(800);
            }
            snapshot("home");
            ui(() -> call(activity, "connect"));
            long deadline = SystemClock.uptimeMillis() + 15000;
            while ((int) get("screen") != 1 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
            check((int) get("screen") == 1, "live stream did not reach the desktop");
            if ((boolean) get("coverDisplay")) ui(() -> ((View) get("coverToggle")).performClick());
            settle();
            snapshot("deck");
            testLatches();

            // Exercise the real local rail, then the production touch handler of the editor slider.
            ui(() -> findDescription((View) get("shortcutBar"), "Resize workspace").performClick());
            settle();
            check(get("resizeOverlay") != null, "resize button did not open studio");
            for (String target : new String[]{"Monitor", "Keyboard", "Trackpad", "Bar"}) {
                ui(() -> findText((View) get("resizeOverlay"), target).performClick());
                settle();
                String field = target.equals("Trackpad") ? "padScale" : target.equals("Bar") ? "barScale" : "monitorFraction";
                dragSlider(0.1f, 0.3f);
                float before = (float) get(field);
                View targetView = (View) get(target.equals("Monitor") ? "surfaceView"
                        : target.equals("Keyboard") ? "keyboard" : target.equals("Trackpad") ? "trackpadDeck" : "shortcutBar");
                int beforeWidth = targetView.getWidth(), beforeHeight = targetView.getHeight();
                dragSlider(0.3f, 0.7f);
                settle();
                check((float) get(field) != before, target + " did not resize");
                check(targetView.getWidth() != beforeWidth || targetView.getHeight() != beforeHeight,
                        target + " changed its value without changing its visible size");
                checkBounds();
                snapshot("resize-" + target.toLowerCase());
            }
            for (String name : new String[]{"Pad height", "Wheel", "Buttons"}) {
                int keyboardHeight = ((View) get("keyboard")).getHeight();
                ui(() -> findText((View) get("resizeOverlay"), name).performClick());
                dragSlider(0.1f, 0.2f);
                settle();
                View pad = (View) get("trackpadDeck");
                float before = name.equals("Pad height") ? pad.getHeight() : name.equals("Wheel")
                        ? ((android.graphics.RectF) field(pad, "scroll")).width() : ((android.graphics.RectF) field(pad, "left")).height();
                dragSlider(0.2f, 0.65f);
                settle();
                float after = name.equals("Pad height") ? pad.getHeight() : name.equals("Wheel")
                        ? ((android.graphics.RectF) field(pad, "scroll")).width() : ((android.graphics.RectF) field(pad, "left")).height();
                check(after > before, name + " did not visibly grow");
                check(((View) get("keyboard")).getHeight() == keyboardHeight,
                        name + " resized the keyboard");
                checkBounds();
                snapshot("resize-" + name.replace(' ', '-'));
            }
            ui(() -> findText((View) get("resizeOverlay"), "Done").performClick());
            settle();
            float savedBar = (float) get("barScale");
            int posture = (int) get("resizePosture");
            check(prefs.getFloat("resize_bar_" + posture, -1) == savedBar, "Done did not persist");
            check(!prefs.contains("resize_bar_" + ((posture + 1) % 3)), "Save changed another posture");
            ui(() -> call(activity, "showResize"));
            settle();
            ui(() -> findText((View) get("resizeOverlay"), "Bar").performClick());
            dragSlider(0.7f, 0.1f);
            ui(() -> call(activity, "handleBack"));
            settle();
            check((float) get("barScale") == savedBar, "Back did not cancel changes");

            ui(() -> findDescription((View) get("shortcutBar"), "Folddeck app settings").performClick());
            settle();
            check((int) get("screen") == 2, "App button did not open local settings");
            snapshot("app-settings");
            ui(() -> call(activity, "handleBack"));
            check((int) get("screen") == 1, "App settings Back did not return to deck");

            // The split keyboard has a separate allocation path; exercise it on hardware too.
            ui(() -> {
                prefs.edit().putInt("layout_portrait", 4).putInt("layout_landscape", 4).putInt("layout_cover", 4).apply();
                call(activity, "applyCustomization");
                call(activity, "showResize");
            });
            settle();
            ui(() -> findText((View) get("resizeOverlay"), "Keyboard").performClick());
            dragSlider(0.3f, 0.05f);
            settle();
            int independentKeyboardHeight = ((View) get("keyboard")).getHeight();
            ui(() -> findText((View) get("resizeOverlay"), "Pad height").performClick());
            dragSlider(0.2f, 1f);
            settle();
            check(((View) get("trackpadDeck")).getHeight() > independentKeyboardHeight,
                    "Trackpad is still capped to keyboard height");
            check(((View) get("keyboard")).getHeight() == independentKeyboardHeight,
                    "Growing the trackpad changed the keyboard height");
            View raised = (View) get("trackpadDeck");
            int[] raisedAt = new int[2], monitorAt = new int[2], barAt = new int[2];
            raised.getLocationInWindow(raisedAt);
            ((View) get("surfaceView")).getLocationInWindow(monitorAt);
            ((View) get("shortcutBar")).getLocationInWindow(barAt);
            check(raisedAt[1] < monitorAt[1] + ((View) get("surfaceView")).getHeight(),
                    "Trackpad is still confined below the monitor band");
            int monitorRight = monitorAt[0] + ((View) get("surfaceView")).getWidth();
            int barRight = barAt[0] + ((View) get("shortcutBar")).getWidth();
            check((barRight <= raisedAt[0] && raisedAt[0] + raised.getWidth() <= monitorAt[0])
                    || (monitorRight <= raisedAt[0] && raisedAt[0] + raised.getWidth() <= barAt[0]),
                    "Raised trackpad is not between monitor and custom bar");
            checkBounds();
            snapshot("independent-tall-trackpad");
            ui(() -> findText((View) get("resizeOverlay"), "Trackpad").performClick());
            dragSlider(0.2f, 0.8f);
            settle();
            float padBeforeDrag = (float) get("padScale");
            ui(() -> {
                View overlay = (View) get("resizeOverlay");
                long at = SystemClock.uptimeMillis();
                for (int i = 0; i < 3; i++) {
                    MotionEvent e = MotionEvent.obtain(at, at + i * 16,
                            i == 0 ? MotionEvent.ACTION_DOWN : i == 1 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_UP,
                            overlay.getWidth() * (i == 0 ? 0.3f : 0.4f), overlay.getHeight() * 0.8f, 0);
                    overlay.dispatchTouchEvent(e); e.recycle();
                }
            });
            settle();
            check((float) get("padScale") > padBeforeDrag, "direct surface drag did not resize");
            checkBounds();
            snapshot("split-keyboard");
            ui(() -> {
                View kb = (View) get("keyboard");
                try {
                    call(kb, "onSizeChanged", 800, 1300, kb.getWidth(), kb.getHeight());
                    checkLegends(kb);
                } finally {
                    call(kb, "onSizeChanged", kb.getWidth(), kb.getHeight(), 800, 1300);
                }
            });
            ui(() -> findText((View) get("resizeOverlay"), "Reset").performClick());
            settle();
            check((float) get("monitorFraction") == -1f && (float) get("barScale") == 1f, "Reset failed");
            ui(() -> findText((View) get("resizeOverlay"), "Cancel").performClick());
            if (shareTest) testSharing();
            result.putString("stream", "PASS: live stream, seven resize controls, hold sections, bounds, save, cancel, reset, settings return, split keyboard" + (shareTest ? ", phone sharing" : "") + "; " + screenshots + " screenshots");
            ui(() -> activity.getWindow().removeOnFrameMetricsAvailableListener(metrics));
            java.util.Collections.sort(frameTimes);
            if (!frameTimes.isEmpty()) result.putString("frames", String.format(java.util.Locale.US,
                    "%d editor frames; UI duration p50 %.2f ms, p95 %.2f ms", frameTimes.size(),
                    frameTimes.get(frameTimes.size() / 2) / 1e6,
                    frameTimes.get((int) ((frameTimes.size() - 1) * 0.95)) / 1e6));
        } catch (Throwable e) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(e));
        } finally {
            if (video != null) {
                try {
                    getUiAutomation().executeShellCommand("pkill -INT -f /sdcard/folddeck-ui-test.mp4").close();
                    try (java.io.InputStream output = new android.os.ParcelFileDescriptor.AutoCloseInputStream(video)) {
                        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                        byte[] chunk = new byte[1024];
                        int count;
                        while ((count = output.read(chunk)) >= 0) bytes.write(chunk, 0, count);
                        String log = bytes.toString("UTF-8");
                        result.putString("video", log.isEmpty() ? "/sdcard/folddeck-ui-test.mp4" : log);
                        if (log.contains("ERROR")) result.putString("stream", "FAIL recording: " + log);
                    }
                } catch (Exception e) { result.putString("video", e.toString()); }
            }
            if (activity != null) ui(() -> {
                call(activity, "finishResize", false);
                call(activity, "stopStreaming");
                if (clipboardTested) {
                    android.content.ClipboardManager cm = activity.getSystemService(android.content.ClipboardManager.class);
                    if (originalClip == null) cm.clearPrimaryClip(); else cm.setPrimaryClip(originalClip);
                }
                if (prefs != null) restorePreferences();
                if (leaveBoth && prefs != null) {
                    SharedPreferences.Editor edit = prefs.edit().putInt("layout_landscape", 0)
                            .putInt("layout_portrait", 0).putInt("layout_cover", 0).putBoolean("show_shortcuts", true)
                            .putBoolean("laptop_audio", true).putBoolean("mouse_auto_capture", true);
                    for (int p = 0; p < 3; p++) for (String k : new String[]{"resize_monitor_", "resize_pad_", "resize_bar_"}) edit.remove(k + p);
                    edit.apply();
                }
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                activity.finish();
            });
            if (rotation >= 0) getUiAutomation().setRotation(-2);
        }
        finish(result.getString("stream", "FAIL").startsWith("PASS") ? -1 : 0, result);
    }

    private void remember(String key) { saved.put(key, prefs.getAll().get(key)); }
    private void testLatches() {
        ui(() -> {
            Object pad = get("trackpadDeck");
            Object original = field(pad, "listener");
            java.util.List<String> events = new java.util.ArrayList<>();
            Class<?> type = original.getClass().getInterfaces()[0];
            Object listener = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getName().equals("onButton")) events.add(args[0] + ":" + args[1]);
                return null;
            });
            call(pad, "setListener", listener);
            try {
                for (String name : new String[]{"left", "right"}) {
                    android.graphics.RectF box = (android.graphics.RectF) field(pad, name);
                    tap((View) pad, box.right - 5, box.centerY());
                    check((int) field(pad, "pressedMask") != 0, "Hold did not latch");
                    tap((View) pad, box.left + 5, box.centerY());
                    check((int) field(pad, "pressedMask") != 0, "normal button released latch");
                    tap((View) pad, box.right - 5, box.centerY());
                    check((int) field(pad, "pressedMask") == 0, "Held did not release");
                }
                check(events.size() == 4, "latches emitted duplicate button transitions: " + events);
                android.graphics.RectF box = (android.graphics.RectF) field(pad, "left");
                tap((View) pad, box.right - 5, box.centerY());
                call(pad, "cancelInput");
                check((int) field(pad, "pressedMask") == 0 && events.size() == 6, "cancel did not release latch");
            } finally { call(pad, "cancelInput"); call(pad, "setListener", original); }
        });
    }

    private void tap(View v, float x, float y) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now + action * 20, action, x, y, 0);
            v.dispatchTouchEvent(event); event.recycle();
        }
    }

    private void testSharing() throws Exception {
        Object sharing = get("sharing");
        getUiAutomation().grantRuntimePermission("io.folddeck.spike", "android.permission.RECORD_AUDIO");
        getUiAutomation().grantRuntimePermission("io.folddeck.spike", "android.permission.CAMERA");
        ui(() -> {
            call(activity, "showSettings");
            clickText((View) get("settingsView"), "Phone Sharing");
            call(sharing, "focus", true);
            if (!(boolean) call(sharing, "enabled", 1)) clickText((View) get("settingsView"), "Clipboard sync");
            if (!(boolean) call(sharing, "enabled", 2)) clickText((View) get("settingsView"), "Phone microphone");
            if (!(boolean) call(sharing, "enabled", 4)) clickText((View) get("settingsView"), "Phone camera");
        });
        long deadline = SystemClock.uptimeMillis() + 10000;
        while ((int) call(sharing, "available") != 7 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        check((int) call(sharing, "available") == 7, "Phone inputs not ready: " + field(sharing, "status"));
        check((int) call(sharing, "active") == 1 && field(sharing, "microphone") == null
                && !(boolean) field(sharing, "cameraStarting"), "Idle sensors activated without a consumer");
        settle();
        snapshot("phone-sharing");
        ui(() -> {
            android.content.ClipboardManager cm = activity.getSystemService(android.content.ClipboardManager.class);
            originalClip = cm.getPrimaryClip(); clipboardTested = true;
            cm.setPrimaryClip(android.content.ClipData.newPlainText("test", "Folddeck phone clipboard test 🌍"));
        });
        Bundle stage = new Bundle(); stage.putString("stage", "SHARING_IDLE"); sendStatus(1, stage);
        deadline = SystemClock.uptimeMillis() + 15000;
        while ((int) call(sharing, "active") != 7 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        check((int) call(sharing, "active") == 7, "Consumers did not activate sensors: " + field(sharing, "status"));
        snapshot("phone-sharing-live");
        stage.putString("stage", "SHARING_LIVE"); sendStatus(1, stage);
        // Host runner reads real media, then closes both consumers.
        deadline = SystemClock.uptimeMillis() + 15000;
        while ((int) call(sharing, "active") != 1 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        check((int) call(sharing, "active") == 1 && field(sharing, "microphone") == null
                && !(boolean) field(sharing, "cameraStarting"), "Sensors did not return to idle");
        stage.putString("stage", "SHARING_IDLE_AGAIN"); sendStatus(1, stage);
        // Restart consumers and exercise both real settings controls while live.
        deadline = SystemClock.uptimeMillis() + 10000;
        while ((int) call(sharing, "active") != 7 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        check((int) call(sharing, "active") == 7, "Second demand did not start sensors");
        ui(() -> {
            String text = activity.getSystemService(android.content.ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString();
            check(text.equals("Folddeck laptop clipboard test 🌍"), "Laptop clipboard did not arrive");
            clickText((View) get("settingsView"), "Phone microphone");
            clickText((View) get("settingsView"), "Phone camera");
            check(!prefs.getBoolean("share_microphone", true) && !prefs.getBoolean("share_camera", true), "Disable did not persist");
            call(activity, "showScreen", 1);
        });
        settle();
        check(((int) call(sharing, "active") & 6) == 0, "Disable did not stop captures");
        stage.putString("stage", "SHARING_STOPPED"); sendStatus(1, stage);
    }
    private void restorePreferences() {
        SharedPreferences.Editor edit = prefs.edit();
        for (Map.Entry<String, Object> e : saved.entrySet()) {
            Object value = e.getValue();
            if (value == null) edit.remove(e.getKey());
            else if (value instanceof Float) edit.putFloat(e.getKey(), (Float) value);
            else if (value instanceof Integer) edit.putInt(e.getKey(), (Integer) value);
            else if (value instanceof Boolean) edit.putBoolean(e.getKey(), (Boolean) value);
        }
        edit.commit();
        backup.edit().clear().commit();
    }
    private void ui(Runnable runnable) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> { try { runnable.run(); } catch (Throwable e) { failure.set(e); } });
        waitForIdleSync();
        if (failure.get() != null) throw new RuntimeException(failure.get());
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(450); }
    private Object get(String name) { return field(activity, name); }
    private static Object field(Object owner, String name) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Object call(Object owner, String name, Object... args) {
        for (Method m : owner.getClass().getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == args.length) {
            try { m.setAccessible(true); return m.invoke(owner, args); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        throw new AssertionError("Missing method " + name);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static View findText(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View found = findText(((ViewGroup) v).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }

    private static void clickText(View root, String label) {
        View view = findText(root, label);
        check(view != null, "Missing control " + label);
        while (!view.isClickable() && view.getParent() instanceof View) view = (View) view.getParent();
        check(view.performClick(), "Control did not click: " + label);
    }
    private static View findDescription(View v, String text) {
        if (text.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) v).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private void dragSlider(float from, float to) {
        SeekBar slider = (SeekBar) field(get("resizeOverlay"), "slider");
        long start = SystemClock.uptimeMillis();
        for (int step = 0; step <= 72; step++) {
            float p = from + (to - from) * step / 72f;
            int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 72 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            ui(() -> {
                MotionEvent e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                        slider.getPaddingLeft() + p * (slider.getWidth() - slider.getPaddingLeft() - slider.getPaddingRight()),
                        slider.getHeight() / 2f, 0);
                slider.dispatchTouchEvent(e); e.recycle();
            });
            SystemClock.sleep(12);
        }
    }
    private void checkBounds() {
        ui(() -> {
            View lid = (View) get("lid"), base = (View) get("base"), deck = (View) get("deck");
            check(lid.getBottom() <= base.getTop() && base.getBottom() <= deck.getHeight(), "deck bands overlap/overflow");
            View surface = (View) get("surfaceView"), pane = (View) get("videoPane");
            check(surface.getWidth() > 0 && surface.getHeight() > 0
                    && surface.getRight() <= pane.getWidth() && surface.getBottom() <= pane.getHeight(), "monitor outside pane");
            for (String name : new String[]{"keyboard", "trackpadDeck", "shortcutBar"}) {
                View v = (View) get(name), parent = (View) v.getParent();
                check(v.getWidth() > 0 && v.getHeight() > 0 && v.getLeft() >= 0 && v.getTop() >= 0
                        && v.getRight() <= parent.getWidth() && v.getBottom() <= parent.getHeight(), name + " outside parent");
            }
            checkLegends(get("keyboard"));
        });
    }
    private void checkLegends(Object keyboard) {
            android.graphics.Paint text = (android.graphics.Paint) field(keyboard, "text");
            for (Object cap : (Iterable<?>) field(keyboard, "caps")) {
                Object key = field(cap, "key");
                String label = (String) field(key, "label");
                call(keyboard, "drawLegends", new android.graphics.Canvas(), cap);
                android.graphics.RectF rect = (android.graphics.RectF) field(cap, "rect");
                check(text.measureText(label) <= rect.width(), "key label overflow: " + label);
            }
    }
    private void snapshot(String name) throws Exception {
        if (!record) return;
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        check(bitmap != null, "screenshot unavailable");
        File file = new File(activity.getExternalFilesDir(null), "ui-" + name + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle(); screenshots++;
    }
}
