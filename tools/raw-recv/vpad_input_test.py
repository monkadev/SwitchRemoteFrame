#!/usr/bin/env python3
"""
vpad_input_test.py - v1.0.2: the on-screen gamepad reaches the console.

The on-screen gamepad is a third input, merged with the keyboard and a physical
controller into the one Pro Controller the Switch sees. tools/vpad_test.c checks
the layout's maths and the remap table; this checks the other half - that a
click on an on-screen control really becomes an "SFTG" the console can read.

It runs the built raw-view with --test-vpad against a mock console (a TCP socket
on port 9950, the stream's own port). That flag makes the viewer show the pad,
work out where its A button is, and push a real SDL mouse-button-down there -
the same code a click goes through - while leaving the keyboard's A alone. So
the A in the SFTG can only have come from the on-screen control.

  python3 tools/raw-recv/vpad_input_test.py
"""
import os
import socket
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
# SFT_VIEW points at another build, so a released AppImage or .exe can be
# checked the same way: SFT_VIEW=/path/to/viewer python3 vpad_input_test.py
VIEW = Path(os.environ["SFT_VIEW"]) if os.environ.get("SFT_VIEW") \
    else REPO / "tools/raw-recv/raw-view"
PORT = 9950   # the stream's own port; --net always uses it

A_BIT = 0                 # HidNpadButton A, as the console reads it
KEYBOARD_A_BIT = 0        # the keyboard's Z is A too, so the test must not press Z


class MockConsole:
    def __init__(self, port):
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", port))
        self.srv.listen(1)
        self.conn = None
        self.data = bytearray()

    def _serve(self):
        self.conn, _ = self.srv.accept()
        self.conn.settimeout(0.5)
        try:
            while True:
                try:
                    chunk = self.conn.recv(1 << 16)
                except socket.timeout:
                    continue
                if not chunk:
                    return
                self.data += chunk
        except OSError:
            return

    def start(self):
        threading.Thread(target=self._serve, daemon=True).start()

    def wait_connected(self, seconds):
        deadline = time.time() + seconds
        while self.conn is None and time.time() < deadline:
            time.sleep(0.05)
        return self.conn is not None

    def stop(self):
        for s in (self.conn, self.srv):
            try:
                if s:
                    s.close()
            except OSError:
                pass


def sftg_messages(data):
    """the 32-byte controller messages, out of the viewer's byte stream"""
    out = []
    i, n = 0, len(data)
    while i + 32 <= n:
        if data[i:i + 4] == b"SFTG":
            out.append(bytes(data[i:i + 32]))
            i += 32
        else:
            i += 1
    return out


def main():
    if not VIEW.exists():
        subprocess.run(["make", "-C", str(VIEW.parent), "raw-view"], check=True)
    console = MockConsole(PORT)
    console.start()
    env = dict(os.environ)
    env["SDL_VIDEODRIVER"] = "dummy"          # no display on the test machine
    env["SDL_RENDER_DRIVER"] = "software"
    proc = subprocess.Popen([str(VIEW), "--net", "127.0.0.1", "--test-vpad"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    try:
        if not console.wait_connected(15):
            proc.kill()
            _, err = proc.communicate()
            print("raw-view did not connect\n" + err.decode())
            return 1
        time.sleep(3.5)                        # enough for the pad, the click and a resend
    finally:
        proc.terminate()
        try:
            _, err = proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            _, err = proc.communicate()
        console.stop()

    msgs = sftg_messages(bytes(console.data))
    print(f"  SFTG: {len(msgs)}")
    fails = []
    if not msgs:
        print("  FAIL no SFTG at all (the on-screen gamepad sent nothing)")
        print("vpad_input_test FAILED")
        return 1

    held = [m for m in msgs if struct.unpack_from("<I", m, 8)[0] & (1 << A_BIT)]
    if not held:
        bits = sorted({struct.unpack_from("<I", m, 8)[0] for m in msgs})
        fails.append(f"no SFTG ever held A (button words seen: {bits})")
    for m in msgs:
        if m[4] != 1:
            fails.append(f"SFTG version {m[4]}, not 1")
            break
        lx, ly, rx, ry = struct.unpack_from("<hhhh", m, 12)
        for name, v in (("lx", lx), ("ly", ly), ("rx", rx), ("ry", ry)):
            if not -32767 <= v <= 32767:
                fails.append(f"SFTG stick {name} out of range: {v}")
    # Every message that holds a button must also say a controller is there.
    # (Some messages clear it on purpose - that is the viewer taking the
    # controller away again - so this is checked per message, not for all.)
    for m in msgs:
        if struct.unpack_from("<I", m, 8)[0] and not (m[5] & 1):
            fails.append("an SFTG holds buttons but flags bit0 says no controller")
            break

    # the on-screen gamepad is a controller on its own: with no keyboard and no
    # physical gamepad, holding its A must make the viewer say one is there
    if held:
        print(f"  the on-screen A reached the console ({len(held)} of {len(msgs)} messages)")
    if fails:
        for f in fails:
            print("  FAIL " + f)
        print("vpad_input_test FAILED")
        return 1
    print("vpad_input_test OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
