"""Local consumer detection and session-scoped PipeWire default inputs."""
import ctypes
import errno
import fcntl
import json
import os
import struct
import subprocess
import threading
import time


def command(*args):
    return subprocess.check_output(args, text=True, timeout=3).strip()


def snapshot():
    return json.loads(command("pw-dump", "-N", "-R"))


def props(obj):
    return obj.get("info", {}).get("props", {})


def configured(objects, key):
    for obj in objects:
        if obj.get("props", {}).get("metadata.name") == "default":
            for entry in obj.get("metadata", []):
                if entry["subject"] == 0 and entry["key"] == key:
                    value = entry["value"]
                    return json.loads(value) if isinstance(value, str) else value
    return None


def set_default(key, value):
    if value is None:
        command("pw-metadata", "-n", "default", "-d", "0", key)
    else:
        command("pw-metadata", "-n", "default", "0", key, json.dumps(value), "Spa:String:JSON")


class Default:
    def __init__(self, media, name):
        self.key = "default.configured." + media + ".source"
        self.value = {"name": name}
        self.previous = configured(snapshot(), self.key)
        set_default(self.key, self.value)

    def close(self):
        # Respect a manual default change made during the session.
        if configured(snapshot(), self.key) == self.value:
            set_default(self.key, self.previous)


class Event(ctypes.Structure):
    _fields_ = [("type", ctypes.c_uint32), ("data", ctypes.c_uint64 * 8),
                ("pending", ctypes.c_uint32), ("sequence", ctypes.c_uint32),
                ("sec", ctypes.c_long), ("nsec", ctypes.c_long),
                ("id", ctypes.c_uint32), ("reserved", ctypes.c_uint32 * 8)]


class Subscription(ctypes.Structure):
    _fields_ = [("type", ctypes.c_uint32), ("id", ctypes.c_uint32),
                ("flags", ctypes.c_uint32), ("reserved", ctypes.c_uint32 * 5)]


class CameraUsage:
    TYPE = 0x10E00001  # v4l2loopback V4L2_EVENT_PRI_CLIENT_USAGE

    def __init__(self, path):
        self.fd = os.open(path, os.O_RDWR | os.O_NONBLOCK | os.O_CLOEXEC)
        self.active = False
        try:
            sub = Subscription(type=self.TYPE, flags=1)  # SEND_INITIAL
            fcntl.ioctl(self.fd, 0x4000565A | (ctypes.sizeof(sub) << 16), sub)
        except Exception:
            self.close()
            raise

    def poll(self):
        while True:
            event = Event()
            try:
                fcntl.ioctl(self.fd, 0x80005659 | (ctypes.sizeof(event) << 16), event)
            except OSError as exc:
                if exc.errno in (errno.EAGAIN, errno.ENOENT):
                    return self.active
                raise
            if event.type == self.TYPE:
                self.active = bool(event.data[0] & 0xffffffff)

    def close(self):
        os.close(self.fd)


def microphone_used(objects):
    nodes = {o["id"] for o in objects if props(o).get("node.name") == "folddeck_mic"}
    return any(o.get("type") == "PipeWire:Interface:Link"
               and o.get("info", {}).get("output-node-id") in nodes
               and o.get("info", {}).get("state") == "active" for o in objects)


def is_capture_event(line):
    # Only recording streams. pw-dump (our own poll) makes client events, so those
    # would retrigger themselves.
    return " on source-output #" in line


class Watcher:
    """Calls back on every capture-stream event, so demand is seen in ms, not at the next poll."""

    def __init__(self, callback):
        self.proc = subprocess.Popen(["pactl", "subscribe"], stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, text=True)
        threading.Thread(target=self.run, args=(callback,), name="pactl-subscribe", daemon=True).start()

    def run(self, callback):
        with self.proc.stdout:   # closed here, at the EOF close() causes
            for line in self.proc.stdout:
                if is_capture_event(line):
                    callback()

    def close(self):
        self.proc.kill()
        self.proc.wait()


def camera_rate(path):
    # Set before starting the writer: exclusive_caps exposes OUTPUT until then.
    # The kernel's unreduced default breaks PipeWire/GStreamer format matching.
    params = bytearray(204)  # struct v4l2_streamparm: type + 200-byte union
    struct.pack_into("=I", params, 0, 2)  # V4L2_BUF_TYPE_VIDEO_OUTPUT
    struct.pack_into("=II", params, 12, 1, 10)  # output.timeperframe: our 10 fps
    fd = os.open(path, os.O_RDWR | os.O_CLOEXEC)
    try:
        fcntl.ioctl(fd, 0xC0CC5616, params)  # VIDIOC_S_PARM
    finally:
        os.close(fd)


class CameraNode:
    def __init__(self, path):
        self.id = None
        self.name = "folddeck_camera"
        command("pw-cli", "create-node", "spa-node-factory",
                '{ factory.name=api.v4l2.source api.v4l2.path=' + path
                + ' node.name=folddeck_camera node.description="Folddeck Phone"'
                + ' media.class=Video/Source object.linger=true }')
        for _ in range(10):
            for obj in snapshot():
                if props(obj).get("node.name") == self.name:
                    self.id = obj["id"]
                    return
            time.sleep(.05)
        raise OSError("Could not publish phone camera to PipeWire")

    def close(self):
        if self.id is not None:
            # IDs can be reused after a media-server restart.
            if any(o["id"] == self.id and props(o).get("node.name") == self.name for o in snapshot()):
                command("pw-cli", "destroy", str(self.id))
