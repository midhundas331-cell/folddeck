package io.folddeck.spike;

import java.util.ArrayList;
import java.util.List;

/** Aggregate button ownership so one input source cannot release another. */
public class PointerButtonsCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        List<String> events = new ArrayList<>();
        PointerButtons buttons = new PointerButtons(
                (button, down) -> events.add(button + ":" + down));

        buttons.set(PointerButtons.SOURCE_TRACKPAD, 1, true);
        buttons.set(PointerButtons.SOURCE_TRACKPAD, 1, true);
        check(events.equals(List.of("1:true")), "duplicate source press emits one down");

        buttons.set(PointerButtons.SOURCE_MOUSE, 1, true);
        buttons.set(PointerButtons.SOURCE_MOUSE, 1, false);
        check(events.equals(List.of("1:true")), "mouse cannot release held trackpad button");

        buttons.set(PointerButtons.SOURCE_TRACKPAD, 1, false);
        check(events.equals(List.of("1:true", "1:false")), "last owner emits release");

        buttons.set(PointerButtons.SOURCE_TOUCH, 1, true);
        buttons.set(PointerButtons.SOURCE_MOUSE, 2, true);
        buttons.releaseSource(PointerButtons.SOURCE_MOUSE);
        check(events.contains("2:false"), "source release frees its own button");
        check(buttons.ownersForTest(1) == PointerButtons.SOURCE_TOUCH,
                "source release preserves other source");

        buttons.releaseAll();
        check(events.get(events.size() - 1).equals("1:false"), "releaseAll frees remaining button");
        System.out.println("PointerButtonsCheck ok");
    }
}
