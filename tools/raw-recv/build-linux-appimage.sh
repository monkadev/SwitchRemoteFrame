#!/usr/bin/env bash
# build-linux-appimage.sh - the Linux viewer as a single runnable AppImage.
#
#   bash tools/raw-recv/build-linux-appimage.sh [VERSION]
#
# Reuses the portable build's binary (run build-linux-portable.sh first). The
# result is one self-contained file: ./switch-remote-frame-<VERSION>-linux.AppImage
#
# Needs: appimagetool (downloaded if it is not on PATH) and mksquashfs
# (Debian/Ubuntu: squashfs-tools). On a machine with no FUSE the AppImage still
# runs with --appimage-extract-and-run; with FUSE (the usual case) just execute it.
set -euo pipefail

VERSION="${1:-1.0.0}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

PORTABLE="dist/build-linux-portable/switch-remote-frame-$VERSION-linux"
if [ ! -x "$PORTABLE/switch-remote-frame-viewer" ]; then
    echo "build-linux-portable.sh $VERSION first (no viewer at $PORTABLE)" >&2
    exit 1
fi

# appimagetool: on PATH, or download the AppImage (it needs FUSE or
# --appimage-extract-and-run, both handled below).
APPIMAGETOOL="${APPIMAGETOOL:-}"
if [ -z "$APPIMAGETOOL" ]; then
    if command -v appimagetool >/dev/null 2>&1; then
        APPIMAGETOOL=appimagetool
    else
        AI="$ROOT/appimagetool.AppImage"
        if [ ! -x "$AI" ]; then
            echo "downloading appimagetool..." >&2
            wget -q "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage" -O "$AI"
        fi
        chmod +x "$AI"
        APPIMAGETOOL="$AI --appimage-extract-and-run"
    fi
fi

BUILD="dist/build-linux-appimage"
APPDIR="$BUILD/switch-remote-frame.AppDir"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/bin" "$APPDIR/usr/share/licenses/switch-remote-frame" \
         "$APPDIR/usr/share/applications" "$APPDIR/usr/share/icons/hicolor/256x256/apps"

cp "$PORTABLE/switch-remote-frame-viewer" "$APPDIR/usr/bin/"
cp "$PORTABLE"/licenses/* "$APPDIR/usr/share/licenses/switch-remote-frame/"

# the icon (the same artwork as the Android app)
if command -v convert >/dev/null 2>&1; then
    convert tools/raw-recv/switch-remote-frame.svg -background none -resize 256x256 \
        "$APPDIR/switch-remote-frame.png" 2>/dev/null || true
fi
if [ ! -f "$APPDIR/switch-remote-frame.png" ]; then
    cp "$PORTABLE/switch-remote-frame.svg" "$APPDIR/switch-remote-frame.svg"
    cp "$PORTABLE/switch-remote-frame.svg" "$APPDIR/usr/share/icons/hicolor/256x256/apps/switch-remote-frame.svg"
else
    cp "$APPDIR/switch-remote-frame.png" "$APPDIR/usr/share/icons/hicolor/256x256/apps/switch-remote-frame.png"
fi

cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
# Switch Remote Frame - the PC viewer, from its own AppImage.
exec "$APPDIR/usr/bin/switch-remote-frame-viewer" "$@"
EOF
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/switch-remote-frame.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Switch Remote Frame
Comment=Watch and record your Switch, streamed over USB or the network (PC viewer with mouse, keyboard and gamepad control)
Exec=switch-remote-frame-viewer --app
Icon=switch-remote-frame
Terminal=false
Categories=Game;Utility;
StartupNotify=true
Keywords=Switch;Nintendo;stream;viewer;game;
EOF
cp "$APPDIR/switch-remote-frame.desktop" "$APPDIR/usr/share/applications/"

OUT="dist/switch-remote-frame-$VERSION-linux.AppImage"
rm -f "$OUT"
echo "== building $OUT"
$APPIMAGETOOL "$APPDIR" "$OUT"
chmod +x "$OUT"
echo "== $OUT"
ls -la "$OUT"
