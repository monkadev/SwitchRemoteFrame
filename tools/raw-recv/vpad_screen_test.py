#!/usr/bin/env python3
"""
vpad_screen_test.py - v1.0.3: the mouse really is the Switch's touch screen,
and the on-screen gamepad is really on the frames the user sees.

Two things broke, and neither was covered by a test:

  - ui_event answered 1 to *every* mouse move, and the event loop's `continue`
    then swallowed it, so the move never reached the on-screen gamepad or, under
    it, the touch screen. A tap worked; a drag never became a swipe.
  - the on-screen gamepad was drawn only by viewer_repaint, which nothing calls
    while a picture streams. The display loop cleared and re-presented every
    frame without it, so the pad lasted one frame - it flickered on a click and
    vanished.

Both need a picture on screen (the touch screen and the pad are for a game), so
this streams real H.264 from the console's own frames, the way
tools/sft_stream_test.py does, and then:

  --test-drag   pushes a left-button press and a run of moves as real SDL
                events. They go through the same routing a mouse does, and the
                SFTT frames the console receives must show the finger moving.
  --shot        saves the last frame presented and reports whether that frame
                carried the overlays, so "drew them once" cannot pass for
                "drew them on the video".

  python3 tools/raw-recv/vpad_screen_test.py
"""
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
VIEW = Path(os.environ["SFT_VIEW"]) if os.environ.get("SFT_VIEW") \
    else REPO / "tools/raw-recv/raw-view"
PORT = 9950
HDRS = REPO / "tier4/applet-mitm/source/nvenc_grc_hdrs.h"
RUN_H = REPO / "logs/m82-runH"
STATUS_FMT = "<IIIIHHHHIiIIHH"


def console_sps_pps():
    txt = HDRS.read_text()
    body = txt[txt.index("SpsPps["):]
    n = int(re.search(r"SpsPps\[(\d+)\]", body).group(1))
    vals = [int(v, 16) for v in re.findall(r"0x([0-9a-f]{2})", body)]
    assert len(vals) == n, (len(vals), n)
    return bytes(vals)


def nvenc_frames():
    out = []
    for tag in ("q16", "q20", "q24", "last"):
        st = (RUN_H / f"nvframe-{tag}-status.bin").read_bytes()
        total_bits = struct.unpack_from(STATUS_FMT, st, 0)[2]
        bits = (RUN_H / f"nvframe-{tag}-bits.bin").read_bytes()
        out.append(bits[:total_bits // 8])
    return out


def pack(frame_no, payload):
    """an SFTR packet: 32-byte header (version 2, flags 2 = H.264) then the slice"""
    return struct.pack("<IHHIIIIII", 0x52544653, 2, 2, 1280, 720, 0, len(payload), 0, frame_no) + payload


class StreamingConsole:
    """the console's end of the stream: takes the viewer's input, sends H.264"""

    def __init__(self, port, frames):
        self.port = port
        self.frames = frames
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", port))
        self.srv.listen(1)
        self.conn = None
        self.data = bytearray()
        self.stop = False

    def _serve(self):
        self.conn, _ = self.srv.accept()
        self.conn.settimeout(0.2)
        sps = console_sps_pps()
        n = 0
        try:
            while not self.stop:
                try:
                    chunk = self.conn.recv(1 << 16)
                    if chunk:
                        self.data += chunk
                except socket.timeout:
                    pass
                except OSError:
                    return
                # ~30 fps, looping the four real slices; the first carries the SPS+PPS
                payload = (sps + self.frames[n % len(self.frames)]) if n == 0 \
                    else self.frames[n % len(self.frames)]
                try:
                    self.conn.sendall(pack(n, payload))
                except OSError:
                    return
                n += 1
                time.sleep(1.0 / 30.0)
        except OSError:
            return

    def start(self):
        threading.Thread(target=self._serve, daemon=True).start()

    def wait_connected(self, seconds):
        deadline = time.time() + seconds
        while self.conn is None and time.time() < deadline:
            time.sleep(0.05)
        return self.conn is not None

    def close(self):
        self.stop = True
        for s in (self.conn, self.srv):
            try:
                if s:
                    s.close()
            except OSError:
                pass


def sftt_frames(data):
    """the 48-byte touch frames, as (finger count, [(x, y), ...])"""
    out = []
    i, n = 0, len(data)
    while i + 48 <= n:
        if data[i:i + 4] == b"SFTT":
            count = data[i + 5]
            pts = []
            for k in range(min(count, 5)):
                o = i + 8 + k * 8
                pts.append((struct.unpack_from("<H", data, o)[0],
                            struct.unpack_from("<H", data, o + 2)[0]))
            out.append((count, pts))
            i += 48
        else:
            i += 1
    return out


def run(args, seconds, console, extra_env=None):
    env = dict(os.environ)
    env["SDL_VIDEODRIVER"] = "dummy"
    env["SDL_RENDER_DRIVER"] = "software"
    if extra_env:
        env.update(extra_env)
    proc = subprocess.Popen([str(VIEW), "--net", "127.0.0.1"] + args,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    try:
        if not console.wait_connected(15):
            proc.kill()
            _, err = proc.communicate()
            return None, "raw-view did not connect\n" + err.decode(errors="replace")
        # let the picture arrive, so the home screen is gone and the game is up
        time.sleep(seconds)
    finally:
        proc.terminate()
        try:
            _, err = proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            _, err = proc.communicate()
    return bytes(console.data), err.decode(errors="replace")


def main():
    if not VIEW.exists():
        subprocess.run(["make", "-C", str(VIEW.parent), "raw-view"], check=True)
    fails = []

    # ---- the drag ------------------------------------------------------------
    console = StreamingConsole(PORT, nvenc_frames())
    console.start()
    data, err = run(["--test-drag"], 5.0, console)
    console.close()
    if data is None:
        print("  FAIL " + err)
        print("vpad_screen_test FAILED")
        return 1

    frames = sftt_frames(data)
    down = [pts for count, pts in frames if count == 1]
    moved = []
    for pts in down:
        if not moved or moved[-1] != pts[0]:
            moved.append(pts[0])
    print(f"  SFTT frames: {len(frames)}, one finger down in {len(down)}, "
          f"{len(moved)} distinct positions")
    if not down:
        fails.append("the console never saw a finger down - the left button did not "
                     "become a touch (is the picture on screen?)")
    elif len(moved) < 5:
        fails.append(f"the finger only ever sat at {len(moved)} position(s) "
                     f"{moved[:3]} - the drag did not become a swipe")
    else:
        # a swipe: the last position is well away from the first
        (x0, y0), (x1, y1) = moved[0], moved[-1]
        dist = ((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5
        print(f"  swipe {moved[0]} -> {moved[-1]} ({dist:.0f} of 1280x720)")
        if dist < 40:
            fails.append(f"the finger moved only {dist:.0f} points - not a swipe")
    time.sleep(0.4)

    # ---- hiding the pad with its own button, and bringing it back -------------
    console = StreamingConsole(PORT, nvenc_frames())
    console.start()
    _, err = run(["--test-pad-toggle", "--seconds", "8", "--shot", "/dev/null"], 8.5, console)
    console.close()
    time.sleep(0.4)
    clicks = [l.strip() for l in err.splitlines() if "test-pad-toggle" in l]
    for l in clicks:
        print("  " + l)
    hid = [l for l in clicks if "hidden=1" in l]
    back = [l for l in clicks if "the pad is shown again: yes" in l]
    fade = [l for l in clicks if "idle alpha" in l]
    if len(hid) != 1 or "hidden=1" not in hid[0]:
        fails.append(f"clicking the pad's button did not hide the pad: {clicks}")
    elif not back:
        fails.append(f"the pad's Show button did not bring the pad back: {clicks}")
    else:
        print("  the pad hid, and the same button brought it back")
    # v1.0.5: while the pad is away and the pointer sits still, the Show button
    # fades out after 2 s; one move brings it straight back
    if not fade:
        fails.append("the run never reported the Show button's idle fade")
    else:
        mf = re.search(r"idle alpha is (\d\.\d+); one move -> (\d\.\d+)", fade[0])
        if not mf:
            fails.append(f"could not read the idle fade from: {fade[0]}")
        else:
            gone, moved = float(mf.group(1)), float(mf.group(2))
            if gone > 0.05:
                fails.append(f"the Show button was still at alpha {gone:.2f} after "
                             "2.5 s with the pointer still - it should have faded out")
            elif moved < 0.99:
                fails.append(f"one move only brought the Show button to alpha "
                             f"{moved:.2f} - any movement must bring it back at once")
            else:
                print("  the Show button faded out while the pointer sat still, "
                      "and one move brought it back")
    # while the pad was away the button has to be on the picture long enough to
    # be found - until v1.0.5 that meant every hidden frame; now it fades out
    # 2 s after the pointer rests, so what matters is that it was up at all
    m = re.search(r"while the pad was hidden: (\d+) frame\(s\), overlays on (\d+)", err)
    if not m:
        fails.append("the run reported nothing about the frames where the pad was "
                     "hidden, so the Show button staying up was not checked")
    else:
        hf, ho = int(m.group(1)), int(m.group(2))
        print(f"  while the pad was hidden: {hf} frame(s), the button on {ho} of them")
        if hf == 0:
            fails.append("the pad was never hidden on a presented frame, so the "
                         "Show button staying up was not checked on a picture")
        elif ho < 1:
            fails.append("the pad was hidden on presented frames but the Show "
                         "button was never drawn on one - there would have been "
                         "no way to find it")

    # ---- the Settings button staying put over the game ------------------------
    import tempfile
    chrome_home = Path(tempfile.mkdtemp(prefix="sft-chrome-"))
    console = StreamingConsole(PORT, nvenc_frames())
    console.start()
    _, err = run(["--test-chrome"], 7.0, console,
                 {"HOME": str(chrome_home), "XDG_STATE_HOME": str(chrome_home)})
    console.close()
    time.sleep(0.4)
    chrome = [l.strip() for l in err.splitlines() if "test-chrome" in l]
    for l in chrome:
        print("  " + l)
    if len(chrome) < 3:
        fails.append(f"--test-chrome said {len(chrome)} thing(s); it reports the "
                     "button's place, its place again after the pointer sat still, "
                     "and the click")
    else:
        first, later, click = chrome[0], chrome[1], chrome[2]
        if "-> H_SETTINGS" not in first:
            fails.append(f"the chrome did not lay the Settings button out over the "
                         f"game: {first}")
        m = re.search(r"still at \(([-\d.]+),([-\d.]+)\)", later)
        m0 = re.search(r"is at \(([-\d.]+),([-\d.]+)\)", first)
        if not (m and m0):
            fails.append(f"could not read the button's place from: {first} / {later}")
        elif (m.group(1), m.group(2)) != (m0.group(1), m0.group(2)):
            fails.append(f"the Settings button moved while the pointer sat still: "
                         f"{m0.group(1)},{m0.group(2)} -> {m.group(1)},{m.group(2)}")
        elif "-> H_SETTINGS" not in later:
            fails.append(f"the Settings button stopped taking the click where it "
                         f"stands: {later}")
        elif "Settings opened" not in click:
            fails.append(f"clicking the Settings button did not open Settings: {click}")
        else:
            print("  the button stayed where it was laid out, and the click opened Settings")
        # v1.0.5: 2.5 s with the pointer still fades the button out; one move
        # brings it back at full strength
        ma = re.search(r"idle alpha ([\d.]+)", later)
        mb2 = re.search(r"moved -> idle alpha ([\d.]+)", click)
        if not (ma and mb2):
            fails.append(f"the run never reported the button's idle fade: "
                         f"{later} / {click}")
        else:
            gone, moved = float(ma.group(1)), float(mb2.group(1))
            if gone > 0.05:
                fails.append(f"the Settings button was still at alpha {gone:.2f} "
                             "after 2.5 s with the pointer still - it should have "
                             "faded out")
            elif moved < 0.99:
                fails.append(f"one move only brought the Settings button to alpha "
                             f"{moved:.2f} - any movement must bring it back at once")
            else:
                print("  it faded out while the pointer sat still, and one move "
                      "brought it back")
        mo = re.search(r"opacity (\d+)%", first)
        if not mo:
            fails.append("the log did not report the Settings button's opacity")
        elif not 0 <= int(mo.group(1)) <= 100:
            fails.append(f"the Settings button's opacity is {mo.group(1)}%, outside 0..100%")
        # v1.0.1: the show/hide toggle removes the gear and brings it back
        off_line = next((l for l in chrome if "toggle off" in l), "")
        on_line = next((l for l in chrome if "toggle on" in l), "")
        if "gear in layout no" not in off_line or "hit at its place no" not in off_line:
            fails.append(f"switching the Settings button off did not remove the gear: {off_line or 'no line'}")
        elif "gear in layout yes" not in on_line or "hit H_SETTINGS" not in on_line:
            fails.append(f"switching the Settings button back on did not restore it: {on_line or 'no line'}")
        elif any("(FAIL)" in l for l in chrome):
            fails.append("the chrome test reported a FAIL marker")
        else:
            print("  the Settings button toggle removes and restores the gear")
        ini = chrome_home / ".local/share/switch-remote-frame/viewer/viewer.ini"
        saved = ini.read_text(errors="replace") if ini.exists() else ""
        if "set_button=1" not in saved:
            fails.append("viewer.ini does not remember the Settings button toggle")
        else:
            print("  the toggle is remembered in viewer.ini")
        shutil.rmtree(chrome_home, ignore_errors=True)

    # ---- the layout editor's panel shape --------------------------------------
    console = StreamingConsole(PORT, nvenc_frames())
    console.start()
    _, err = run(["--test-editor"], 6.0, console)
    console.close()
    time.sleep(0.4)
    ed = [l.strip() for l in err.splitlines() if "test-editor" in l]
    for l in ed:
        print("  " + l)
    joined = "\n".join(ed)
    md = re.search(r"dragged control 0 from \(([-\d.]+),([-\d.]+)\) to "
                   r"\(([-\d.]+),([-\d.]+)\) of a 1x1 layout, selected=(\d)", joined)
    if not md:
        fails.append("the editor never reported the control drag")
    else:
        x0, y0 = float(md.group(1)), float(md.group(2))
        x1, y1 = float(md.group(3)), float(md.group(4))
        sel = int(md.group(5))
        dist = ((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5
        if dist < 0.02:
            fails.append(f"pressing and moving the pointer left the control at "
                         f"({x0:.3f},{y0:.3f}) -> ({x1:.3f},{y1:.3f}) - a drag of "
                         f"{dist:.3f} in a 1x1 layout moved nothing")
        elif not sel:
            fails.append("the dragged control did not stay selected")
        else:
            print(f"  the left button drags a control: it moved {dist:.3f} of the view")
    mg = re.search(r"view (\d+)x(\d+) - reset y=(\d+), cancel y=(\d+), "
                   r"hide y=(\d+) right=(\d+) \(panel right (\d+)\)", joined)
    if not mg:
        fails.append("the editor never reported its panel geometry")
    else:
        vw, vh = int(mg.group(1)), int(mg.group(2))
        ry, ay, hy, hright, pright = (int(mg.group(i)) for i in (3, 4, 5, 6, 7))
        if not (ry < ay < hy):
            fails.append(f"the bottom block is not stacked Reset/Cancel/Hide: "
                         f"reset y={ry}, cancel y={ay}, hide y={hy}")
        if hy + 26 + 4 > vh:
            fails.append(f"the Hide button (y={hy}) is not inside the {vh}-tall view")
        if abs(hright - pright) > 12:
            fails.append(f"the Hide button's right edge ({hright}) is not the "
                         f"panel's ({pright}) - it must sit in the panel's "
                         "bottom-right corner")
        if not (ry > vh * 0.5):
            fails.append(f"the bottom block starts at y={ry}, not in the bottom "
                         f"half of the {vh}-tall view")
    ms = re.search(r"scroll=(\d+) of content (\d+) in a viewport of (\d+)", joined)
    if not ms:
        fails.append("the ADD list reported no scroll")
    else:
        sc, ch, lv = int(ms.group(1)), int(ms.group(2)), int(ms.group(3))
        if ch <= lv:
            fails.append(f"the test list ({ch}px) did not overflow its viewport "
                         f"({lv}px), so the scroll was not really exercised")
        elif sc <= 0:
            fails.append(f"3 wheel notches left the list at scroll={sc} - it did "
                         "not scroll")
        elif sc > ch - lv:
            fails.append(f"the list scrolled to {sc}, past its content "
                         f"({ch} in {lv})")
    msh = re.search(r"panel hidden=(\d), Show at \((\d+),(\d+)\) size (\d+)x(\d+) "
                    r"of a (\d+)x(\d+) view", joined)
    if not msh:
        fails.append("hiding the panel never reported the Show button's place")
    else:
        hid, sx, sy = int(msh.group(1)), int(msh.group(2)), int(msh.group(3))
        svw, svh = int(msh.group(6)), int(msh.group(7))
        if not hid:
            fails.append("clicking Hide did not hide the panel")
        elif sy < svh * 0.5 or sx < svw * 0.5:
            fails.append(f"the Show button is at ({sx},{sy}) - it belongs in the "
                         f"bottom-right corner of the {svw}x{svh} view")
        else:
            print("  Hide tucked the panel away, and Show stands in the bottom-right corner")
    if "the panel is back: yes" not in joined:
        fails.append("clicking Show did not bring the panel back")

    # ---- the overlays on the video frames ------------------------------------
    shot = REPO / "tools/raw-recv/_vpad_screen_shot.bmp"
    console = StreamingConsole(PORT, nvenc_frames())
    console.start()
    _, err = run(["--seconds", "4", "--shot", str(shot)], 4.5, console)
    console.close()
    said = [l for l in err.splitlines() if "saved" in l and "overlays on" in l]
    print(f"  {said[-1].strip() if said else 'the viewer saved no shot'}")
    if not said:
        fails.append("the viewer saved no screenshot, so the video frames were not checked")
    else:
        # "overlays on N of M presented video frames" - M must be a real count,
        # so a run that presented nothing cannot pass by accident
        m = re.search(r"overlays on (\d+) of (\d+) presented video frames", said[-1])
        if not m:
            fails.append(f"could not read the overlay count from: {said[-1]}")
        else:
            n, total = int(m.group(1)), int(m.group(2))
            if total == 0:
                fails.append("the viewer presented no frame at all, so the overlays "
                             "were never checked on a picture")
            elif n < total:
                fails.append(f"only {n} of {total} presented frames carried the "
                             "on-screen gamepad - while a picture streams the "
                             "display loop is the only thing that presents, so it "
                             "has to draw them itself")
    if shot.exists():
        shot.unlink()

    if fails:
        for f in fails:
            print("  FAIL " + f)
        print("vpad_screen_test FAILED")
        return 1
    print("vpad_screen_test OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
