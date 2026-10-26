#!/usr/bin/env bash
# Give FoldDeck direct framebuffer capture (KMS): a private ffmpeg copy that only
# this user can run, holding CAP_SYS_ADMIN. Re-run after an ffmpeg upgrade.
# Undo: rm ~/.local/lib/folddeck/ffmpeg-kms && systemctl --user restart folddeck
set -euo pipefail
dest="$HOME/.local/lib/folddeck/ffmpeg-kms"
mkdir -p "$(dirname "$dest")"
install -m 0700 "$(command -v ffmpeg)" "$dest"
# sudo needs a terminal for the password; pkexec asks through a desktop dialog.
if [ -t 0 ]; then sudo setcap cap_sys_admin+ep "$dest"; else pkexec setcap cap_sys_admin+ep "$dest"; fi
getcap "$dest"
systemctl --user restart folddeck
echo "done; check: journalctl --user -u folddeck -n 30 | grep capture"
