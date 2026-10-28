/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The gamepads connected to this device, read for the remote gamepad: their
 *  keys and axes go into one Pad.Reader each, Pad.combine makes them the
 *  Switch's controller. Also listens for one input when a button is remapped.
 *  Main thread only. */
final class PadInput implements InputManager.InputDeviceListener {
    interface Listener {
        /** Something changed: a key, an axis, a gamepad came or went. */
        void padChanged();
    }
    interface Capture {
        /** The input pressed for a remap (a Pad input code). */
        void captured(int code);
    }

    static final int RIGHT_AUTO = 0, RIGHT_Z_RZ = 1, RIGHT_RX_RY = 2;

    private final InputManager inputManager;
    private final Handler handler;
    private final Listener listener;
    private final Map<Integer, Pad.Reader> readers = new LinkedHashMap<>();
    private int rightStickMode = RIGHT_AUTO;
    private Capture capture;
    private float[] captureRest;
    private int captureDevice = -1;
    private String lastInput = "";

    PadInput(Context context, Handler handler, Listener listener) {
        this.inputManager = (InputManager)context.getSystemService(Context.INPUT_SERVICE);
        this.handler = handler;
        this.listener = listener;
    }
    void start() { if (inputManager != null) inputManager.registerInputDeviceListener(this, handler); refreshDevices(); }
    void stop() { if (inputManager != null) inputManager.unregisterInputDeviceListener(this); }

    /* ---- which devices and events are gamepads ---- */

    static boolean isGamepad(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }
    /** The names of the gamepads connected now. */
    List<String> names() {
        List<String> out = new ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (isGamepad(d)) out.add(d.getName());
        }
        return out;
    }
    boolean anyConnected() {
        for (int id : InputDevice.getDeviceIds()) if (isGamepad(InputDevice.getDevice(id))) return true;
        return false;
    }

    /* ---- settings ---- */

    void setRightStickMode(int mode) {
        rightStickMode = mode;
        for (Map.Entry<Integer, Pad.Reader> e : readers.entrySet()) configure(e.getValue(), InputDevice.getDevice(e.getKey()));
    }
    /** The axes this device has for the right stick and the triggers: most report
     *  the right stick on Z/RZ with the triggers on LTRIGGER/RTRIGGER (or
     *  BRAKE/GAS); some (Xbox 360 pads on some kernels) on RX/RY with the
     *  triggers on Z/RZ. */
    private void configure(Pad.Reader r, InputDevice d) {
        if (d == null) return;
        boolean z = has(d, Pad.AXIS_Z), rz = has(d, Pad.AXIS_RZ), rx = has(d, Pad.AXIS_RX), ry = has(d, Pad.AXIS_RY);
        boolean lt = has(d, Pad.AXIS_LTRIGGER), rt = has(d, Pad.AXIS_RTRIGGER), brake = has(d, Pad.AXIS_BRAKE), gas = has(d, Pad.AXIS_GAS);
        boolean zrz;
        if (rightStickMode == RIGHT_Z_RZ) zrz = true;
        else if (rightStickMode == RIGHT_RX_RY) zrz = false;
        else zrz = z && rz && (lt || brake || rt || gas || !(rx && ry));
        if (zrz) { r.rxAxis = z ? Pad.AXIS_Z : -1; r.ryAxis = rz ? Pad.AXIS_RZ : -1; }
        else { r.rxAxis = rx ? Pad.AXIS_RX : -1; r.ryAxis = ry ? Pad.AXIS_RY : -1; }
        r.triggerL = lt ? Pad.AXIS_LTRIGGER : brake ? Pad.AXIS_BRAKE : (!zrz && z) ? Pad.AXIS_Z : -1;
        r.triggerR = rt ? Pad.AXIS_RTRIGGER : gas ? Pad.AXIS_GAS : (!zrz && rz) ? Pad.AXIS_RZ : -1;
    }
    private static boolean has(InputDevice d, int axis) { return d.getMotionRange(axis) != null; }

    private Pad.Reader reader(InputDevice d) {
        Pad.Reader r = readers.get(d.getId());
        if (r == null) { r = new Pad.Reader(); configure(r, d); readers.put(d.getId(), r); }
        return r;
    }

    /* ---- events ---- */

    /** A key from a gamepad. True when it is the gamepad's (a button or its
     *  D-pad) - Back, volume and the like stay with Android unless mapped. */
    boolean key(KeyEvent e, Pad.Mapping mapping) {
        InputDevice d = e.getDevice();
        if (!isGamepad(d)) return false;
        // Android's own stand-ins - B as Back, A as OK, the hat and stick as D-pad
        // keys in menus - are not the gamepad's: their ups can go missing once
        // the game has the gamepad again, and a button would stay held
        if ((e.getFlags() & KeyEvent.FLAG_FALLBACK) != 0) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE) return false;
        if (capture != null) {
            if (down && e.getRepeatCount() == 0 && Pad.isKeyCode(code)) finishCapture(code);
            return true;
        }
        Pad.Reader r = reader(d);
        boolean before = r.keyDown(code);
        r.key(code, down);
        if (down && !before) lastInput = name(code) + "  ·  " + d.getName();
        if (before != down) listener.padChanged();
        return isPadKey(code) || mapped(mapping, code);
    }
    private static boolean mapped(Pad.Mapping m, int code) {
        for (int[] cs : m.codes) for (int c : cs) if (c == code) return true;
        return false;
    }
    /** A gamepad's own keys: its buttons and its D-pad. */
    static boolean isPadKey(int code) {
        return KeyEvent.isGamepadButton(code) || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    /** Sticks, triggers and the hat. True when it came from a gamepad. */
    boolean motion(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK && (e.getSource() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) return false;
        InputDevice d = e.getDevice();
        if (!isGamepad(d) || e.getAction() != MotionEvent.ACTION_MOVE) return false;
        Pad.Reader r = reader(d);
        if (capture != null) {
            if (captureDevice != d.getId()) { captureDevice = d.getId(); captureRest = r.axes.clone(); }
            for (InputDevice.MotionRange range : d.getMotionRanges()) {
                int a = range.getAxis();
                if (a < 0 || a >= Pad.MAX_AXIS || a == Pad.AXIS_X || a == Pad.AXIS_Y || a == r.rxAxis || a == r.ryAxis) continue;   // the sticks stay sticks
                float v = e.getAxisValue(a), rest = captureRest[a];
                if (Math.abs(rest) < 0.3f && Math.abs(v) > 0.6f) {
                    finishCapture(a == r.triggerL ? Pad.TRIGGER_L : a == r.triggerR ? Pad.TRIGGER_R : Pad.axisCode(a, v < 0));
                    break;
                }
            }
            for (InputDevice.MotionRange range : d.getMotionRanges()) r.axis(range.getAxis(), e.getAxisValue(range.getAxis()));
            return true;
        }
        boolean changed = false;
        for (InputDevice.MotionRange range : d.getMotionRanges()) {
            int a = range.getAxis();
            if (a < 0 || a >= Pad.MAX_AXIS) continue;
            float v = e.getAxisValue(a);
            if (v != r.axis(a)) {
                boolean wasPressed = Math.abs(r.axis(a)) > Pad.AXIS_PRESS;
                r.axis(a, v);
                changed = true;
                if (!wasPressed && Math.abs(v) > Pad.AXIS_PRESS && a != Pad.AXIS_X && a != Pad.AXIS_Y && a != r.rxAxis && a != r.ryAxis)
                    lastInput = name(a == r.triggerL ? Pad.TRIGGER_L : a == r.triggerR ? Pad.TRIGGER_R : Pad.axisCode(a, v < 0)) + "  ·  " + d.getName();
            }
        }
        if (changed) listener.padChanged();
        return true;
    }

    /** The Switch's controller now. */
    Pad.State state(Pad.Mapping mapping, float deadzone) { return Pad.combine(readers.values(), mapping, deadzone); }
    /** Forget what is held (the app lost focus: the key ups will not come). */
    void clear() { for (Pad.Reader r : readers.values()) r.clear(); listener.padChanged(); }
    String lastInput() { return lastInput; }

    /* ---- remapping ---- */

    void startCapture(Capture c) { capture = c; captureDevice = -1; captureRest = null; }
    void cancelCapture() { capture = null; }
    boolean capturing() { return capture != null; }
    private void finishCapture(int code) {
        Capture c = capture; capture = null;
        if (c != null) handler.post(() -> c.captured(code));
    }

    /** A name for an input code, Android's for keys Pad does not know. */
    String name(int code) {
        String n = Pad.codeName(code);
        if (n != null) return n;
        if (Pad.isKeyCode(code)) {
            String k = KeyEvent.keyCodeToString(code);
            return k.startsWith("KEYCODE_") ? k.substring(8).replace('_', ' ').toLowerCase(java.util.Locale.ROOT) : k;
        }
        if (Pad.isAxisCode(code)) {
            String a = MotionEvent.axisToString(Pad.axisOf(code));
            return (a.startsWith("AXIS_") ? a.substring(5) : a) + (Pad.axisNegative(code) ? " −" : " +");
        }
        return "Input " + code;
    }

    /* ---- devices coming and going ---- */

    private void refreshDevices() {
        for (java.util.Iterator<Integer> it = readers.keySet().iterator(); it.hasNext(); )   // (removeIf is API 24)
            if (!isGamepad(InputDevice.getDevice(it.next()))) it.remove();
    }
    @Override public void onInputDeviceAdded(int id) { listener.padChanged(); }
    @Override public void onInputDeviceRemoved(int id) { readers.remove(id); listener.padChanged(); }
    @Override public void onInputDeviceChanged(int id) {
        Pad.Reader r = readers.get(id);
        InputDevice d = InputDevice.getDevice(id);
        if (r != null && d != null) configure(r, d);
        if (!isGamepad(d)) readers.remove(id);
        listener.padChanged();
    }
}
