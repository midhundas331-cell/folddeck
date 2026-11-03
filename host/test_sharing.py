"""Bounded sharing framing, input interleaving, validation and SEI escaping."""
import socket
import threading
import sharing
import stream
from stream import input_reader


def main():
    received = []
    a, b = socket.socketpair()
    thread = threading.Thread(target=input_reader, args=(b, None, None, threading.Event(), threading.Lock()),
                              kwargs={"on_sharing": received.append})
    thread.start()
    payloads = [b"\x02\0\0\0\x01\x01", b"\x03hello \xf0\x9f\x8c\x8d", b"\x04\0\0\0\x01" + b"\0" * 1920]
    for payload in payloads:
        frame = b"\x08" + len(payload).to_bytes(4, "big") + payload
        for i in range(0, len(frame), 3):
            a.sendall(frame[i:i + 3])
        a.sendall(b"\x03\x01\x00")
    a.close()
    thread.join(2)
    b.close()
    assert not thread.is_alive() and received == payloads
    for invalid in [b"", b"\x02\x80", b"\x04x", b"\x03\xff", b"\x05not jpeg", b"\x09", b"\x03" + b"a" * (sharing.MAX_TEXT + 1)]:
        try:
            sharing.validate(invalid)
        except ValueError:
            pass
        else:
            raise AssertionError("accepted invalid sharing message")
    for p in payloads:
        sharing.validate(p)
    body = sharing.nal(3, b"hello\0\0\0\0\x01")
    assert b"\0\0\x01" not in body[4:]
    # MSG_VIDEO: a hidden phone pauses the screen stream but keeps the connection.
    video = []
    a, b = socket.socketpair()
    thread = threading.Thread(target=input_reader, args=(b, None, None, threading.Event(), threading.Lock()),
                              kwargs={"on_video": video.append})
    thread.start()
    a.sendall(b"\x09\x00\x09\x01")
    a.close()
    thread.join(2)
    b.close()
    assert video == [False, True], video
    gate = stream.VideoGate()
    stop = threading.Event()
    assert not gate.consume_pause(), "a capture that ended on its own is not a pause"
    gate.pause()
    gate.resume()  # back before the pump noticed its encoder die
    assert gate.consume_pause(), "rapid hide/show must not end the connection"
    assert not gate.consume_pause()
    assert gate.wait(stop), "resumed video should restart capture at once"
    gate.pause()
    threading.Timer(0.1, gate.resume).start()
    assert gate.wait(stop), "paused capture restarts on resume"
    gate.pause()
    threading.Timer(0.1, stop.set).start()
    assert not gate.wait(stop), "disconnect while paused ends the stream"
    print("sharing framing and bounds ok")


if __name__ == "__main__":
    main()
