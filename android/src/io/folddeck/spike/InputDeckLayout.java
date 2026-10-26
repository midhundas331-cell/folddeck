package io.folddeck.spike;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;

/** Responsive owner of the on-screen keyboard, dedicated trackpad and docked shortcuts. */
final class InputDeckLayout extends ViewGroup {
    static final class Box {
        final int x, y, w, h;
        Box(int x, int y, int w, int h) {
            this.x = x; this.y = y; this.w = Math.max(0, w); this.h = Math.max(0, h);
        }
        int right() { return x + w; }
        int bottom() { return y + h; }
    }

    static final class Boxes {
        final Box keyboard, trackpad, shortcut;
        Boxes(Box keyboard, Box trackpad, Box shortcut) {
            this.keyboard = keyboard;
            this.trackpad = trackpad;
            this.shortcut = shortcut;
        }
    }

    private View keyboard;
    private View trackpad;
    private View shortcut;
    private boolean keyboardVisible = true;
    private boolean trackpadVisible;
    private boolean sideBySide;
    private boolean portraitConsole;
    private boolean padLeft;
    private boolean split;
    private float padScale = 1f;
    private boolean inputEnabled = true;
    private final int gap;
    private final float density;

    InputDeckLayout(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        gap = Ui.dp(context, 8);
        // KeyboardView and ShortcutBar paint their local ground with drawColor().
        // Keep each custom canvas clipped to its box so reparented/elevated
        // controls cannot erase sibling regions in the shared hardware layer.
        setClipChildren(clipsControlSurfaces());
    }

    void setKeyboardView(View view) {
        if (keyboard != null) removeView(keyboard);
        keyboard = view;
        addView(view);
    }

    void setTrackpadView(View view) {
        if (trackpad != null) removeView(trackpad);
        trackpad = view;
        addView(view);
    }

    void setShortcutView(View view) {
        if (shortcut != null && shortcut.getParent() == this) removeView(shortcut);
        shortcut = view;
        if (view.getParent() != this) addView(view);
    }

    void clearShortcutView() {
        if (shortcut != null && shortcut.getParent() == this) removeView(shortcut);
        shortcut = null;
    }

    void configure(boolean showKeyboard, boolean showTrackpad, boolean horizontalSplit,
                   boolean portraitShelf, boolean trackpadLeft, float trackpadSize,
                   boolean splitKeyboard) {
        split = splitKeyboard;
        padLeft = trackpadLeft;
        padScale = trackpadSize;
        keyboardVisible = showKeyboard;
        trackpadVisible = showTrackpad;
        sideBySide = horizontalSplit;
        portraitConsole = portraitShelf;
        if (keyboard != null) keyboard.setVisibility(showKeyboard ? VISIBLE : GONE);
        if (trackpad != null) trackpad.setVisibility(showTrackpad ? VISIBLE : GONE);
        if (shortcut != null) shortcut.setVisibility(portraitShelf ? VISIBLE : GONE);
        requestLayout();
    }

    void setInputEnabled(boolean enabled) {
        inputEnabled = enabled;
        if (!enabled) cancelPendingInputEvents();
    }

    static boolean blocksInput(boolean enabled) {
        return !enabled;
    }

    static boolean clipsControlSurfaces() {
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return blocksInput(inputEnabled) || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return blocksInput(inputEnabled) || super.onTouchEvent(event);
    }

    /** Width of the trackpad channel in a split board. */
    static int splitChannel(int keyboardWidth, float padScale, float density) {
        int want = Math.round(keyboardWidth * 0.19f * padScale);
        return clamp(want, Math.round(120f * density), Math.round(keyboardWidth * 0.34f));
    }

    static Boxes computeBoxes(int width, int height, int gap,
                              boolean keyboard, boolean trackpad,
                              boolean sideBySide, boolean portraitConsole, boolean padLeft,
                              float padScale, float density) {
        return computeBoxes(width, height, gap, keyboard, trackpad, sideBySide,
                portraitConsole, padLeft, padScale, density, false);
    }

    /**
     * With {@code split}, the keyboard keeps the box it would have alone and the
     * trackpad takes a channel down its middle; the keyboard is told the
     * channel's width and lays its halves either side of it.
     */
    static Boxes computeBoxes(int width, int height, int gap,
                              boolean keyboard, boolean trackpad,
                              boolean sideBySide, boolean portraitConsole, boolean padLeft,
                              float padScale, float density, boolean split) {
        if (split && keyboard && trackpad) {
            Boxes alone = computeBoxes(width, height, gap, true, false, false,
                    portraitConsole, padLeft, padScale, density, false);
            Box kb = alone.keyboard;
            int channel = splitChannel(kb.w, padScale, density);
            return new Boxes(kb, new Box(kb.x + (kb.w - channel) / 2, kb.y, channel, kb.h),
                    alone.shortcut);
        }
        Box none = new Box(0, 0, 0, 0);
        if (!keyboard && !trackpad) return new Boxes(none, none, none);

        if (portraitConsole && keyboard) {
            int shelfMin = Math.round(140f * density);
            int shelfMax = Math.round(210f * density);
            int shelfH = clamp(Math.round(height * 0.40f), shelfMin,
                    Math.min(shelfMax, Math.max(0, height - gap)));
            int keyY = shelfH + gap;
            int span = Math.max(0, width - gap);
            int half = trackpad ? clamp(Math.round(span * 0.5f * padScale),
                    Math.round(span * 0.3f), Math.round(span * 0.7f)) : 0;
            int rest = trackpad ? width - half - gap : width;
            Box pad = trackpad ? new Box(padLeft ? 0 : rest + gap, 0, half, shelfH) : none;
            Box shortcuts = new Box(trackpad && padLeft ? half + gap : 0, 0, rest, shelfH);
            return new Boxes(new Box(0, keyY, width, height - keyY), pad, shortcuts);
        }

        if (keyboard && !trackpad) {
            return new Boxes(new Box(0, 0, width, height), none, none);
        }
        if (!keyboard) {
            return new Boxes(none, new Box(0, 0, width, height), none);
        }

        if (sideBySide) {
            int min = Math.round(220f * density * padScale);
            int max = Math.round(300f * density * padScale);
            int padW = clamp(Math.round(width * 0.28f * padScale), min,
                    Math.min(max, Math.min(width / 2, width - gap)));
            int keyW = Math.max(0, width - gap - padW);
            return padLeft
                    ? new Boxes(new Box(padW + gap, 0, keyW, height),
                            new Box(0, 0, padW, height), none)
                    : new Boxes(new Box(0, 0, keyW, height),
                            new Box(keyW + gap, 0, padW, height), none);
        }

        int min = Math.round(104f * density * padScale);
        int max = Math.round(160f * density * padScale);
        int padH = clamp(Math.round(height * 0.32f * padScale), min,
                Math.min(max, Math.round(height * 0.6f)));
        int keyH = Math.max(0, height - gap - padH);
        return new Boxes(new Box(0, 0, width, keyH),
                new Box(0, keyH + gap, width, padH), none);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        Boxes boxes = computeBoxes(width, height, gap, keyboardVisible,
                trackpadVisible, sideBySide, portraitConsole, padLeft, padScale, density, split);
        boolean splitShown = split && keyboardVisible && trackpadVisible;
        if (keyboard instanceof KeyboardView) {
            KeyboardView kb = (KeyboardView) keyboard;
            kb.setSplitGap(splitShown ? boxes.trackpad.w : 0);
            if (trackpad instanceof TrackpadDeck) {
                // In the channel, the pad's zones sit exactly one key gap in, so
                // it reads as part of the board rather than a panel laid on it.
                ((TrackpadDeck) trackpad).setInset(splitShown
                        ? kb.keyGap(boxes.keyboard.w) : Ui.dp(getContext(), 6));
            }
        }
        if (keyboard != null && keyboardVisible) {
            keyboard.measure(exact(boxes.keyboard.w), exact(boxes.keyboard.h));
        }
        if (trackpad != null && trackpadVisible) {
            Box pad = padBox(boxes);
            trackpad.measure(exact(pad.w), exact(pad.h));
        }
        if (shortcut != null && portraitConsole) {
            shortcut.measure(exact(boxes.shortcut.w), exact(boxes.shortcut.h));
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        Boxes boxes = computeBoxes(r - l, b - t, gap, keyboardVisible,
                trackpadVisible, sideBySide, portraitConsole, padLeft, padScale, density, split);
        if (keyboard != null && keyboardVisible) {
            Box box = boxes.keyboard;
            keyboard.layout(box.x, box.y, box.right(), box.bottom());
        }
        if (trackpad != null && trackpadVisible) {
            Box box = padBox(boxes);
            trackpad.layout(box.x, box.y, box.right(), box.bottom());
        }
        if (shortcut != null && portraitConsole) {
            Box box = boxes.shortcut;
            shortcut.layout(box.x, box.y, box.right(), box.bottom());
        }
    }

    /** Split: the pad fills exactly between the halves the keyboard laid out. */
    private Box padBox(Boxes boxes) {
        if (!(split && keyboardVisible && trackpadVisible && keyboard instanceof KeyboardView)) {
            return boxes.trackpad;
        }
        float[] e = ((KeyboardView) keyboard).splitEdges(boxes.keyboard.w);
        int left = Math.round(e[0]), right = Math.round(e[1]);
        return new Box(boxes.keyboard.x + left, boxes.trackpad.y, Math.max(0, right - left),
                boxes.trackpad.h);
    }

    private static int exact(int size) {
        return MeasureSpec.makeMeasureSpec(Math.max(0, size), MeasureSpec.EXACTLY);
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return Math.max(0, max);
        return Math.max(min, Math.min(max, value));
    }
}
