#!/usr/bin/env python3
"""
chrome_op_test.py - the Settings button's opacity slider.

The PC viewer's Settings button can be made see-through, all the way to 0% -
at which point it is not drawn until the pointer finds it, but it is still
there and still takes the click, so a game can have the corner to itself
without losing the way into Settings. Since v1.0.5 the setting is a slider
from 100% down to 0% (it was five choices before that, and for one version
the button also minimized into a bar with its own setting - both gone).
Since v1.0.5 the button also fades itself out 2 s after the mouse stops and
comes back on any movement; that half is checked by vpad_screen_test.py's
--test-chrome pass, with a picture on screen.

--test-op opens Settings, scrolls to the row and presses the slider at its
left end, its middle and its right end - through the same routing a click
goes through - then puts it back the way it found it. The viewer reports
where each press landed:

    test-op: slider left edge=0%, middle=50%, right edge=100%
    test-op: restored 100%

App mode writes its stderr to $XDG_STATE_HOME/switch-remote-frame/viewer.log
(the "opens like an app, says nothing in a terminal" change), so the log is
what there is to read. viewer.ini goes through SDL_GetPrefPath, which follows
$HOME, so HOME is pointed at a scratch directory: the test neither reads nor
leaves behind anything in the real one.

  python3 tools/raw-recv/chrome_op_test.py
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
VIEW = Path(os.environ["SFT_VIEW"]) if os.environ.get("SFT_VIEW") \
    else REPO / "tools/raw-recv/raw-view"

SLIDER = re.compile(r"slider left edge=(\d+)%, middle=(\d+)%, right edge=(\d+)%")
RESTORED = re.compile(r"restored (\d+)%")


def main():
    if not VIEW.exists():
        subprocess.run(["make", "-C", str(VIEW.parent), "raw-view"], check=True)
    scratch = Path(tempfile.mkdtemp(prefix="sft-chrome-"))
    env = dict(os.environ)
    env["SDL_VIDEODRIVER"] = "dummy"
    env["SDL_RENDER_DRIVER"] = "software"
    env["XDG_STATE_HOME"] = str(scratch)
    env["HOME"] = str(scratch)          # viewer.ini follows $HOME
    env.pop("XDG_CONFIG_HOME", None)
    proc = subprocess.Popen([str(VIEW), "--app", "--no-connect", "--test-op"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    try:
        time.sleep(26)
    finally:
        proc.terminate()
        try:
            proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.communicate()

    log = scratch / "switch-remote-frame" / "viewer.log"
    text = log.read_text(errors="replace") if log.exists() else ""
    ini = scratch / ".local/share/switch-remote-frame/viewer/viewer.ini"
    saved = ini.read_text(errors="replace") if ini.exists() else ""
    shutil.rmtree(scratch, ignore_errors=True)

    fails = []
    if not text:
        print("  FAIL the viewer wrote no log, so there is nothing to check")
        print("chrome_op_test FAILED")
        return 1

    ms = SLIDER.search(text)
    mr = RESTORED.search(text)
    if ms:
        print("  " + ms.group(0))
    if mr:
        print("  " + mr.group(0))
    if "minimized bar" in text:
        fails.append("the log still mentions the minimized bar - it and its "
                     "opacity setting were removed")
    if "choice(s)" in text:
        fails.append("the log still lists choices to step through - the row is "
                     "a slider now")
    if not ms:
        fails.append("the log never reported the slider's ends, so the row was "
                     "not reached in Settings")
    else:
        lo, mid, hi = (int(g) for g in ms.groups())
        if lo != 0:
            fails.append(f"the left end gave {lo}% - pressing there should land on 0%")
        if not 40 <= mid <= 60:
            fails.append(f"the middle gave {mid}% - it should land near 50%")
        if hi != 100:
            fails.append(f"the right end gave {hi}% - it should land on 100%")
        if lo == 0 and 40 <= mid <= 60 and hi == 100:
            print("  the slider runs from 0% to 100%")
    if not mr:
        fails.append("the row was not put back the way it was found")
    elif "set_op=" not in saved:
        fails.append("the opacity was not written to viewer.ini")
    else:
        want = f"set_op={mr.group(1)}"
        if want not in saved:
            fails.append(f"viewer.ini does not carry the restored {want}")
        else:
            print("  it is remembered in viewer.ini")

    for f in fails:
        print("  FAIL " + f)
    if fails:
        print("chrome_op_test FAILED")
        return 1
    print("chrome_op_test OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
