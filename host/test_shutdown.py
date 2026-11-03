"""SIGTERM (systemctl stop/restart) must close the live session: phone defaults and laptop audio restored."""
import os
import subprocess
import sys
import tempfile

import stream


def main():
    ran = []
    stream._session_cleanups[:] = [lambda: ran.append("a"), lambda: ran.append("b")]
    stream.close_sessions()
    assert ran == ["a", "b"] and not stream._session_cleanups, ran

    marker = os.path.join(tempfile.mkdtemp(), "closed")
    child = f"""
import os, signal, time, stream
stream.install_signal_handlers()
stream._session_cleanups.append(lambda: open({marker!r}, "w").close())
try:
    os.kill(os.getpid(), signal.SIGTERM)
    time.sleep(5)
finally:
    stream.close_sessions()
"""
    result = subprocess.run([sys.executable, "-c", child], cwd=os.path.dirname(os.path.abspath(__file__)),
                            timeout=10)
    assert result.returncode == 0, result.returncode
    assert os.path.exists(marker), "SIGTERM skipped session cleanup"
    print("shutdown cleanup ok")


if __name__ == "__main__":
    main()
