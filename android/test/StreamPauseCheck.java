package io.folddeck.spike;

import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * A hidden phone keeps its connection for sharing: pause asks the host to stop the
 * screen stream and hand laptop audio back; resume undoes both.
 *
 *   javac --release 17 -cp build/classes:$J -d build/test test/StreamPauseCheck.java
 *   java -cp build/test:build/classes:$J io.folddeck.spike.StreamPauseCheck
 */
public class StreamPauseCheck {
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] args) throws Exception {
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, a) -> method.getName().equals("getBoolean") ? Boolean.TRUE : null);
        H264Stream stream = new H264Stream("laptop", 5000, null, (b, d, x, p, q, s) -> { }, prefs);
        Field f = H264Stream.class.getDeclaredField("outbox");
        f.setAccessible(true);
        InputOutbox outbox = (InputOutbox) f.get(stream);

        check(!stream.isPaused(), "a new stream shows video");
        stream.pause();
        check(stream.isPaused(), "pause is remembered for reconnects");
        check(Arrays.equals(outbox.takeAll(0), new byte[]{9, 0, 6, 0}), "pause: video off, laptop audio back");
        stream.pause();
        check(outbox.takeAll(0) == null, "pausing twice sends nothing");
        stream.resume(null);
        check(!stream.isPaused(), "resume clears pause");
        check(Arrays.equals(outbox.takeAll(0), new byte[]{6, 1, 9, 1}), "resume: laptop audio per pref, video on");
        check(stream.sameRoute("laptop", 5000) && !stream.sameRoute("laptop", 5001), "route identity");
        System.out.println("StreamPauseCheck ok");
    }
}
