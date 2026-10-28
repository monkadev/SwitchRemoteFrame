#!/usr/bin/env python3
"""
ip_edit_test.py - v1.1.3: the Switch IP field's editing state and its
Confirm / Cancel buttons.

The field shows gray text while idle and white text while typing, and editing
reveals Confirm and Cancel buttons under the box. While editing, ui_event is
bypassed so the keyboard stays text-only; the buttons therefore go through
ui_ip_edit_event instead. --test-ip-edit drives the whole routing headlessly:

  1. open the field with a sample address,
  2. click Cancel - editing ends, the saved address is untouched,
  3. click the field again - editing reopens - then type a new address,
  4. click Confirm - the new address is validated and written to prefs.

App mode writes its stderr to $XDG_STATE_HOME/switch-remote-frame/viewer.log,
so the log plus the rewritten viewer.ini are what there is to check.

  python3 tools/raw-recv/ip_edit_test.py
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

OPENED = "test-ip-edit: editing opened"
CANCEL = "test-ip-edit: cancel clicked, editing=0 last_ip=10.0.0.25"
REOPEN = "test-ip-edit: field clicked again, editing=1"
TYPED = "test-ip-edit: typed, buffer=192.168.1.42"
CONFIRM = "test-ip-edit: confirm clicked, editing=0 last_ip=192.168.1.42"


def main():
    if not VIEW.exists():
        subprocess.run(["make", "-C", str(VIEW.parent), "raw-view"], check=True)
    scratch = Path(tempfile.mkdtemp(prefix="sft-ip-edit-"))
    data = scratch / "share"
    state = scratch / "state"
    prefs_dir = data / "switch-remote-frame" / "viewer"
    prefs_dir.mkdir(parents=True)
    (prefs_dir / "viewer.ini").write_text("connection=network\nlast_ip=10.0.0.25\n")
    env = dict(os.environ)
    env["SDL_VIDEODRIVER"] = "dummy"
    env["SDL_RENDER_DRIVER"] = "software"
    env["XDG_DATA_HOME"] = str(data)
    env["XDG_STATE_HOME"] = str(state)
    proc = subprocess.Popen([str(VIEW), "--app", "--no-connect", "--test-ip-edit"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    try:
        time.sleep(12)      # four steps, 2 s apart, plus slack
    finally:
        proc.terminate()
        try:
            proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.communicate()

    log = state / "switch-remote-frame" / "viewer.log"
    text = log.read_text(errors="replace") if log.exists() else ""
    ini = (prefs_dir / "viewer.ini").read_text(errors="replace")
    shutil.rmtree(scratch, ignore_errors=True)
    if not text:
        print("  FAIL the viewer wrote no log, so there is nothing to check")
        print("ip_edit_test FAILED")
        return 1

    markers = [OPENED, CANCEL, REOPEN, TYPED, CONFIRM]
    positions = [text.find(m) for m in markers]
    fails = []
    for marker, pos in zip(markers, positions):
        if pos < 0:
            fails.append(f"a step never ran (no \"{marker}\")")
    if not fails and positions != sorted(positions):
        fails.append(f"the steps ran in the wrong order ({positions})")
    saved = next((ln.split("=", 1)[1] for ln in ini.splitlines()
                  if ln.startswith("last_ip=")), "")
    if saved != "192.168.1.42":
        fails.append(f"Confirm did not persist the typed address (last_ip={saved!r})")
    if fails:
        for f in fails:
            print("  FAIL " + f)
        print("ip_edit_test FAILED")
        return 1
    print("  Cancel discarded the edit, Confirm validated and saved it, and the "
          "field's own buttons routed while editing")
    print("ip_edit_test OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
