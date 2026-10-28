/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Remote gamepad (Switch Frame Tap 0.7.16+): the controller connected to this
 *  device plays on the Switch as a Pro Controller. Plain Java (Android's key and
 *  axis numbers are written out below), so ProtocolTest checks it on the PC;
 *  PadInput feeds it Android's events.
 *
 *  The wire format is the console's tier4/applet-mitm/source/sft_pad_proto.hpp:
 *  32 bytes, little-endian - "SFTG", u8 version 1, u8 flags (bit0: a gamepad is
 *  there), u16 sequence, u32 Switch buttons, s16 lx, ly, rx, ry (-32767..32767,
 *  y up), 12 reserved. Each message is the whole controller; consoles before
 *  0.7.16 skip it. */
final class Pad {
    static final int MSG_SIZE = 32, VERSION = 1, FLAG_CONNECTED = 1;
    static final int STICK_MAX = 32767;
    /** While anything is held, the state is repeated this often: the console lets
     *  go of everything it has not heard about for 1 s (a viewer that froze). */
    static final long KEEPALIVE_NS = 250_000_000L;

    /* ---- the Switch's buttons (HidNpadButton / HiddbgNpadButton bits) ---- */
    static final int A = 0, B = 1, X = 2, Y = 3, LSTICK = 4, RSTICK = 5, L = 6, R = 7, ZL = 8, ZR = 9,
            PLUS = 10, MINUS = 11, LEFT = 12, UP = 13, RIGHT = 14, DOWN = 15, HOME = 18, CAPTURE = 19;
    static final int BUTTON_BITS = 20;
    static final int VALID = 0xFFFF | (1 << HOME) | (1 << CAPTURE);
    /** The order of the remap list. */
    static final int[] BUTTONS = { A, B, X, Y, L, R, ZL, ZR, MINUS, PLUS, LSTICK, RSTICK, UP, DOWN, LEFT, RIGHT, HOME, CAPTURE };

    static String buttonName(int bit) {
        switch (bit) {
            case A: return "A"; case B: return "B"; case X: return "X"; case Y: return "Y";
            case L: return "L"; case R: return "R"; case ZL: return "ZL"; case ZR: return "ZR";
            case MINUS: return "− (Minus)"; case PLUS: return "+ (Plus)";
            case LSTICK: return "Left stick press"; case RSTICK: return "Right stick press";
            case UP: return "D-pad up"; case DOWN: return "D-pad down"; case LEFT: return "D-pad left"; case RIGHT: return "D-pad right";
            case HOME: return "HOME"; case CAPTURE: return "Capture";
            default: return "Button " + bit;
        }
    }

    /* ---- Android's numbers (android.view.KeyEvent / MotionEvent) ---- */
    static final int KEY_BACK = 4, KEY_DPAD_UP = 19, KEY_DPAD_DOWN = 20, KEY_DPAD_LEFT = 21, KEY_DPAD_RIGHT = 22,
            KEY_DPAD_CENTER = 23, KEY_BUTTON_A = 96, KEY_BUTTON_B = 97, KEY_BUTTON_C = 98, KEY_BUTTON_X = 99,
            KEY_BUTTON_Y = 100, KEY_BUTTON_Z = 101, KEY_BUTTON_L1 = 102, KEY_BUTTON_R1 = 103, KEY_BUTTON_L2 = 104,
            KEY_BUTTON_R2 = 105, KEY_BUTTON_THUMBL = 106, KEY_BUTTON_THUMBR = 107, KEY_BUTTON_START = 108,
            KEY_BUTTON_SELECT = 109, KEY_BUTTON_MODE = 110, KEY_BUTTON_1 = 188, KEY_BUTTON_16 = 203;
    static final int AXIS_X = 0, AXIS_Y = 1, AXIS_Z = 11, AXIS_RX = 12, AXIS_RY = 13, AXIS_RZ = 14,
            AXIS_HAT_X = 15, AXIS_HAT_Y = 16, AXIS_LTRIGGER = 17, AXIS_RTRIGGER = 18, AXIS_GAS = 22, AXIS_BRAKE = 23;
    static final int MAX_AXIS = 48, MAX_KEY = 512;

    /* ---- input codes: what a Switch button is bound to ----
     * An Android key code as it is (1..511); an axis pushed one way,
     * AXIS_BASE + axis * 2 (+1 for the negative side); or the device's own
     * analog trigger, whichever axis it reports it on. */
    static final int AXIS_BASE = 10000, TRIGGER_L = 20001, TRIGGER_R = 20002;
    /** An axis counts as a pressed button beyond this. */
    static final float AXIS_PRESS = 0.5f;

    static int axisCode(int axis, boolean negative) { return AXIS_BASE + axis * 2 + (negative ? 1 : 0); }
    static boolean isAxisCode(int code) { return code >= AXIS_BASE && code < AXIS_BASE + MAX_AXIS * 2; }
    static int axisOf(int code) { return (code - AXIS_BASE) / 2; }
    static boolean axisNegative(int code) { return ((code - AXIS_BASE) & 1) == 1; }
    static boolean isKeyCode(int code) { return code > 0 && code < MAX_KEY; }

    /** A short name for an input code; null for a key this table does not know
     *  (PadInput then asks Android). */
    static String codeName(int code) {
        switch (code) {
            case KEY_BUTTON_A: return "A"; case KEY_BUTTON_B: return "B"; case KEY_BUTTON_X: return "X"; case KEY_BUTTON_Y: return "Y";
            case KEY_BUTTON_C: return "C"; case KEY_BUTTON_Z: return "Z";
            case KEY_BUTTON_L1: return "L1 / LB"; case KEY_BUTTON_R1: return "R1 / RB";
            case KEY_BUTTON_L2: return "L2 / LT"; case KEY_BUTTON_R2: return "R2 / RT";
            case KEY_BUTTON_THUMBL: return "L3"; case KEY_BUTTON_THUMBR: return "R3";
            case KEY_BUTTON_START: return "Start / Menu"; case KEY_BUTTON_SELECT: return "Select / View";
            case KEY_BUTTON_MODE: return "Guide / Home"; case KEY_BACK: return "Back";
            case KEY_DPAD_UP: return "D-pad up"; case KEY_DPAD_DOWN: return "D-pad down";
            case KEY_DPAD_LEFT: return "D-pad left"; case KEY_DPAD_RIGHT: return "D-pad right";
            case KEY_DPAD_CENTER: return "D-pad centre";
            case TRIGGER_L: return "Left trigger"; case TRIGGER_R: return "Right trigger";
            default: break;
        }
        if (code >= KEY_BUTTON_1 && code <= KEY_BUTTON_16) return "Button " + (code - KEY_BUTTON_1 + 1);
        if (isAxisCode(code)) {
            int a = axisOf(code);
            boolean neg = axisNegative(code);
            switch (a) {
                case AXIS_HAT_X: return neg ? "D-pad left (hat)" : "D-pad right (hat)";
                case AXIS_HAT_Y: return neg ? "D-pad up (hat)" : "D-pad down (hat)";
                case AXIS_X: return neg ? "Left stick left" : "Left stick right";
                case AXIS_Y: return neg ? "Left stick up" : "Left stick down";
                default: return "Axis " + a + (neg ? " −" : " +");
            }
        }
        return null;
    }

    /* ---- the controller's state, and its message ---- */
    static final class State {
        final boolean connected;
        final int buttons, lx, ly, rx, ry;
        State(boolean connected, int buttons, int lx, int ly, int rx, int ry) {
            this.connected = connected;
            this.buttons = connected ? buttons & VALID : 0;
            this.lx = connected ? clampStick(lx) : 0; this.ly = connected ? clampStick(ly) : 0;
            this.rx = connected ? clampStick(rx) : 0; this.ry = connected ? clampStick(ry) : 0;
        }
        /** No gamepad: the console takes its virtual controller away. */
        static final State NONE = new State(false, 0, 0, 0, 0, 0);
        /** A gamepad, nothing held. */
        static final State IDLE = new State(true, 0, 0, 0, 0, 0);
        boolean neutral() { return buttons == 0 && lx == 0 && ly == 0 && rx == 0 && ry == 0; }
        boolean pressed(int bit) { return (buttons & (1 << bit)) != 0; }
        State without(int mask) { return new State(connected, buttons & ~mask, lx, ly, rx, ry); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof State)) return false;
            State s = (State)o;
            return connected == s.connected && buttons == s.buttons && lx == s.lx && ly == s.ly && rx == s.rx && ry == s.ry;
        }
        @Override public int hashCode() { return Arrays.hashCode(new int[]{ connected ? 1 : 0, buttons, lx, ly, rx, ry }); }
    }
    static int clampStick(int v) { return v < -STICK_MAX ? -STICK_MAX : Math.min(v, STICK_MAX); }

    static byte[] encode(State s, int seq) {
        byte[] m = new byte[MSG_SIZE];
        m[0] = 'S'; m[1] = 'F'; m[2] = 'T'; m[3] = 'G';
        m[4] = (byte)VERSION; m[5] = (byte)(s.connected ? FLAG_CONNECTED : 0);
        m[6] = (byte)seq; m[7] = (byte)(seq >>> 8);
        int b = s.buttons;
        m[8] = (byte)b; m[9] = (byte)(b >>> 8); m[10] = (byte)(b >>> 16); m[11] = (byte)(b >>> 24);
        int[] v = { s.lx, s.ly, s.rx, s.ry };
        for (int i = 0; i < 4; i++) { m[12 + i * 2] = (byte)v[i]; m[13 + i * 2] = (byte)(v[i] >>> 8); }
        return m;
    }

    /** A stick (Android: -1..1, y down) -> the Switch's (-32767..32767, y up),
     *  with a round dead zone and the rest of the travel stretched to full. */
    static int[] stick(float x, float y, float deadzone) {
        double mag = Math.sqrt((double)x * x + (double)y * y);
        if (mag <= deadzone || mag == 0) return new int[]{ 0, 0 };
        double scaled = Math.min(1.0, (mag - deadzone) / (1.0 - deadzone));
        double k = scaled / mag;
        return new int[]{ clampStick((int)Math.round(x * k * STICK_MAX)), clampStick((int)Math.round(-y * k * STICK_MAX)) };
    }

    /* ---- the mapping: Switch button -> input codes ---- */
    static final class Mapping {
        final int[][] codes = new int[BUTTON_BITS][];
        Mapping() { for (int i = 0; i < BUTTON_BITS; i++) codes[i] = new int[0]; }

        /** byPosition: the face buttons where a Switch controller has them (the
         *  bottom one is B); otherwise by their letters (the one marked A is A). */
        static Mapping defaults(boolean byPosition) {
            Mapping m = new Mapping();
            m.face(byPosition);
            m.codes[L] = new int[]{ KEY_BUTTON_L1 };
            m.codes[R] = new int[]{ KEY_BUTTON_R1 };
            m.codes[ZL] = new int[]{ KEY_BUTTON_L2, TRIGGER_L };
            m.codes[ZR] = new int[]{ KEY_BUTTON_R2, TRIGGER_R };
            m.codes[MINUS] = new int[]{ KEY_BUTTON_SELECT };
            m.codes[PLUS] = new int[]{ KEY_BUTTON_START };
            m.codes[LSTICK] = new int[]{ KEY_BUTTON_THUMBL };
            m.codes[RSTICK] = new int[]{ KEY_BUTTON_THUMBR };
            m.codes[UP] = new int[]{ KEY_DPAD_UP, axisCode(AXIS_HAT_Y, true) };
            m.codes[DOWN] = new int[]{ KEY_DPAD_DOWN, axisCode(AXIS_HAT_Y, false) };
            m.codes[LEFT] = new int[]{ KEY_DPAD_LEFT, axisCode(AXIS_HAT_X, true) };
            m.codes[RIGHT] = new int[]{ KEY_DPAD_RIGHT, axisCode(AXIS_HAT_X, false) };
            m.codes[HOME] = new int[]{ KEY_BUTTON_MODE };
            return m;
        }
        /** Only A, B, X and Y, from a preset. */
        void face(boolean byPosition) {
            codes[A] = new int[]{ byPosition ? KEY_BUTTON_B : KEY_BUTTON_A };
            codes[B] = new int[]{ byPosition ? KEY_BUTTON_A : KEY_BUTTON_B };
            codes[X] = new int[]{ byPosition ? KEY_BUTTON_Y : KEY_BUTTON_X };
            codes[Y] = new int[]{ byPosition ? KEY_BUTTON_X : KEY_BUTTON_Y };
        }
        /** Which face-button preset this is: 1 positions, 2 letters, 0 neither. */
        int facePreset() {
            Mapping p = new Mapping(); p.face(true);
            Mapping l = new Mapping(); l.face(false);
            boolean isP = true, isL = true;
            for (int b : new int[]{ A, B, X, Y }) {
                isP &= Arrays.equals(codes[b], p.codes[b]);
                isL &= Arrays.equals(codes[b], l.codes[b]);
            }
            return isP ? 1 : isL ? 2 : 0;
        }
        /** Bind one input to this button, and to no other button. */
        void bind(int bit, int code) {
            for (int i = 0; i < BUTTON_BITS; i++) codes[i] = remove(codes[i], code);
            codes[bit] = new int[]{ code };
        }
        void clear(int bit) { codes[bit] = new int[0]; }
        Mapping copy() {
            Mapping m = new Mapping();
            for (int i = 0; i < BUTTON_BITS; i++) m.codes[i] = codes[i].clone();
            return m;
        }
        /** "0:97;1:96;8:104,20001;..." - every button, bound or not. */
        String serialize() {
            StringBuilder sb = new StringBuilder();
            for (int b : BUTTONS) {
                if (sb.length() > 0) sb.append(';');
                sb.append(b).append(':');
                for (int i = 0; i < codes[b].length; i++) { if (i > 0) sb.append(','); sb.append(codes[b][i]); }
            }
            return sb.toString();
        }
        /** The saved mapping; buttons it does not mention keep the defaults. */
        static Mapping parse(String s, Mapping defaults) {
            Mapping m = defaults.copy();
            if (s == null || s.isEmpty()) return m;
            for (String part : s.split(";")) {
                int colon = part.indexOf(':');
                if (colon <= 0) continue;
                try {
                    int bit = Integer.parseInt(part.substring(0, colon).trim());
                    if (bit < 0 || bit >= BUTTON_BITS || ((1 << bit) & VALID) == 0) continue;
                    List<Integer> list = new ArrayList<>();
                    String rest = part.substring(colon + 1).trim();
                    if (!rest.isEmpty()) for (String c : rest.split(",")) {
                        int code = Integer.parseInt(c.trim());
                        if (isKeyCode(code) || isAxisCode(code) || code == TRIGGER_L || code == TRIGGER_R) list.add(code);
                    }
                    int[] arr = new int[list.size()];
                    for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
                    m.codes[bit] = arr;
                } catch (NumberFormatException ignored) { }
            }
            return m;
        }
        /** What this button is bound to, for the remap list. */
        String describe(int bit, CodeNamer namer) {
            if (codes[bit].length == 0) return "Not set";
            StringBuilder sb = new StringBuilder();
            for (int c : codes[bit]) {
                // the hat and the analog triggers back up the defaults quietly
                if (codes[bit].length > 1 && (c == TRIGGER_L || c == TRIGGER_R
                        || (isAxisCode(c) && (axisOf(c) == AXIS_HAT_X || axisOf(c) == AXIS_HAT_Y)))) continue;
                if (sb.length() > 0) sb.append(" / ");
                sb.append(namer.name(c));
            }
            return sb.length() == 0 ? namer.name(codes[bit][0]) : sb.toString();
        }
        private static int[] remove(int[] a, int code) {
            int n = 0;
            for (int c : a) if (c != code) n++;
            if (n == a.length) return a;
            int[] r = new int[n]; int i = 0;
            for (int c : a) if (c != code) r[i++] = c;
            return r;
        }
    }
    interface CodeNamer { String name(int code); }
    static final CodeNamer PLAIN_NAMES = code -> { String n = codeName(code); return n != null ? n : "Key " + code; };

    /* ---- one gamepad's inputs ---- */
    static final class Reader {
        private final boolean[] keys = new boolean[MAX_KEY];
        final float[] axes = new float[MAX_AXIS];
        /** Which axes this device uses for the right stick and the analog
         *  triggers (-1: it has none); PadInput sets them from the device. */
        int rxAxis = AXIS_Z, ryAxis = AXIS_RZ, triggerL = AXIS_LTRIGGER, triggerR = AXIS_RTRIGGER;
        void key(int code, boolean down) { if (isKeyCode(code)) keys[code] = down; }
        boolean keyDown(int code) { return isKeyCode(code) && keys[code]; }
        void axis(int axis, float value) { if (axis >= 0 && axis < MAX_AXIS) axes[axis] = value; }
        float axis(int axis) { return axis >= 0 && axis < MAX_AXIS ? axes[axis] : 0f; }
        void clear() { Arrays.fill(keys, false); Arrays.fill(axes, 0f); }
        boolean active(int code) {
            if (isKeyCode(code)) return keys[code];
            if (code == TRIGGER_L) return triggerL >= 0 && axis(triggerL) > AXIS_PRESS;
            if (code == TRIGGER_R) return triggerR >= 0 && axis(triggerR) > AXIS_PRESS;
            if (isAxisCode(code)) {
                float v = axis(axisOf(code));
                return axisNegative(code) ? v < -AXIS_PRESS : v > AXIS_PRESS;
            }
            return false;
        }
        int buttons(Mapping m) {
            int b = 0;
            for (int bit = 0; bit < BUTTON_BITS; bit++)
                for (int c : m.codes[bit]) if (active(c)) { b |= 1 << bit; break; }
            return b;
        }
        int[] leftStick(float dz) { return stick(axis(AXIS_X), axis(AXIS_Y), dz); }
        int[] rightStick(float dz) {
            if (rxAxis < 0 || ryAxis < 0) return new int[]{ 0, 0 };
            return stick(axis(rxAxis), axis(ryAxis), dz);
        }
    }

    /** Every gamepad as one controller: the buttons of all of them, each stick
     *  from whichever pad pushes it furthest. connected is false without any. */
    static State combine(Iterable<Reader> readers, Mapping m, float deadzone) {
        boolean any = false;
        int buttons = 0;
        int[] left = { 0, 0 }, right = { 0, 0 };
        long leftMag = 0, rightMag = 0;
        for (Reader r : readers) {
            any = true;
            buttons |= r.buttons(m);
            int[] l = r.leftStick(deadzone), rr = r.rightStick(deadzone);
            long lm = (long)l[0] * l[0] + (long)l[1] * l[1], rm = (long)rr[0] * rr[0] + (long)rr[1] * rr[1];
            if (lm > leftMag) { leftMag = lm; left = l; }
            if (rm > rightMag) { rightMag = rm; right = rr; }
        }
        if (!any) return State.NONE;
        return new State(true, buttons, left[0], left[1], right[0], right[1]);
    }

    /** 0.7.16: two controllers played as one (the gamepads and the on-screen
     *  one): buttons together, each stick from whichever is pushed further. */
    static State merge(State a, State b) {
        if (a == null || !a.connected) return b == null ? State.NONE : b;
        if (b == null || !b.connected) return a;
        long al = (long)a.lx * a.lx + (long)a.ly * a.ly, bl = (long)b.lx * b.lx + (long)b.ly * b.ly;
        long ar = (long)a.rx * a.rx + (long)a.ry * a.ry, br = (long)b.rx * b.rx + (long)b.ry * b.ry;
        return new State(true, a.buttons | b.buttons,
                bl > al ? b.lx : a.lx, bl > al ? b.ly : a.ly,
                br > ar ? b.rx : a.rx, br > ar ? b.ry : a.ry);
    }

    /* ---- the shortcut that opens Settings from the gamepad ---- */
    static final int SHORTCUT_MINUS_PLUS = 0, SHORTCUT_STICKS = 1, SHORTCUT_OFF = 2;
    static final long SHORTCUT_HOLD_MS = 700;
    /** The Switch buttons held together for the shortcut (0: none). */
    static int shortcutMask(int shortcut) {
        switch (shortcut) {
            case SHORTCUT_MINUS_PLUS: return (1 << MINUS) | (1 << PLUS);
            case SHORTCUT_STICKS: return (1 << LSTICK) | (1 << RSTICK);
            default: return 0;
        }
    }

    private Pad() { }
}
