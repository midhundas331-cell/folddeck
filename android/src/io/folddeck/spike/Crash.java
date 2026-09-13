package io.folddeck.spike;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Captures uncaught exceptions so a crash produces a readable stack trace
 * instead of Samsung's "this app has a bug" dialog.
 *
 * Without adb on this phone there is no logcat to read, so the trace is written
 * two ways: into SharedPreferences (shown on the next launch) and to
 * /sdcard/Android/data/io.folddeck.spike/files/crash.txt, which is reachable
 * from the Files app or over scp without any developer options.
 */
final class Crash {

    private static final String PREFS = "folddeck_crash";
    private static final String KEY_TRACE = "trace";

    private Crash() { }

    static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                record(ctx, thread.getName(), error);
            } catch (Throwable ignored) {
                // Never let the handler itself throw; that would replace a
                // useful trace with a useless one.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static void record(Context ctx, String threadName, Throwable error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("FoldDeck crash");
        pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("thread: " + threadName);
        pw.println("device: " + android.os.Build.MODEL + "  Android "
                + android.os.Build.VERSION.RELEASE + " (API "
                + android.os.Build.VERSION.SDK_INT + ")");
        pw.println();
        error.printStackTrace(pw);
        String trace = sw.toString();

        prefs(ctx).edit().putString(KEY_TRACE, trace).commit();

        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir != null) {
                try (FileOutputStream fos = new FileOutputStream(new File(dir, "crash.txt"))) {
                    fos.write(trace.getBytes());
                }
            }
        } catch (Throwable ignored) {
            // The prefs copy is the one that matters; the file is a convenience.
        }
    }

    static String pending(Context ctx) {
        return prefs(ctx).getString(KEY_TRACE, null);
    }

    static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY_TRACE).commit();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
