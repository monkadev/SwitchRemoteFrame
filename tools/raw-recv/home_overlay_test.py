#!/usr/bin/env python3
"""Regression: Gamepad settings and the on-screen gamepad editor opened from
Settings must be drawn over the disconnected home screen, without a video frame.
"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
VIEW = Path(os.environ["SFT_VIEW"]) if os.environ.get("SFT_VIEW") else REPO / "tools/raw-recv/raw-view"


def main():
    if not VIEW.exists():
        subprocess.run(["make", "-C", str(VIEW.parent), "raw-view"], check=True)
    scratch = Path(tempfile.mkdtemp(prefix="sft-home-overlay-"))
    env = dict(os.environ, SDL_VIDEODRIVER="dummy", SDL_RENDER_DRIVER="software",
               HOME=str(scratch), XDG_STATE_HOME=str(scratch))
    env.pop("XDG_CONFIG_HOME", None)
    try:
        shot = scratch / "home.bmp"
        result = subprocess.run([str(VIEW), "--app", "--no-connect", "--test-home-overlays",
                                 "--seconds", "2", "--shot", str(shot)], env=env, capture_output=True,
                                text=True, timeout=20)
        log = scratch / "switch-remote-frame" / "viewer.log"
        text = log.read_text(errors="replace") if log.exists() else result.stderr
        lines = [x for x in text.splitlines() if "test-home-overlays:" in x]
        line = lines[0] if lines else ""
        if "Settings visible -> Gamepad visible; layout editor visible" not in line:
            print("  FAIL disconnected overlays not confirmed:", line or "no test log")
            return 1
        # v1.1.1: no gamepad on the plain home menu, and the modals survive
        # the home loop's 50 ms repaints and wheel events without flickering.
        steady = next((x for x in lines if "steady" in x), "")
        if "hides the gamepad yes, its Show button yes; gamepad page steady yes; layout editor steady yes; scrim single composite yes" not in steady:
            print("  FAIL home gamepad/flicker regression:", steady or "no steady line")
            return 1
        for x in lines:
            print("  " + x.strip())
        print("home_overlay_test OK")
        return 0
    except subprocess.TimeoutExpired:
        print("  FAIL viewer did not exit in time")
        return 1
    finally:
        shutil.rmtree(scratch, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
