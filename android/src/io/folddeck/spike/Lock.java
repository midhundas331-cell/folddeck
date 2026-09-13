package io.folddeck.spike;

import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * App-lock PIN storage.
 *
 * This is a UI gate, not a cryptographic one: it stops someone who picks up your
 * unlocked phone, and does not pretend to stop someone with real access to the
 * device. The connection credentials are NOT wrapped by it.
 *
 * The PIN is still stored as a salted PBKDF2 hash rather than in the clear --
 * cheap to do properly, and it means a casual look at the prefs file reveals
 * nothing. A four-digit PIN is only 10,000 possibilities, so the iteration count
 * is the only thing making an offline guess cost anything; hence the attempt
 * counter, which is the real defence.
 */
final class Lock {

    private static final String PREFS = "folddeck_lock";
    private static final String KEY_SALT = "salt";
    private static final String KEY_HASH = "hash";
    private static final String KEY_FAILS = "fails";
    private static final String KEY_BIOMETRIC = "biometric_enabled";

    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    static final int PIN_LENGTH = 4;
    static final int MAX_FAILS = 10;

    private final SharedPreferences prefs;

    Lock(android.content.Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    boolean isConfigured() {
        return prefs.contains(KEY_HASH);
    }

    boolean biometricEnabled() {
        return prefs.getBoolean(KEY_BIOMETRIC, true);
    }

    void setBiometricEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply();
    }

    /** Store a new PIN, replacing any existing one. */
    void setPin(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        prefs.edit()
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
                .putInt(KEY_FAILS, 0)
                .apply();
    }

    /** @return true if the PIN matches. Failed attempts are counted. */
    boolean verify(String pin) {
        String saltB64 = prefs.getString(KEY_SALT, null);
        String hashB64 = prefs.getString(KEY_HASH, null);
        if (saltB64 == null || hashB64 == null) return false;

        byte[] want = Base64.decode(hashB64, Base64.NO_WRAP);
        byte[] got = hash(pin, Base64.decode(saltB64, Base64.NO_WRAP));

        if (!constantTimeEquals(want, got)) {
            prefs.edit().putInt(KEY_FAILS, failedAttempts() + 1).apply();
            return false;
        }
        prefs.edit().putInt(KEY_FAILS, 0).apply();
        return true;
    }

    int failedAttempts() {
        return prefs.getInt(KEY_FAILS, 0);
    }

    int attemptsRemaining() {
        return Math.max(0, MAX_FAILS - failedAttempts());
    }

    /**
     * After MAX_FAILS wrong PINs the lock stops accepting guesses.
     *
     * Without this a four-digit PIN falls to a patient thumb in an afternoon;
     * 10,000 possibilities is nothing when there is no cost per attempt.
     */
    boolean isLockedOut() {
        return failedAttempts() >= MAX_FAILS;
    }

    /** Clears the PIN entirely, forcing setup again. Also resets the counter. */
    void reset() {
        prefs.edit().remove(KEY_SALT).remove(KEY_HASH).putInt(KEY_FAILS, 0).apply();
    }

    // ------------------------------------------------------------------ //
    private static byte[] hash(String pin, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
