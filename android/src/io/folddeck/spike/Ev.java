package io.folddeck.spike;

/**
 * Linux evdev keycodes and a ThinkPad-style keyboard layout.
 *
 * We send scancodes, never characters. The host injects them into /dev/uinput,
 * so the laptop's own XKB layout, compose key, dead keys, Ctrl+Alt+Fn and every
 * application shortcut behave exactly as with a real USB keyboard.
 *
 * That is also why the shifted legends here are decoration only: pressing Shift
 * then 2 produces whatever the *host's* layout puts there, which on a UK
 * keyboard is " and not @. The legends assume US ANSI because that is what the
 * shape is modelled on.
 */
final class Ev {
    static final int ESC = 1, K1 = 2, K2 = 3, K3 = 4, K4 = 5, K5 = 6, K6 = 7, K7 = 8,
            K8 = 9, K9 = 10, K0 = 11, MINUS = 12, EQUAL = 13, BACKSPACE = 14, TAB = 15,
            Q = 16, W = 17, E = 18, R = 19, T = 20, Y = 21, U = 22, I = 23, O = 24, P = 25,
            LEFTBRACE = 26, RIGHTBRACE = 27, ENTER = 28, LEFTCTRL = 29,
            A = 30, S = 31, D = 32, F = 33, G = 34, H = 35, J = 36, K = 37, L = 38,
            SEMICOLON = 39, APOSTROPHE = 40, GRAVE = 41, LEFTSHIFT = 42, BACKSLASH = 43,
            Z = 44, X = 45, C = 46, V = 47, B = 48, N = 49, M = 50,
            COMMA = 51, DOT = 52, SLASH = 53, RIGHTSHIFT = 54,
            LEFTALT = 56, SPACE = 57, CAPSLOCK = 58,
            F1 = 59, F2 = 60, F3 = 61, F4 = 62, F5 = 63, F6 = 64,
            F7 = 65, F8 = 66, F9 = 67, F10 = 68, F11 = 87, F12 = 88,
            SYSRQ = 99, RIGHTCTRL = 97, RIGHTALT = 100,
            HOME = 102, UP = 103, PAGEUP = 104, LEFT = 105, RIGHT = 106,
            END = 107, DOWN = 108, PAGEDOWN = 109, INSERT = 110, DELETE = 111,
            LEFTMETA = 125, RIGHTMETA = 126;

    // Fn-row alternates.
    //
    // Every code here must be <= 247, not merely <= 255. X11 keycodes are capped
    // at 255 and X adds 8 to the evdev code, so evdev 248 and above can never
    // reach an X client. That rules out KEY_MICMUTE (248), which is why Fn+F4
    // carries no alternate even though a real ThinkPad mutes the mic there.
    static final int MUTE = 113, VOLUMEDOWN = 114, VOLUMEUP = 115,
            NEXTSONG = 163, PLAYPAUSE = 164, PREVIOUSSONG = 165,
            BRIGHTNESSDOWN = 224, BRIGHTNESSUP = 225;

    /** Highest evdev code that survives translation to an X11 keycode. */
    static final int MAX_X11_CODE = 247;

    /** Sentinel for Fn: handled entirely on the phone, never sent to the host. */
    static final int LOCAL_FN = 0;

    private Ev() { }

    static boolean isModifier(int code) {
        return code == LEFTSHIFT || code == RIGHTSHIFT || code == LEFTCTRL || code == RIGHTCTRL
                || code == LEFTALT || code == RIGHTALT || code == LEFTMETA || code == RIGHTMETA;
    }

    /**
     * One key. {@code width} is in "u" units, where 1u is a standard alpha key.
     *
     * {@code shift} is the legend printed above the base one. {@code fnCode} and
     * {@code fnLabel} are the Fn-layer function, as on a real ThinkPad.
     */
    static final class Key {
        final int code;
        final String label;
        final String shift;
        final float width;
        final int fnCode;
        final String fnLabel;

        Key(int code, String label, String shift, float width, int fnCode, String fnLabel) {
            this.code = code;
            this.label = label;
            this.shift = shift;
            this.width = width;
            this.fnCode = fnCode;
            this.fnLabel = fnLabel;
        }
    }

    private static Key k(int code, String label) {
        return new Key(code, label, null, 1f, 0, null);
    }

    private static Key k(int code, String label, float w) {
        return new Key(code, label, null, w, 0, null);
    }

    /** A key with a shifted legend above the base one. */
    private static Key d(int code, String label, String shift) {
        return new Key(code, label, shift, 1f, 0, null);
    }

    /** A function key with an Fn-layer alternate. Its legend is the Nerd Font icon
     *  Omarchy's own OSD shows for that key (shell/plugins/osd/OsdModel.js). */
    private static Key fn(int code, String label, int fnCode, String fnLabel) {
        return new Key(code, label, null, 1f, fnCode, fnLabel);
    }

    /**
     * Six-row ThinkPad layout.
     *
     * ThinkPad-specific choices: Fn sits to the LEFT of Ctrl in the bottom-left
     * corner, the F-row carries Fn-layer media alternates, and the arrows form an
     * inverted T with Up directly above Down. The right-hand column holds the
     * navigation keys.
     *
     * Every row sums to exactly ROW_UNITS so the columns line up, which is what
     * makes a keyboard read as a keyboard rather than as rows of buttons.
     */
    static final Key[][] LAYOUT = {
            {
                k(ESC, "Esc"),
                fn(F1, "F1", MUTE, ""), fn(F2, "F2", VOLUMEDOWN, ""),
                fn(F3, "F3", VOLUMEUP, ""), k(F4, "F4"),
                fn(F5, "F5", BRIGHTNESSDOWN, "󰍹 -"), fn(F6, "F6", BRIGHTNESSUP, "󰍹 +"),
                k(F7, "F7"), k(F8, "F8"), k(F9, "F9"),
                fn(F10, "F10", PREVIOUSSONG, "󰒮"), fn(F11, "F11", PLAYPAUSE, "󰐊"),
                fn(F12, "F12", NEXTSONG, "󰒭"),
                k(SYSRQ, "PrtSc"), k(INSERT, "Ins"), k(DELETE, "Del"),
            },
            {
                d(GRAVE, "`", "~"), d(K1, "1", "!"), d(K2, "2", "@"), d(K3, "3", "#"),
                d(K4, "4", "$"), d(K5, "5", "%"), d(K6, "6", "^"), d(K7, "7", "&"),
                d(K8, "8", "*"), d(K9, "9", "("), d(K0, "0", ")"),
                d(MINUS, "-", "_"), d(EQUAL, "=", "+"),
                k(BACKSPACE, "⌫", 2f), k(HOME, "Home"),
            },
            {
                k(TAB, "Tab", 1.5f),
                k(Q, "Q"), k(W, "W"), k(E, "E"), k(R, "R"), k(T, "T"),
                k(Y, "Y"), k(U, "U"), k(I, "I"), k(O, "O"), k(P, "P"),
                d(LEFTBRACE, "[", "{"), d(RIGHTBRACE, "]", "}"),
                new Key(BACKSLASH, "\\", "|", 1.5f, 0, null),
                k(END, "End"),
            },
            {
                k(CAPSLOCK, "Caps", 1.75f),
                k(A, "A"), k(S, "S"), k(D, "D"), k(F, "F"), k(G, "G"),
                k(H, "H"), k(J, "J"), k(K, "K"), k(L, "L"),
                d(SEMICOLON, ";", ":"), d(APOSTROPHE, "'", "\""),
                k(ENTER, "Enter", 2.25f), k(PAGEUP, "PgUp"),
            },
            {
                k(LEFTSHIFT, "Shift", 2.25f),
                k(Z, "Z"), k(X, "X"), k(C, "C"), k(V, "V"), k(B, "B"), k(N, "N"), k(M, "M"),
                d(COMMA, ",", "<"), d(DOT, ".", ">"), d(SLASH, "/", "?"),
                k(RIGHTSHIFT, "Shift", 1.75f), k(UP, "↑"), k(PAGEDOWN, "PgDn"),
            },
            {
                k(LOCAL_FN, "Fn", 1.25f), k(LEFTCTRL, "Ctrl", 1.25f),
                k(LEFTMETA, "◆", 1.25f), k(LEFTALT, "Alt", 1.25f),
                k(SPACE, "", 5.5f),
                k(RIGHTALT, "Alt", 1.25f), k(RIGHTCTRL, "Ctrl", 1.25f),
                k(LEFT, "←"), k(DOWN, "↓"), k(RIGHT, "→"),
            },
    };

    /** Every row sums to this. */
    static final float ROW_UNITS = 16f;
}
