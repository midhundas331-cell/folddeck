#!/usr/bin/env bash
# Install FoldDeck as a session service that starts automatically at login.
#
# Two pieces, because neither alone is enough on this desktop:
#
#   systemd user unit  gives restart-on-failure and journald logs
#   autostart .desktop starts that unit from inside the graphical session, and
#                      hands it DISPLAY/XAUTHORITY
#
# A unit bound to graphical-session.target would be the obvious approach and
# does not work here: Cinnamon under lightdm never activates that target, so
# the unit would sit inactive forever. Checked with `systemctl --user is-active
# graphical-session.target`.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
UNIT_DIR="$HOME/.config/systemd/user"
AUTOSTART_DIR="$HOME/.config/autostart"
UNIT="$UNIT_DIR/folddeck.service"
LAUNCHER="$HERE/autostart.sh"
DESKTOP="$AUTOSTART_DIR/folddeck.desktop"

mkdir -p "$UNIT_DIR" "$AUTOSTART_DIR"

echo "installing systemd user unit -> $UNIT"
install -m 0644 "$HERE/folddeck.service" "$UNIT"

echo "writing session launcher    -> $LAUNCHER"
cat > "$LAUNCHER" <<'LAUNCH'
#!/usr/bin/env bash
# Started by ~/.config/autostart/folddeck.desktop, inside the graphical session.
#
# x11grab needs DISPLAY and XAUTHORITY, and the systemd user manager does not
# inherit them. Push them across explicitly rather than relying on
# import-environment, which silently imports nothing if the session did not
# export XAUTHORITY (Cinnamon often doesn't).
export DISPLAY="${DISPLAY:-:0}"
export XAUTHORITY="${XAUTHORITY:-$HOME/.Xauthority}"
systemctl --user set-environment DISPLAY="$DISPLAY" XAUTHORITY="$XAUTHORITY"
systemctl --user restart folddeck.service
LAUNCH
chmod +x "$LAUNCHER"

echo "writing autostart entry     -> $DESKTOP"
cat > "$DESKTOP" <<DESK
[Desktop Entry]
Type=Application
Name=FoldDeck Host
Comment=Stream this screen to the Fold
Exec=$LAUNCHER
Terminal=false
NoDisplay=true
X-GNOME-Autostart-enabled=true
DESK

systemctl --user daemon-reload
echo
echo "installed."
echo
echo "  start now:     $LAUNCHER"
echo "  status:        systemctl --user status folddeck"
echo "  logs:          journalctl --user -u folddeck -f"
echo "  stop:          systemctl --user stop folddeck"
echo "  disable:       rm $DESKTOP"
echo
echo "It will start automatically at your next login."
