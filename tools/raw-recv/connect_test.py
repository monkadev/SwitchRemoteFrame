#!/usr/bin/env python3
"""
connect_test.py - v1.0.3: the home screen's Connect / Disconnect button.

The Android app's home card has one pill that reads Connect, or Disconnect while
it is looking for the Switch or connected to one, and its Settings drawer has a
Disconnect in the top bar. The PC viewer had neither: it looked for the console
from the moment it opened and there was no way to tell it to stop.

--test-connect presses that button twice - and then the card's Settings button
(v1.0.4) - through the same routing a click goes through, and the viewer says in
its log which state it is in:

    idle: not looking for the Switch (Connect to start)
    connect: looking for the Switch (USB)
    disconnect: stopped looking for the Switch - Connect to start again

App mode writes its stderr to $XDG_STATE_HOME/switch-remote-frame/viewer.log
(that is the v1.0.2 "opens like an app, says nothing in a terminal" change), so
the log is what there is to read.

  python3 tools/raw-recv/connect_test.py
"""
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
VIEW = Path(os.environ["SFT_VIEW"]) if os.environ.get("SFT_VIEW") \
    else REPO / "tools/raw-recv/raw-view"

IDLE = "idle: not looking for the Switch"
CONNECT = "connect: looking for the Switch"
DISCONNECT = "disconnect: stopped looking for the Switch"
SETTINGS = "pressed the card's Settings button -> the drawer opened"


def main():
    if not VIEW.exists():
        subprocess.run(["make", "-C", str(VIEW.parent), "raw-view"], check=True)
    state = Path(tempfile.mkdtemp(prefix="sft-connect-"))
    env = dict(os.environ)
    env["SDL_VIDEODRIVER"] = "dummy"
    env["SDL_RENDER_DRIVER"] = "software"
    env["XDG_STATE_HOME"] = str(state)
    # --no-connect: open on the home screen looking for nothing, as after a
    # Disconnect. --test-connect then presses the button twice, 2 s apart.
    proc = subprocess.Popen([str(VIEW), "--app", "--no-connect", "--test-connect"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    try:
        time.sleep(11)      # three presses, 2 s apart, plus slack
    finally:
        proc.terminate()
        try:
            proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.communicate()

    log = state / "switch-remote-frame" / "viewer.log"
    text = log.read_text(errors="replace") if log.exists() else ""
    shutil.rmtree(state, ignore_errors=True)
    if not text:
        print("  FAIL the viewer wrote no log, so there is nothing to check")
        print("connect_test FAILED")
        return 1

    def at(needle):
        return text.find(needle)

    fails = []
    i_idle, i_conn, i_disc, i_set = at(IDLE), at(CONNECT), at(DISCONNECT), at(SETTINGS)
    print(f"  log: idle at {i_idle}, connect at {i_conn}, disconnect at {i_disc}, settings at {i_set}")
    if i_idle < 0:
        fails.append(f"the home screen never said it was idle (no \"{IDLE}\")")
    if i_conn < 0:
        fails.append(f"pressing Connect did nothing (no \"{CONNECT}\")")
    if i_disc < 0:
        fails.append(f"pressing it again did not disconnect (no \"{DISCONNECT}\")")
    if i_set < 0:
        fails.append("pressing the card's Settings button did not open the drawer "
                     f"(no \"{SETTINGS}\")")
    if "the drawer did not open" in text:
        fails.append("pressing the card's Settings button reported the drawer did "
                     "not open")
    if not fails:
        if not (i_idle < i_conn < i_disc < i_set):
            fails.append(f"the presses came in the wrong order "
                         f"(idle {i_idle}, connect {i_conn}, disconnect {i_disc}, settings {i_set})")
        else:
            print("  Connect started the search, the same button stopped it, and "
                  "the card's Settings button opened the drawer")
    for f in fails:
        print("  FAIL " + f)
    if fails:
        print("connect_test FAILED")
        return 1
    print("connect_test OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
