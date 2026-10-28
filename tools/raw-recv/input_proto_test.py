#!/usr/bin/env python3
"""
input_proto_test.py - v1.0.1: the PC viewer's input to the console.

The Android viewer sends its touches and its gamepad over the stream's own
connection; the console drives its touch screen and a virtual Pro Controller
from them and sends the game's rumble back. This runs the built raw-view
against a mock console (a TCP socket on port 9950, the stream's own port) with
--test-input (a finger down and A held) and checks the bytes it sends against
the wire formats in tier4/applet-mitm/source/sft_{touch,pad,rumble}_proto.hpp:

  SFTK  4 bytes  a keyframe request, sent at start-up
  SFTT  48 bytes "SFTT", version 1, count, u16 seq, then 5 x {u16 x, u16 y,
        u8 id, u8 reserved[3]}; x 0..1279, y 0..719, id 0..15
  SFTG  32 bytes "SFTG", version 1, flags bit0 (a controller is there), u16
        seq, u32 buttons (the Switch's own HidNpadButton bits), 4 x s16
        sticks (y up), 12 reserved
  SFTV  4 bytes  "SFTV" - this viewer wants the game's rumble (every 2 s)

  python3 tools/raw-recv/input_proto_test.py
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
VIEW = REPO / "tools/raw-recv/raw-view"
PORT = 9950


class MockConsole:
    """accepts raw-view's connection and records everything it sends"""

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


def scan(data):
    """reassemble the viewer's SFT messages out of the byte stream"""
    out = []
    i, n = 0, len(data)
    while i + 4 <= n:
        if data[i:i + 3] != b"SFT":
            i += 1
            continue
        c = data[i + 3:i + 4]
        if c == b"K":
            out.append(("SFTK", data[i:i + 4])); i += 4
        elif c == b"V":
            out.append(("SFTV", data[i:i + 4])); i += 4
        elif c == b"T" and i + 48 <= n:
            out.append(("SFTT", data[i:i + 48])); i += 48
        elif c == b"G" and i + 32 <= n:
            out.append(("SFTG", data[i:i + 32])); i += 32
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
    env["SDL_RENDER_DRIVER"] = "software"     # the dummy driver has no GPU renderer
    proc = subprocess.Popen([str(VIEW), "--net", "127.0.0.1", "--test-input"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    try:
        if not console.wait_connected(15):
            proc.kill()
            out, err = proc.communicate()
            print("raw-view did not connect\n" + err.decode())
            return 1
        # 3.5 s: the first SFTG/SFTT, a touch keepalive, a rumble ask
        time.sleep(3.5)
    finally:
        proc.terminate()
        try:
            out, err = proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate()
        console.stop()

    kinds = {}
    for name, blob in scan(bytes(console.data)):
        kinds.setdefault(name, []).append(blob)
    for k, v in sorted(kinds.items()):
        print(f"  {k}: {len(v)}")

    fails = []
    if "SFTK" not in kinds:
        fails.append("no SFTK (the keyframe request)")
    if "SFTV" not in kinds:
        fails.append("no SFTV (the rumble ask)")

    touches = kinds.get("SFTG", [])
    if not touches:
        fails.append("no SFTG (the controller)")
    else:
        for b in touches:
            if b[4] != 1:
                fails.append(f"SFTG version {b[4]}, not 1")
            if not (b[5] & 1):
                fails.append("SFTG flags bit0 (connected) not set")
            buttons = struct.unpack_from("<I", b, 8)[0] & 0x0000FFFF
            if not (buttons & 1):
                fails.append("SFTG does not hold A (bit 0)")
            lx, ly, rx, ry = struct.unpack_from("<hhhh", b, 12)
            for name, v in (("lx", lx), ("ly", ly), ("rx", rx), ("ry", ry)):
                if not -32767 <= v <= 32767:
                    fails.append(f"SFTG stick {name} out of range: {v}")
            break

    pads = kinds.get("SFTT", [])
    if not pads:
        fails.append("no SFTT (the touch screen)")
    else:
        for b in pads:
            if b[4] != 1:
                fails.append(f"SFTT version {b[4]}, not 1")
            if b[5] != 1:
                fails.append(f"SFTT count {b[5]}, not 1 (the test's finger)")
            x, y = struct.unpack_from("<HH", b, 8)
            if not (0 <= x <= 1279 and 0 <= y <= 719):
                fails.append(f"SFTT point out of the touch screen: {x},{y}")
            if b[12] != 0:
                fails.append(f"SFTT finger id {b[12]}, not 0")
            break

    if fails:
        for f in fails:
            print("  FAIL " + f)
        print("input_proto_test FAILED")
        return 1
    print("input_proto_test OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
