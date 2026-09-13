package io.folddeck.spike;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The PIN / fingerprint lock screen.
 *
 * Framework {@link BiometricPrompt} (API 28+), not androidx.biometric -- the
 * framework class is enough at minSdk 31 and keeps the build free of Gradle and
 * AndroidX, which is what makes this project build in two seconds.
 *
 * Opaque by construction: it covers the deck completely, and the caller stops
 * the stream while it is up, so the desktop is not being captured on the laptop
 * either while the phone is locked.
 */
class LockOverlay extends LinearLayout {

    interface Listener {
        void onUnlocked();
    }

    private final Lock lock;
    private final Listener listener;
    private final Activity activity;

    private final TextView title = new TextView(getContext());
    private final TextView hint = new TextView(getContext());
    private final LinearLayout dots = new LinearLayout(getContext());
    private final View[] dotViews = new View[Lock.PIN_LENGTH];
    private final TextView biometricButton;

    private final StringBuilder entered = new StringBuilder();
    private String firstEntry;          // during setup, the PIN awaiting confirmation
    private CancellationSignal cancel;

    LockOverlay(Activity activity, Lock lock, Listener listener) {
        super(activity);
        this.activity = activity;
        this.lock = lock;
        this.listener = listener;

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(Ui.BG);
        // Swallow every touch so nothing reaches the stream behind it.
        setClickable(true);
        setFocusable(true);

        LayoutParams markLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        markLp.setMargins(Ui.dp(activity, 32), 0, Ui.dp(activity, 32), Ui.dp(activity, 28));
        View mark = Ui.wordmark(activity, 300);
        addView(mark, markLp);

        title.setTextColor(Ui.TEXT);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.T_TITLE);
        title.setTypeface(Ui.FONT_BOLD);
        title.setGravity(Gravity.CENTER);
        addView(title);

        buildDots();
        addView(dots);

        hint.setTextColor(Ui.TEXT_DIM);
        hint.setTypeface(Ui.FONT);
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.T_LABEL);
        hint.setGravity(Gravity.CENTER);
        // One line is always reserved, even empty, so a message never shoves the
        // keypad down under a finger mid-PIN; only the padding around it is trimmed.
        hint.setPadding(Ui.dp(activity, 32), 0, Ui.dp(activity, 32), Ui.dp(activity, 6));
        addView(hint);

        addView(buildKeypad());

        biometricButton = Ui.text(activity, "Use fingerprint", Ui.T_BODY, Ui.ACCENT);
        biometricButton.setGravity(Gravity.CENTER);
        biometricButton.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 14),
                Ui.dp(activity, 20), Ui.dp(activity, 14));
        biometricButton.setBackground(
                Ui.pressable(Ui.square(Ui.BG)));
        biometricButton.setClickable(true);
        biometricButton.setOnClickListener(v -> promptBiometric());
        LayoutParams bioLp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        bioLp.topMargin = Ui.dp(activity, 12);
        bioLp.gravity = Gravity.CENTER_HORIZONTAL;
        addView(biometricButton, bioLp);

        refresh();
    }

    // ------------------------------------------------------------------ //
    /**
     * Omarchy's lock field: a box outlined 3px in the accent, the entry inside.
     * Real squares rather than ■ and □ glyphs: the two characters have different
     * optical weights in most fonts, so the row visibly jumped as digits landed.
     */
    private void buildDots() {
        dots.setOrientation(HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        LayoutParams fieldLp = new LayoutParams(Ui.dp(getContext(), 280), Ui.dp(getContext(), 60));
        fieldLp.gravity = Gravity.CENTER_HORIZONTAL;
        fieldLp.setMargins(0, Ui.dp(getContext(), 18), 0, Ui.dp(getContext(), 10));
        dots.setLayoutParams(fieldLp);
        for (int i = 0; i < dotViews.length; i++) {
            View dot = new View(getContext());
            LayoutParams lp = new LayoutParams(Ui.dp(getContext(), 12), Ui.dp(getContext(), 12));
            lp.setMargins(Ui.dp(getContext(), 9), 0, Ui.dp(getContext(), 9), 0);
            dot.setLayoutParams(lp);
            dotViews[i] = dot;
            dots.addView(dot);
        }
    }

    private LinearLayout buildKeypad() {
        LinearLayout pad = new LinearLayout(getContext());
        pad.setOrientation(VERTICAL);
        pad.setGravity(Gravity.CENTER_HORIZONTAL);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"", "0", "⌫"}};
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(getContext());
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER_HORIZONTAL);
            for (String label : row) {
                line.addView(buildKey(label));
            }
            pad.addView(line);
        }
        return pad;
    }

    private View buildKey(String label) {
        TextView b = Ui.text(getContext(), label, 22f, Ui.TEXT);
        b.setGravity(Gravity.CENTER);
        // Backspace reads as an action, not a value, so it gets no keycap.
        boolean filled = !label.isEmpty() && !label.equals("⌫");
        b.setBackground(label.isEmpty() ? null
                : Ui.pressable(filled ? Ui.outlined(Ui.SURFACE, Ui.LINE, 1, getContext())
                        : Ui.square(Ui.BG)));

        LayoutParams lp = new LayoutParams(Ui.dp(getContext(), 80), Ui.dp(getContext(), 58));
        lp.setMargins(Ui.dp(getContext(), 7), Ui.dp(getContext(), 7),
                Ui.dp(getContext(), 7), Ui.dp(getContext(), 7));
        b.setLayoutParams(lp);

        if (label.isEmpty()) {
            b.setEnabled(false);
        } else if (label.equals("⌫")) {
            b.setTextColor(Ui.TEXT_DIM);
            b.setClickable(true);
            b.setOnClickListener(v -> {
                if (entered.length() > 0) entered.deleteCharAt(entered.length() - 1);
                refresh();
            });
        } else {
            b.setClickable(true);
            b.setOnClickListener(v -> onDigit(label));
        }
        return b;
    }

    private void onDigit(String digit) {
        if (lock.isLockedOut()) return;
        if (entered.length() >= Lock.PIN_LENGTH) return;
        entered.append(digit);
        refresh();
        if (entered.length() == Lock.PIN_LENGTH) {
            // Let the last dot paint before the PBKDF2 hash blocks the UI thread.
            postDelayed(this::submit, 90);
        }
    }

    private void submit() {
        String pin = entered.toString();
        entered.setLength(0);

        if (!lock.isConfigured()) {
            if (firstEntry == null) {
                firstEntry = pin;
                title.setText("Confirm your PIN");
                hint.setTextColor(Ui.TEXT_DIM);
                hint.setText("Enter the same four digits again");
                refresh();
                return;
            }
            if (!firstEntry.equals(pin)) {
                firstEntry = null;
                title.setText("Set a 4-digit PIN");
                hint.setTextColor(Ui.ERR);
                hint.setText("Those did not match. Start again.");
                refresh();
                return;
            }
            lock.setPin(pin);
            firstEntry = null;
            unlock();
            return;
        }

        if (lock.verify(pin)) {
            unlock();
        } else {
            hint.setTextColor(Ui.ERR);
            hint.setText(lock.isLockedOut()
                    ? "Too many attempts. Reinstall to reset."
                    : "Wrong PIN. " + lock.attemptsRemaining() + " attempts left.");
            refresh();
        }
    }

    private void unlock() {
        cancelBiometric();
        hint.setTextColor(Ui.TEXT_DIM);
        listener.onUnlocked();
    }

    private void refresh() {
        if (!lock.isConfigured()) {
            title.setText(firstEntry == null ? "Set a 4-digit PIN" : "Confirm your PIN");
            biometricButton.setVisibility(GONE);
        } else {
            title.setText("Enter PIN");
            biometricButton.setVisibility(biometricAvailable() ? VISIBLE : GONE);
        }
        // The field goes red with the hint, as Omarchy's does on a wrong password.
        boolean error = hint.getCurrentTextColor() == Ui.ERR;
        dots.setBackground(Ui.outlined(Ui.BG, error ? Ui.ERR : Ui.ACCENT, 3, getContext()));
        for (int i = 0; i < dotViews.length; i++) {
            dotViews[i].setBackground(i < entered.length()
                    ? Ui.square(Ui.ACCENT)
                    : Ui.outlined(Ui.BG, Ui.LINE, 1, getContext()));
        }
    }

    // ------------------------------------------------------------------ //
    boolean biometricAvailable() {
        if (!lock.biometricEnabled() || !lock.isConfigured()) return false;
        try {
            BiometricManager bm = getContext().getSystemService(BiometricManager.class);
            if (bm == null) return false;
            return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            // Logged, not swallowed: a silent false here is how a missing
            // USE_BIOMETRIC permission hid the fingerprint path for a whole release.
            android.util.Log.w("FoldDeck/Lock", "biometric check failed", t);
            return false;
        }
    }

    /** Called when the overlay becomes visible; offers the fingerprint straight away. */
    void onShown() {
        entered.setLength(0);
        hint.setTextColor(Ui.TEXT_DIM);
        hint.setText(lock.isConfigured() ? "" : "You will need this each time the app opens");
        refresh();
        if (biometricAvailable() && !lock.isLockedOut()) promptBiometric();
    }

    private void promptBiometric() {
        if (!biometricAvailable() || lock.isLockedOut()) return;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        cancelBiometric();
        cancel = new CancellationSignal();

        // Never let the fingerprint path take the app down: it is a convenience
        // over the keypad, which is always there. Vendor BiometricPrompt
        // implementations throw for their own reasons (no enrolment, sensor
        // busy, prompt shown too early), and none of that is worth a crash.
        try {
            BiometricPrompt prompt = new BiometricPrompt.Builder(getContext())
                    .setTitle("Unlock FoldDeck")
                    .setSubtitle("Fingerprint, or use your PIN")
                    .setNegativeButton("Use PIN", getContext().getMainExecutor(),
                            (dialog, which) -> { /* fall through to the keypad */ })
                    .build();

            prompt.authenticate(cancel, getContext().getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult r) {
                            unlock();
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence msg) {
                            // Dismissed, or the sensor is unavailable. The keypad
                            // is already on screen, so there is nothing to do.
                        }
                    });
        } catch (Throwable t) {
            // Hide it for now, but leave the setting alone: a sensor that is busy
            // once should not switch fingerprint unlock off for good.
            android.util.Log.w("FoldDeck/Lock", "biometric unavailable, using PIN", t);
            biometricButton.setVisibility(GONE);
        }
    }

    void cancelBiometric() {
        if (cancel != null && !cancel.isCanceled()) cancel.cancel();
        cancel = null;
    }
}
