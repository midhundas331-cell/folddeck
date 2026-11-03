#!/usr/bin/env bash
# Run after installing v4l2loopback-dkms and headers for the running kernel.
set -euo pipefail
if (( EUID != 0 )); then
  echo 'Run with sudo (or pkexec from a graphical session).'
  exit 1
fi
modinfo v4l2loopback >/dev/null
modules=/etc/modules-load.d/folddeck-camera.conf
options=/etc/modprobe.d/folddeck-camera.conf
for file in "$modules" "$options"; do
  if [[ -e "$file" ]] && ! grep -q '^# Folddeck virtual camera$' "$file"; then
    echo "Refusing to replace unrelated configuration: $file" >&2
    exit 1
  fi
done
install -d /etc/modules-load.d /etc/modprobe.d
cat > "$modules" <<'EOF'
# Folddeck virtual camera
v4l2loopback
EOF
cat > "$options" <<'EOF'
# Folddeck virtual camera
options v4l2loopback video_nr=10 card_label="Folddeck Phone" exclusive_caps=1
EOF
modprobe v4l2loopback video_nr=10 'card_label=Folddeck Phone' exclusive_caps=1
echo 'Folddeck Phone webcam is configured for this boot and future boots.'
