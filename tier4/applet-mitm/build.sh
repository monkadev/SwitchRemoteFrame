#!/usr/bin/env bash
# Build the standalone mitm module inside a copy of the Atmosphere tree.
set -e
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# AMS_TREE picks another Atmosphere checkout (v0.7.11: ref/Atmosphere-1.12.0 for
# firmware 23); its .nsp is then named after it, next to the default build
AMS="${AMS_TREE:-$REPO/ref/Atmosphere}"
case "$AMS" in /*) ;; *) AMS="$REPO/$AMS" ;; esac
TAG="${AMS_TREE:+-$(basename "$AMS")}"
SRC=$REPO/tier4/applet-mitm
DST=$AMS/stratosphere/applet-mitm
IMG="${DKP_IMG:-devkitpro/devkita64:latest}"
UIDGID="$(id -u):$(id -g)"

mkdir -p "$DST"
# docker builds as root; reclaim ownership before touching the tree
docker run --rm -v "$AMS":/ams "$IMG" chown -R "$UIDGID" /ams/stratosphere/applet-mitm >/dev/null 2>&1 || true

# tier4 libstratosphere patch (idempotent): non-domain mitm sub-object forwarding
python3 "$SRC/patch_libstrat.py" "$AMS"

# by content, and a changed file gets the time of the copy: with the sources'
# own times an older checkout (another branch) kept the newer objects of the
# last build, and the link failed or, worse, used them
rsync -rlpgoD --checksum --delete \
  --exclude build.sh --exclude '*.nsp' --exclude patch_libstrat.py --exclude out/ --exclude build/ --exclude test/ \
  "$SRC"/ "$DST"/

# v1.0.5: the toolchain image can predate the Atmosphere tree - AMS 1.12.0
# needs the pgl API libnx master gained on 2026-09-17, and the published
# devkita64 image can carry an older libnx. Refresh the container's packages
# from the devkitPro repo first; if the pgl API is still missing, build the
# pinned libnx commit (the one Atmosphere 1.12.0 was released against).
LIBNX_PIN="45d6b0d962c4e5be7fa34778f969b0efeb173c2a"   # pgl: add new commands + the fix right after it
PREP='
set -e
echo "refresh: dkp-pacman"
dkp-pacman -Sy --noconfirm >/dev/null 2>&1 || echo "refresh: -Sy failed (offline repo?)"
dkp-pacman -Su --noconfirm >/dev/null 2>&1 || echo "refresh: -Su failed (offline repo?)"
if ! grep -q pglGetHostProgramLaunchProperty /opt/devkitpro/libnx/include/switch/services/pgl.h 2>/dev/null; then
    echo "refresh: the image libnx predates the Atmosphere tree - building libnx '"$LIBNX_PIN"'"
    curl -sSfL "https://github.com/switchbrew/libnx/archive/'"$LIBNX_PIN"'.tar.gz" | tar xz -C /tmp
    LIBNX_SRC="/tmp/libnx-'"$LIBNX_PIN"'"
    if ! make -C "$LIBNX_SRC" install -j"$(nproc)" >/tmp/libnx-build.log 2>&1; then
        echo "refresh: the full libnx install failed - its first errors:"
        grep -m10 -E "error:|Error [0-9]" /tmp/libnx-build.log || tail -20 /tmp/libnx-build.log
        # the release library and the headers are what the sysmodule needs
        echo "refresh: falling back to the release library + headers"
        make -C "$LIBNX_SRC/nx" release -j"$(nproc)" >>/tmp/libnx-build.log 2>&1 || { tail -25 /tmp/libnx-build.log; exit 1; }
        mkdir -p "$DEVKITPRO/libnx/lib"
        cp "$LIBNX_SRC/nx/lib/libnx.a" "$DEVKITPRO/libnx/lib/"
        cp -r "$LIBNX_SRC/nx/include/." "$DEVKITPRO/libnx/include/"
    fi
else
    echo "refresh: the repo libnx has the pgl API"
fi
grep -c pglGetHostProgramLaunchProperty /opt/devkitpro/libnx/include/switch/services/pgl.h \
    && echo "refresh: the pgl API is in place" \
    || { echo "refresh: FAILED to get the pgl API"; exit 1; }
'
set +e
docker run --rm -e MAKEFLAGS="${MAKEFLAGS:--j$(nproc)}" -v "$AMS":/ams -w /ams/stratosphere/applet-mitm "$IMG" bash -lc "$PREP make nx_release 2>&1"
rc=$?
set -e

docker run --rm -v "$AMS":/ams "$IMG" chown -R "$UIDGID" /ams/stratosphere/applet-mitm >/dev/null 2>&1 || true
NSP="$(find "$DST/out" -name '*.nsp' | head -1)"
[ -n "$NSP" ] && cp "$NSP" "$SRC/applet-mitm$TAG.nsp"

if [ $rc -eq 0 ] && [ -f "$SRC/applet-mitm$TAG.nsp" ]; then
  ls -l "$SRC/applet-mitm$TAG.nsp"; echo OK
else
  echo "BUILD FAILED (rc=$rc)"; exit 1
fi
