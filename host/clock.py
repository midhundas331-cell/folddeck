#!/usr/bin/env python3
"""A millisecond clock to film for the glass-to-glass measurement.

Point a 240fps camera at the laptop and the phone at once. In a single video
frame, read this clock directly and read it again inside the mirrored image on
the phone. The difference is the end-to-end latency — no instrumentation, no
clock synchronisation, and no need to trust either machine's timers.

GTK rather than a terminal: it needs a large, high-contrast, fixed-width readout
that repaints faster than the display refreshes, and xterm isn't installed here.

The alternating colour bar matters more than it looks. At 240fps you get ~4
camera frames per 60Hz display refresh, so consecutive frames often show an
identical time string; the bar flipping tells you the panel actually repainted
rather than the camera catching the same frame twice.
"""
import sys
import time

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk  # noqa: E402

CSS = """
window { background-color: #000000; }
#time { color: #ffffff; font-family: monospace; font-size: 64pt; font-weight: bold; }
/* Both states must be clearly visible on camera — an "off" state of black on a
   black window is indistinguishable from the background, which is exactly the
   ambiguity the bar exists to remove. */
#bar-on  { background-color: #ffffff; }
#bar-off { background-color: #ff0000; }
"""


class ClockWindow(Gtk.Window):
    def __init__(self):
        super().__init__(title="folddeck-clock")
        self.set_default_size(760, 260)
        self.set_keep_above(True)

        provider = Gtk.CssProvider()
        provider.load_from_data(CSS.encode())
        Gtk.StyleContext.add_provider_for_screen(
            self.get_screen(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.label = Gtk.Label()
        self.label.set_name("time")
        self.bar = Gtk.Box()
        self.bar.set_name("bar-off")
        self.bar.set_size_request(-1, 60)
        box.pack_start(self.label, True, True, 0)
        box.pack_start(self.bar, False, False, 0)
        self.add(box)

        self.flip = False
        self.connect("destroy", Gtk.main_quit)

        # 5ms: comfortably faster than a 60Hz (16.7ms) or 120Hz (8.3ms) repaint,
        # so the displayed value is never the bottleneck in the measurement.
        GLib.timeout_add(5, self.tick)

    def tick(self):
        now = time.time()
        ms = int((now % 1) * 1000)
        self.label.set_text(f"{time.strftime('%H:%M:%S', time.localtime(now))}.{ms:03d}")
        self.flip = not self.flip
        self.bar.set_name("bar-on" if self.flip else "bar-off")
        return True


def main() -> int:
    win = ClockWindow()
    win.show_all()
    Gtk.main()
    return 0


if __name__ == "__main__":
    sys.exit(main())
