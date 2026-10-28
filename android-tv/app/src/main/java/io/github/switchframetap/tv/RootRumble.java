/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 0.7.16: rumble for Xbox controllers over Bluetooth where the kernel cannot
 *  (no rumble in its hid-microsoft driver - Linux 4.14 kernels such as the
 *  Xiaomi Pad 5's - so Android sees no motor). With root, the app writes the
 *  same 9-byte output report Linux's hid-microsoft sends (report 3: enable
 *  both motors, the left "strong" and right "weak" magnitude 0..100, the
 *  longest duration) to the controller's /dev/hidrawN, and Android's
 *  Bluetooth stack (uhid) hands it to the controller.
 *
 *  One "su" process per controller runs `dd bs=9` into its hidraw node and
 *  gets one report per write, so every report reaches the node whole. Nothing
 *  here runs until the user turns "Vibration through root" on. */
final class RootRumble {
    /** Xbox controllers that take the report (Linux hid-microsoft's list):
     *  One S 1708 (old / new firmware), Elite 2 (old / new), Series X|S. */
    static final int VENDOR = 0x045E;
    static final int[] PRODUCTS = { 0x02E0, 0x02FD, 0x0B20, 0x0B05, 0x0B22, 0x0B13 };
    static final int REPORT_SIZE = 9;
    private static final String[] SU_PATHS = { "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/debug_ramdisk/su", "/system_ext/bin/su" };

    /** What a scan found. */
    static final class Scan {
        final List<String> nodes;      // hidrawN of the Xbox controllers
        final List<String> names;
        final String error;            // why nothing could be done (null: it worked)
        Scan(List<String> nodes, List<String> names, String error) { this.nodes = nodes; this.names = names; this.error = error; }
    }

    private static final class Writer {
        final String node; final Process process; final OutputStream out;
        Writer(String node, Process process) { this.node = node; this.process = process; this.out = process.getOutputStream(); }
    }
    private volatile List<Writer> writers = Collections.emptyList();
    private volatile Scan last;

    /** Is there an su binary? (looked up - nothing is run, no prompt) */
    static boolean suPresent() {
        for (String p : SU_PATHS) if (new File(p).exists()) return true;
        String path = System.getenv("PATH");
        if (path != null) for (String dir : path.split(":")) if (!dir.isEmpty() && new File(dir, "su").exists()) return true;
        return false;
    }

    /** The output report for these motor strengths (0..100 each). */
    static byte[] report(int strong, int weak) {
        // 2.55 s, played once: refreshed every second while it rumbles, so it stops
        // on its own soon if the app goes away mid-rumble (Linux loops it 255 times)
        return new byte[]{ 3, 0x03, 0, 0, (byte)clamp100(strong), (byte)clamp100(weak), (byte)0xFF, 0, 0 };
    }
    static int clamp100(int v) { return Math.max(0, Math.min(100, v)); }
    /** An Android amplitude (0..255) -> the report's 0..100. */
    static int percent(int amplitude) { return amplitude <= 0 ? 0 : Math.max(1, Math.round(clamp255(amplitude) * 100 / 255f)); }
    private static int clamp255(int v) { return Math.max(0, Math.min(255, v)); }

    /** Is this uevent's HID_ID / HID_NAME an Xbox controller on Bluetooth (bus 5)? */
    static boolean matches(String hidId, String name) {
        if (hidId == null) return false;
        String[] f = hidId.trim().split(":");
        if (f.length != 3) return false;
        try {
            int bus = Integer.parseInt(f[0], 16), vendor = (int)Long.parseLong(f[1], 16), product = (int)Long.parseLong(f[2], 16);
            if (bus != 5 || vendor != VENDOR) return false;
            for (int p : PRODUCTS) if (p == product) return true;
            return name != null && name.toLowerCase(Locale.ROOT).contains("xbox");
        } catch (NumberFormatException e) { return false; }
    }

    /** Find the controllers' hidraw nodes as root (the first time, the root
     *  manager asks) and open one writer each. Blocking: not on the UI thread. */
    synchronized Scan rescan() {
        if (!suPresent()) return finish(new ArrayList<>(), new ArrayList<>(), "No root (su) found on this device");
        List<String> nodes = new ArrayList<>(), names = new ArrayList<>();
        String script = "for d in /sys/class/hidraw/hidraw*; do [ -e \"$d/device/uevent\" ] || continue; "
                + "i=$(grep '^HID_ID=' \"$d/device/uevent\"); n=$(grep '^HID_NAME=' \"$d/device/uevent\"); "
                + "echo \"SFT ${d##*/} ${i#HID_ID=} ${n#HID_NAME=}\"; done; echo SFT_END\nexit\n";
        boolean ended = false;
        try {
            Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
            OutputStream in = p.getOutputStream();
            in.write(script.getBytes(StandardCharsets.UTF_8)); in.flush(); in.close();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.equals("SFT_END")) { ended = true; continue; }
                if (!line.startsWith("SFT ")) continue;
                String[] f = line.substring(4).split(" ", 3);
                if (f.length >= 2 && f[0].matches("hidraw\\d+") && matches(f[1], f.length > 2 ? f[2] : null)) {
                    nodes.add(f[0]); names.add(f.length > 2 ? f[2] : f[0]);
                }
            }
            p.waitFor();   // the root manager answers (or times out its prompt) by itself
        } catch (Exception e) {
            return finish(nodes, names, "Root could not be used: " + e.getMessage());
        }
        if (!ended) return finish(nodes, names, "Root was refused");
        if (nodes.isEmpty()) return finish(nodes, names, "No Xbox controller connected over Bluetooth was found");
        return finish(nodes, names, null);
    }

    private Scan finish(List<String> nodes, List<String> names, String error) {
        List<Writer> fresh = new ArrayList<>();
        if (error == null) {
            for (String node : nodes) {
                try {
                    Process p = new ProcessBuilder("su", "-c", "exec dd of=/dev/" + node + " bs=" + REPORT_SIZE + " 2>/dev/null").start();
                    fresh.add(new Writer(node, p));
                } catch (Exception e) {
                    error = "Root could not be used: " + e.getMessage();
                }
            }
        }
        List<Writer> old = writers;
        writers = fresh;
        for (Writer w : old) close(w);
        Scan s = new Scan(nodes, names, error);
        last = s;
        return s;
    }

    Scan last() { return last; }
    boolean active() { return !writers.isEmpty(); }

    /** Both motors (0..100) on every controller found; a controller that went
     *  away is dropped (its dd ended). */
    void send(int strong, int weak) {
        List<Writer> ws = writers;
        if (ws.isEmpty()) return;
        byte[] r = report(strong, weak);
        List<Writer> dead = null;
        for (Writer w : ws) {
            try { w.out.write(r); w.out.flush(); }
            catch (Exception e) { if (dead == null) dead = new ArrayList<>(); dead.add(w); }
        }
        if (dead != null) {
            List<Writer> keep = new ArrayList<>(ws);
            keep.removeAll(dead);
            writers = keep;
            for (Writer w : dead) close(w);
        }
    }

    void release() {
        List<Writer> old = writers;
        writers = Collections.emptyList();
        for (Writer w : old) close(w);
    }

    private static void close(Writer w) {
        try { w.out.close(); } catch (Exception ignored) { }
        w.process.destroy();
    }
}
