"""Consumer graph, default ownership, stale configuration and fail-closed demand."""
from unittest.mock import patch
import threading
import time
import demand
import sharing


def main():
    node = {"id": 42, "info": {"props": {"node.name": "folddeck_mic"}}}
    link = {"type": "PipeWire:Interface:Link", "info": {"output-node-id": 42, "state": "active"}}
    assert not demand.microphone_used([node])
    assert demand.microphone_used([node, link])
    link["info"]["state"] = "paused"
    assert not demand.microphone_used([node, link])
    current = {"name": "laptop"}
    def read(objects, key):
        return current
    def write(key, value):
        nonlocal current
        current = value
    with patch.object(demand, "snapshot", return_value=[]), patch.object(demand, "configured", side_effect=read), patch.object(demand, "set_default", side_effect=write):
        default = demand.Default("audio", "folddeck_mic")
        assert current == {"name": "folddeck_mic"}
        default.close()
        assert current == {"name": "laptop"}
        default = demand.Default("audio", "folddeck_mic")
        current = {"name": "user-selected"}
        default.close()
        assert current == {"name": "user-selected"}
    # Exercise worker state without launching devices or an input thread.
    s = sharing.Sharing.__new__(sharing.Sharing)
    s.lock = threading.Condition(); s.pending = {}
    revision = (12).to_bytes(4, "big")
    for i in range(1, 6):
        s.offer(bytes([sharing.MIC]) + revision + bytes([i]) * 1920)
    assert s.pending[sharing.MIC] == revision + b"".join(bytes([i]) * 1920 for i in range(1, 6)), "100 ms worker delay lost PCM"
    s.offer(bytes([sharing.MIC]) + revision + b"\x06" * 1920)
    assert s.pending[sharing.MIC] == revision + b"".join(bytes([i]) * 1920 for i in range(2, 7)), "overflow must retain newest 100 ms"
    fresh = (13).to_bytes(4, "big") + b"\x07" * 1920
    s.offer(bytes([sharing.MIC]) + fresh)
    assert s.pending[sharing.MIC] == fresh, "configuration revisions mixed PCM"
    s.offer(bytes([sharing.CONFIG]) + (14).to_bytes(4, "big") + b"\0")
    assert set(s.pending) == {sharing.CONFIG}, "disable retained microphone samples"
    s.revision = 10; s.mask = 0; s.demand = 0; s.devices = {}; s.send = lambda data: None
    s.configure(9, 7)
    assert s.mask == 0 and s.revision == 10
    s.devices = {sharing.MIC: object()}; s.mask = 2; s.demand = 2
    with patch.object(demand, "snapshot", side_effect=OSError("observer unavailable")):
        s.poll_demand()
    assert s.demand == 0
    # ValueError (e.g. JSONDecodeError from pw-dump) during configure must not crash worker.
    s.devices = {}
    with patch.object(sharing, "Device", side_effect=ValueError("corrupt pw-dump json")):
        s.configure(11, 2)
    assert s.mask == 0 and s.revision == 11
    # Idle black-frame write failure must retire only CAMERA and keep the Sharing thread alive.
    class FailingCamera:
        black = b"jpeg"
        usage = type("U", (), {"poll": staticmethod(lambda: False)})()
        closed = False
        def write(self, data):
            raise OSError("camera writer died")
        def close(self):
            self.closed = True
    cam = FailingCamera()
    sent = []
    worker = sharing.Sharing(sent.append)
    try:
        with worker.lock:
            worker.revision = 1
            worker.mask = 6
            worker.devices[sharing.CAMERA] = cam
            worker.lock.notify()
        deadline = threading.Event()
        for _ in range(40):
            if cam.closed:
                break
            deadline.wait(0.02)
        assert cam.closed, "failed idle camera was not closed"
        assert worker.thread.is_alive(), "idle camera write failure killed Sharing thread"
        assert worker.mask == 2, f"camera bit not cleared from mask: {worker.mask}"
    finally:
        worker.close()
    for mask in (8, 128, 255):
        try:
            sharing.validate(b"\x02\0\0\0\x01" + bytes([mask]))
        except ValueError:
            pass
        else:
            raise AssertionError("invalid permission mask accepted")
    # Real `pactl subscribe` lines from this laptop: only capture streams matter, and
    # pw-dump itself creates client events, which must not retrigger a poll.
    assert demand.is_capture_event("Event 'new' on source-output #102736")
    assert demand.is_capture_event("Event 'remove' on source-output #7")
    assert not demand.is_capture_event("Event 'new' on client #102735")
    assert not demand.is_capture_event("Event 'change' on sink-input #5")
    # A consumer event must reach the phone well inside the 0.5 s fallback poll.
    class Mic:
        def close(self):
            pass
    used = [False]
    events = []
    with patch.object(demand, "snapshot", return_value=[]), \
            patch.object(demand, "microphone_used", side_effect=lambda objects: used[0]):
        worker = sharing.Sharing(lambda data: None)
        worker.state = lambda kind, mask: events.append((kind, mask, time.monotonic()))
        try:
            with worker.lock:
                worker.revision, worker.mask = 1, 2
                worker.devices[sharing.MIC] = Mic()
                worker.lock.notify()
            for _ in range(100):
                if events:
                    break
                time.sleep(0.01)
            assert events, "worker never polled demand"
            used[0] = True
            woke = time.monotonic()
            worker.wake()
            for _ in range(50):
                if any(mask == 2 for _, mask, _ in events):
                    break
                time.sleep(0.01)
            hit = [at for _, mask, at in events if mask == 2]
            assert hit and hit[0] - woke < 0.15, f"consumer event not acted on promptly: {events}"
        finally:
            worker.close()
    print("demand ownership, stale config and fail-closed checks ok")


if __name__ == "__main__":
    main()
