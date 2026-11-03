"""A stopped audio reader must not inject old PCM into a replacement route."""
import threading
import io
from types import SimpleNamespace
import audio


def main():
    route = audio.AudioRoute()
    read_started = threading.Event()
    release_read = threading.Event()

    class DelayedOutput:
        def read(self, size):
            if read_started.is_set():
                return b""
            read_started.set()
            assert release_read.wait(2)
            return b"\x01\x02\x03\x04" * 480

    old = SimpleNamespace(stdout=DelayedOutput())
    route._proc = old
    reader = threading.Thread(target=route._read, args=(old,))
    reader.start()
    try:
        assert read_started.wait(2)
        # A stop/start can finish after read() returns but before it gets the lock.
        with route._lock:
            route._proc = SimpleNamespace()
            route._buf.clear()
        release_read.set()
        reader.join(2)
        assert not reader.is_alive()
        assert route.take() == b"", "old reader contaminated the new audio session"
    finally:
        release_read.set()
        reader.join(2)
    current = SimpleNamespace(stdout=io.BytesIO(b"\x05\x06\x07\x08" * 480))
    route._proc = current
    route._read(current)
    assert route.take() == b"\x05\x06\x07\x08" * 480, "current session lost audio"
    print("audio reader session isolation ok")


if __name__ == "__main__":
    main()
