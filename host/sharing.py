"""Phone clipboard, PCM microphone and JPEG webcam over the paired TLS stream.

The input reader validates and buffers bounded pending samples. Device and
clipboard operations run on a worker; disconnect always destroys its resources.
"""
from __future__ import annotations

import glob
import os
import select
import subprocess
import threading
import time

import audio
import demand

UUID = b"FOLDDECK-SHARE-2"
MAX_PACKET, MAX_TEXT = 256 * 1024, 64 * 1024
HELLO, STATUS, CONFIG, CLIPBOARD, MIC, CAMERA, READY, DEMAND = range(8)
MIC_SINK = "folddeck_phone"


def nal(kind, data):
    payload = UUID + bytes([kind]) + data
    size = b"\xff" * (len(payload) // 255) + bytes([len(payload) % 255])
    return b"\0\0\0\1\x06" + audio.escape(b"\x05" + size + payload) + b"\x80"


def validate(payload):
    if not payload or len(payload) > MAX_PACKET:
        raise ValueError("sharing packet size")
    kind, data = payload[0], payload[1:]
    if kind == CONFIG:
        if len(data) != 5 or data[4] & ~7 or not int.from_bytes(data[:4], "big"):
            raise ValueError("sharing config")
    elif kind == CLIPBOARD:
        if len(data) > MAX_TEXT:
            raise ValueError("clipboard size")
        data.decode("utf-8", errors="strict")
    elif kind == MIC:
        if len(data) <= 4 or len(data) > 9604 or len(data) % 2:
            raise ValueError("PCM size")
    elif kind == CAMERA:
        if len(data) < 8 or data[4:6] != b"\xff\xd8" or not data.endswith(b"\xff\xd9"):
            raise ValueError("JPEG frame")
    else:
        raise ValueError("sharing subtype")
    return kind, data


def camera_device():
    for path in glob.glob("/sys/class/video4linux/video*/name"):
        with open(path) as f:
            if f.read().strip() == "Folddeck Phone":
                return "/dev/" + path.split("/")[-2]
    raise OSError("Camera needs v4l2loopback with card_label='Folddeck Phone'")


def pactl(*args):
    result = subprocess.run(["pactl", *args], capture_output=True, text=True, timeout=3, check=True)
    return result.stdout.strip()


def cleanup_stale():
    for line in pactl("list", "short", "modules").splitlines():
        if f"sink_name={MIC_SINK}" in line.split() or "source_name=folddeck_mic" in line.split():
            pactl("unload-module", line.split()[0])
    for obj in demand.snapshot():
        if demand.props(obj).get("node.name") == "folddeck_camera":
            demand.command("pw-cli", "destroy", str(obj["id"]))
    for media, name in (("audio", "folddeck_mic"), ("video", "folddeck_camera")):
        key = "default.configured." + media + ".source"
        if demand.configured(demand.snapshot(), key) == {"name": name}:
            demand.set_default(key, None)


def clipboard_read():
    """Bound memory even if another app supplies an enormous clipboard."""
    proc = subprocess.Popen(["wl-paste", "--no-newline", "--type", "text"], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL)
    data = bytearray()
    deadline = time.monotonic() + 0.5
    try:
        while time.monotonic() < deadline:
            if not select.select([proc.stdout], [], [], 0.05)[0]:
                continue
            chunk = os.read(proc.stdout.fileno(), min(8192, MAX_TEXT + 1 - len(data)))
            if not chunk:
                if proc.wait(timeout=0.2) != 0:
                    return None
                return bytes(data).decode("utf-8").encode("utf-8")
            data.extend(chunk)
            if len(data) > MAX_TEXT:
                return None
        return None
    finally:
        if proc.poll() is None:
            proc.kill()
        proc.wait()
        proc.stdout.close()


class Device:
    def __init__(self, kind):
        self.modules = []
        self.proc = None
        self.default = self.usage = self.node = None
        self.black = None
        try:
            if kind == MIC:
                self.modules.append(pactl("load-module", "module-null-sink", f"sink_name={MIC_SINK}",
                                          "rate=48000", "channels=1", "sink_properties=device.description=Folddeck-Phone-Transport"))
                self.modules.append(pactl("load-module", "module-remap-source", f"master={MIC_SINK}.monitor",
                                          "source_name=folddeck_mic", "source_properties=device.description=Folddeck-Phone-Microphone"))
                # Phone PCM arrives around 5% of full scale; lift it to normal speech level.
                pactl("set-source-volume", "folddeck_mic", "250%")
                cmd = ["pacat", "--playback", "--device=" + MIC_SINK, "--raw", "--format=s16le",
                       "--rate=48000", "--channels=1", "--latency-msec=40", "--client-name=Folddeck-Phone-Microphone"]
            else:
                # ponytail: 640x480 JPEG at 10 fps; hardware H.264 upstream if higher quality is needed.
                demand.camera_rate(camera_device())
                cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-threads", "1",
                       "-probesize", "32", "-analyzeduration", "0", "-max_pixels", "1048576",
                       "-f", "mjpeg", "-framerate", "10", "-i", "pipe:0", "-an",
                       "-vf", "scale=640:480:force_original_aspect_ratio=decrease,pad=640:480:(ow-iw)/2:(oh-ih)/2",
                       "-pix_fmt", "yuyv422", "-threads", "1", "-f", "v4l2", camera_device()]
            self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                         bufsize=0)
            os.set_blocking(self.proc.stdin.fileno(), False)
            if kind == CAMERA:
                self.black = subprocess.check_output(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                    "color=black:s=640x480", "-frames:v", "1", "-f", "mjpeg", "pipe:1"], timeout=3)
                # Prime the decoder before advertising capture capabilities.
                for _ in range(20):
                    self.write(self.black)
                self.usage = demand.CameraUsage(camera_device())
                self.node = demand.CameraNode(camera_device())
            self.default = demand.Default("audio" if kind == MIC else "video",
                                          "folddeck_mic" if kind == MIC else "folddeck_camera")
        except Exception:
            self.close()
            raise

    def write(self, data):
        view = memoryview(data)
        deadline = time.monotonic() + 0.2
        while view:
            if self.proc.poll() is not None or time.monotonic() >= deadline:
                raise OSError("Phone input device stopped accepting frames")
            if select.select([], [self.proc.stdin], [], 0.02)[1]:
                try:
                    view = view[os.write(self.proc.stdin.fileno(), view):]
                except BlockingIOError:
                    pass

    def close(self):
        for resource in (self.default, self.node, self.usage):
            if resource:
                try:
                    resource.close()
                except (OSError, ValueError, subprocess.SubprocessError):
                    pass
        self.default = self.node = self.usage = None
        if self.proc:
            if self.proc.poll() is None:
                self.proc.kill()
            self.proc.wait()
            self.proc.stdin.close()
            self.proc = None
        for module in reversed(self.modules):
            try:
                pactl("unload-module", module)
            except (OSError, subprocess.SubprocessError):
                pass
        self.modules.clear()


class Sharing:
    def __init__(self, send):
        self.send = send
        self.pending = {}
        self.lock = threading.Condition()
        self.stopped = False
        self.mask = 0
        self.revision = 0
        self.demand = 0
        self.last_camera = 0
        self.devices = {}
        self.last_clip = None
        self.burst_until = 0.0
        try:
            self.watcher = demand.Watcher(self.wake)
        except OSError:
            self.watcher = None  # the 0.5 s poll still works
        self.thread = threading.Thread(target=self.run, name="phone-sharing", daemon=True)
        self.thread.start()

    def wake(self):
        """A capture stream appeared/changed/went: poll demand now and briefly at 50 ms,
        since the link can turn active a few ms after the event."""
        with self.lock:
            self.burst_until = time.monotonic() + 0.3
            self.lock.notify()

    def offer(self, payload):
        kind, data = validate(payload)
        with self.lock:
            if kind == CONFIG:
                self.pending.clear()
            elif kind == MIC:
                old = self.pending.get(MIC)
                if old is not None and old[:4] == data[:4]:
                    # Preserve contiguous speech through short worker stalls, capped at 100 ms.
                    data = data[:4] + (old[4:] + data[4:])[-9600:]
            self.pending[kind] = data
            self.lock.notify()

    def status(self, text):
        print(f"    sharing: status to phone: {text}", flush=True)
        self.send(nal(STATUS, text.encode("utf-8")[:2048]))

    def state(self, kind, mask):
        self.send(nal(kind, self.revision.to_bytes(4, "big") + bytes([mask])))

    def configure(self, revision, mask):
        print(f"    sharing: config rev {revision} mask {mask:03b} (clip/mic/cam bits 1/2/4)", flush=True)
        if revision <= self.revision:
            print(f"    sharing: ignored stale config rev {revision} <= {self.revision}", flush=True)
            return
        self.revision = revision
        self.demand = 0
        self.mask = mask
        self.last_clip = None  # resend text that changed while Android lacked focus
        for kind, bit in ((MIC, 2), (CAMERA, 4)):
            if not mask & bit and kind in self.devices:
                self.devices.pop(kind).close()
            if mask & bit and kind not in self.devices:
                try:
                    self.devices[kind] = Device(kind)
                except (OSError, ValueError, subprocess.SubprocessError) as exc:
                    self.mask &= ~bit
                    self.status(str(exc))
        print(f"    sharing: ready mask {self.mask:03b}, devices {sorted(self.devices)}", flush=True)
        self.state(READY, self.mask)

    def poll_demand(self):
        active = 0
        try:
            if MIC in self.devices and demand.microphone_used(demand.snapshot()):
                active |= 2
            if CAMERA in self.devices and self.devices[CAMERA].usage.poll():
                active |= 4
        except (OSError, ValueError, subprocess.SubprocessError):
            active = 0  # observer failure must never renew capture
        self.demand = active & self.mask
        self.state(DEMAND, self.demand)

    def run(self):
        next_clip = 0
        next_demand = 0
        next_black = 0
        try:
            while True:
                with self.lock:
                    if not self.pending and not self.stopped:
                        self.lock.wait(0.05)
                    if self.stopped:
                        return
                    pending, self.pending = self.pending, {}
                if CONFIG in pending:
                    config = pending.pop(CONFIG)
                    self.configure(int.from_bytes(config[:4], "big"), config[4])
                    next_demand = 0
                    next_clip = time.monotonic() + 1.0
                for kind, data in pending.items():
                    if kind == CLIPBOARD and self.mask & 1:
                        try:
                            subprocess.run(["wl-copy", "--type", "text/plain;charset=utf-8"], input=data,
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1, check=True)
                            self.last_clip = data
                        except (OSError, subprocess.SubprocessError) as exc:
                            self.status("Clipboard: " + str(exc))
                    elif (kind in self.devices and self.demand & (2 if kind == MIC else 4)
                          and int.from_bytes(data[:4], "big") == self.revision):
                        try:
                            self.devices[kind].write(data[4:])
                            if kind == CAMERA:
                                self.last_camera = time.monotonic()
                        except OSError as exc:
                            self.devices.pop(kind).close()
                            self.mask &= ~(2 if kind == MIC else 4)
                            self.status(str(exc))
                            self.state(READY, self.mask)
                now = time.monotonic()
                camera = self.devices.get(CAMERA)
                try:
                    if camera and camera.usage.poll() != bool(self.demand & 4):
                        next_demand = 0  # a cheap non-blocking ioctl, so read it every pass
                except OSError:
                    next_demand = 0  # poll_demand fails closed
                if self.revision and (now >= next_demand or now < self.burst_until):
                    self.poll_demand()
                    next_demand = time.monotonic() + .5
                if CAMERA in self.devices and now >= next_black and (not self.demand & 4 or now - self.last_camera > 1):
                    try:
                        self.devices[CAMERA].write(self.devices[CAMERA].black)
                        next_black = now + .1
                    except OSError as exc:
                        self.devices.pop(CAMERA).close()
                        self.mask &= ~4
                        self.status(str(exc))
                        self.state(READY, self.mask)
                if self.mask & 1 and time.monotonic() >= next_clip:
                    next_clip = time.monotonic() + 0.75
                    try:
                        data = clipboard_read()
                        if data is not None and data != self.last_clip:
                            self.last_clip = data
                            self.send(nal(CLIPBOARD, data))
                    except (OSError, UnicodeError, subprocess.SubprocessError):
                        pass
        except (OSError, subprocess.SubprocessError) as exc:
            print(f"    sharing: worker stopped: {exc!r}", flush=True)  # pump owns transport teardown
        finally:
            if self.watcher:
                self.watcher.close()
            for device in self.devices.values():
                device.close()
            self.devices.clear()

    def close(self):
        with self.lock:
            self.stopped = True
            self.pending.clear()
            self.lock.notify()
        self.thread.join(timeout=12)
