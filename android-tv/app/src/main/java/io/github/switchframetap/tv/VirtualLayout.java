/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 0.7.16: the on-screen gamepad's layout - which controls show, where, and
 *  how big. Pure Java (no Android), so ProtocolTest checks it.
 *
 *  A control's position is its centre as a fraction of the screen (0..1 each
 *  way), so a layout survives other screen sizes; the defaults are placed in
 *  dp from the corners for the screen they are made for. Saved as one line:
 *  "v1;id:x,y,scale,shown;..." (unknown ids are skipped, missing ones take
 *  their default). */
final class VirtualLayout {
    /** What a control is: a stick, the D-pad, a round button, a shoulder button, a small round one. */
    static final int STICK = 0, DPAD = 1, ROUND = 2, SHOULDER = 3, SMALL = 4;

    static final String[] IDS = { "ls", "rs", "dpad", "a", "b", "x", "y", "l", "r", "zl", "zr",
                                  "minus", "plus", "l3", "r3", "home", "capture" };
    static final float MIN_SCALE = 0.5f, MAX_SCALE = 2.0f, SCALE_STEP = 0.1f;

    static int kind(String id) {
        switch (id) {
            case "ls": case "rs": return STICK;
            case "dpad": return DPAD;
            case "a": case "b": case "x": case "y": return ROUND;
            case "l": case "r": case "zl": case "zr": return SHOULDER;
            default: return SMALL;
        }
    }
    /** The Switch button a control presses (Pad bit), -1 for sticks and the D-pad. */
    static int button(String id) {
        switch (id) {
            case "a": return Pad.A; case "b": return Pad.B; case "x": return Pad.X; case "y": return Pad.Y;
            case "l": return Pad.L; case "r": return Pad.R; case "zl": return Pad.ZL; case "zr": return Pad.ZR;
            case "minus": return Pad.MINUS; case "plus": return Pad.PLUS;
            case "l3": return Pad.LSTICK; case "r3": return Pad.RSTICK;
            case "home": return Pad.HOME; case "capture": return Pad.CAPTURE;
            default: return -1;
        }
    }
    static String label(String id) {
        switch (id) {
            case "ls": return "L stick"; case "rs": return "R stick"; case "dpad": return "D-pad";
            case "minus": return "−"; case "plus": return "+";
            case "l3": return "L3"; case "r3": return "R3"; case "home": return "HOME"; case "capture": return "◉";
            default: return id.toUpperCase(Locale.ROOT);
        }
    }
    /** The name in the editor's lists. */
    static String name(String id) {
        switch (id) {
            case "minus": return "− (Minus)"; case "plus": return "+ (Plus)";
            case "l3": return "L3 (left stick press)"; case "r3": return "R3 (right stick press)";
            case "capture": return "Capture";
            default: return label(id);
        }
    }
    /** The control's size at scale 1, dp: width and height. */
    static float widthDp(String id) {
        switch (kind(id)) {
            case STICK: return 128; case DPAD: return 124; case ROUND: return 56; case SHOULDER: return 76; default: return 40;
        }
    }
    static float heightDp(String id) { return kind(id) == SHOULDER ? 40 : widthDp(id); }

    static final class Item {
        final String id;
        float x, y, scale;
        boolean shown;
        Item(String id, float x, float y, float scale, boolean shown) {
            this.id = id; this.x = clamp01(x); this.y = clamp01(y); this.scale = clampScale(scale); this.shown = shown;
        }
        Item copy() { return new Item(id, x, y, scale, shown); }
    }

    final Item[] items = new Item[IDS.length];

    Item get(String id) {
        for (Item i : items) if (i.id.equals(id)) return i;
        return null;
    }
    List<String> hidden() {
        List<String> out = new ArrayList<>();
        for (Item i : items) if (!i.shown) out.add(i.id);
        return out;
    }
    VirtualLayout copy() {
        VirtualLayout c = new VirtualLayout();
        for (int k = 0; k < items.length; k++) c.items[k] = items[k].copy();
        return c;
    }

    static float clamp01(float v) { return Float.isNaN(v) ? 0.5f : Math.max(0f, Math.min(1f, v)); }
    static float clampScale(float v) {
        if (Float.isNaN(v)) return 1f;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.round(v * 10f) / 10f));
    }

    /** The default layout for a screen this big (dp, landscape): the left stick
     *  and the D-pad bottom left, the buttons and the right stick bottom right,
     *  the shoulders along the top (the corners stay free: the frame rate
     *  top left, the edit button top right), − and + at the top, HOME at the bottom
     *  centre. Capture, L3 and R3 start hidden (the editor adds them). */
    static VirtualLayout defaults(float wDp, float hDp) {
        float w = Math.max(wDp, 320), h = Math.max(hDp, 240);
        VirtualLayout v = new VirtualLayout();
        int k = 0;
        for (String id : IDS) {
            float x, y; boolean shown = true;
            switch (id) {
                case "ls":      x = 100;          y = h - 175; break;
                case "dpad":    x = 215;          y = h - 72;  break;
                case "rs":      x = w - 215;      y = h - 72;  break;
                case "a":       x = w - 46;       y = h - 175; break;
                case "b":       x = w - 100;      y = h - 121; break;
                case "x":       x = w - 100;      y = h - 229; break;
                case "y":       x = w - 154;      y = h - 175; break;
                case "zl":      x = 100;          y = 34;      break;
                case "l":       x = 188;          y = 34;      break;
                case "zr":      x = w - 100;      y = 34;      break;
                case "r":       x = w - 188;      y = 34;      break;
                case "minus":   x = w / 2 - 56;   y = 30;      break;
                case "plus":    x = w / 2 + 56;   y = 30;      break;
                case "home":    x = w / 2 + 34;   y = h - 30;  break;
                case "capture": x = w / 2 - 34;   y = h - 30;  shown = false; break;
                case "l3":      x = 200;          y = h - 185; shown = false; break;
                case "r3":      x = w - 230;      y = h - 165; shown = false; break;
                default:        x = w / 2;        y = h / 2;   break;
            }
            v.items[k++] = new Item(id, x / w, y / h, 1f, shown);
        }
        return v;
    }

    String serialize() {
        StringBuilder b = new StringBuilder("v1");
        for (Item i : items)
            b.append(';').append(i.id).append(':').append(String.format(Locale.ROOT, "%.4f,%.4f,%.1f,%d", i.x, i.y, i.scale, i.shown ? 1 : 0));
        return b.toString();
    }

    /** A saved layout over the defaults for this screen; the defaults alone when
     *  there is none or it cannot be read. */
    static VirtualLayout parse(String s, float wDp, float hDp) {
        VirtualLayout v = defaults(wDp, hDp);
        if (s == null || !s.startsWith("v1")) return v;
        for (String part : s.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            Item d = v.get(part.substring(0, colon));
            if (d == null) continue;
            String[] f = part.substring(colon + 1).split(",");
            if (f.length != 4) continue;
            try {
                Item n = new Item(d.id, Float.parseFloat(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2]), "1".equals(f[3]));
                for (int k = 0; k < v.items.length; k++) if (v.items[k] == d) v.items[k] = n;
            } catch (NumberFormatException ignored) { }
        }
        return v;
    }

    /* ---- what a finger on a control means ---- */

    /** A finger on a stick, from its centre (dx, dy in px, y down) with this
     *  radius -> the Switch's stick (-32767..32767, y up), full at the rim. */
    static int[] stick(float dx, float dy, float radius) {
        if (radius <= 0) return new int[]{ 0, 0 };
        float x = dx / radius, y = -dy / radius;
        float len = (float)Math.sqrt(x * x + y * y);
        if (len > 1) { x /= len; y /= len; }
        return new int[]{ Math.round(x * Pad.STICK_MAX), Math.round(y * Pad.STICK_MAX) };
    }

    /** A finger on the D-pad (from its centre, y down) -> the directions held:
     *  eight ways, nothing in the middle fifth. */
    static int dpad(float dx, float dy, float radius) {
        if (radius <= 0 || dx * dx + dy * dy < (0.2f * radius) * (0.2f * radius)) return 0;
        double angle = Math.toDegrees(Math.atan2(-dy, dx));   // 0 = right, 90 = up
        if (angle < 0) angle += 360;
        int sector = (int)Math.floor((angle + 22.5) / 45.0) % 8;
        switch (sector) {
            case 0: return 1 << Pad.RIGHT;
            case 1: return 1 << Pad.RIGHT | 1 << Pad.UP;
            case 2: return 1 << Pad.UP;
            case 3: return 1 << Pad.UP | 1 << Pad.LEFT;
            case 4: return 1 << Pad.LEFT;
            case 5: return 1 << Pad.LEFT | 1 << Pad.DOWN;
            case 6: return 1 << Pad.DOWN;
            default: return 1 << Pad.DOWN | 1 << Pad.RIGHT;
        }
    }
}
