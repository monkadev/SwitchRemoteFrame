/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.InputDevice;
import java.util.ArrayList;
import java.util.List;

/** 0.7.16: the game's rumble (Rumble.java) on the motors here - the gamepads'
 *  (each motor its own side on Android 12+, as on a Pro Controller) and / or
 *  this device's own. All vibrator calls happen on one thread of its own; a
 *  motor runs until told otherwise, and stops by itself when the console has
 *  said nothing for Rumble.SILENCE_MS (a stalled stream never leaves it
 *  buzzing). */
final class Rumbler {
    private final Context context;
    private final HandlerThread thread = new HandlerThread("sft-rumble");
    private final Handler handler;
    private volatile boolean padOn, deviceOn;
    // the rumble thread's own
    private Rumble.Motors want = Rumble.Motors.OFF;
    private int padLeft, padRight, deviceAmp;      // what the motors were last told (Android amplitudes)
    private long heardMs, padsAtMs = -1;
    private final List<Pad> pads = new ArrayList<>();
    // 0.7.16: Xbox controllers whose kernel cannot rumble them, through root
    private final RootRumble root = new RootRumble();
    private volatile boolean rootOn;
    private String padSignature = "";
    private long rootSentMs;

    /** A gamepad's motors: the left and right one (Android 12+), or one for both. */
    private static final class Pad {
        Object manager;         // android.os.VibratorManager (API 31+)
        int[] ids;
        Vibrator single;
        String name;
    }

    Rumbler(Context context) {
        this.context = context.getApplicationContext();
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    void setPad(boolean on) { padOn = on; if (!on) handler.post(() -> { padLeft = padRight = 0; for (Pad p : pads) cancel(p); root.send(0, 0); }); }
    void setDevice(boolean on) { deviceOn = on; if (!on) handler.post(() -> { deviceAmp = 0; Vibrator v = deviceVibrator(); if (v != null) v.cancel(); }); }

    /** The console's newest value (any thread). */
    void set(Rumble.Motors m) { handler.post(() -> apply(m)); }
    /** Everything off now (any thread). */
    void stop() { set(Rumble.Motors.OFF); }
    void release() { stop(); handler.post(root::release); thread.quitSafely(); }

    /** This device has a motor of its own. */
    boolean deviceCanVibrate() { Vibrator v = deviceVibrator(); return v != null && v.hasVibrator(); }

    /** What the gamepad test found: the gamepads that have a motor (names),
     *  and what the root way found (null while it is off). */
    interface Tested { void tested(List<String> withMotors, int gamepads, RootRumble.Scan root); }

    /** "Vibration through root" on / off; on, the controllers are looked for
     *  as root at once (the root manager asks the first time). */
    void setRoot(boolean on, Tested result) {
        rootOn = on;
        if (!on) { handler.post(root::release); return; }
        handler.post(() -> {
            RootRumble.Scan s = root.rescan();
            if (result != null) result.tested(new ArrayList<>(), 0, s);
        });
    }
    static boolean rootPossible() { return RootRumble.suPresent(); }

    /** A short buzz on the gamepads (the Gamepad page's test) - whether or not
     *  this device itself can vibrate; says which gamepads have a motor. */
    void testPads(Tested result) {
        handler.post(() -> {
            padsAtMs = -1;   // look again: a gamepad may have just been connected
            List<String> names = new ArrayList<>();
            for (Pad p : gamepads()) names.add(p.name);
            int all = 0;
            for (int id : InputDevice.getDeviceIds()) if (PadInput.isGamepad(InputDevice.getDevice(id))) all++;
            RootRumble.Scan rs = rootOn ? root.rescan() : null;   // a controller may have a new node
            if (result != null) result.tested(names, all, rs);
            padLeft = padRight = -100;   // tell the motors even if they think they run already
            apply(new Rumble.Motors(200, 200, 0), true, false);
            handler.postDelayed(() -> apply(Rumble.Motors.OFF, true, false), 400);
        });
    }
    /** A short buzz on this device (switching Device vibration on). */
    void testDevice() {
        handler.post(() -> {
            apply(new Rumble.Motors(160, 160, 0), false, true);
            handler.postDelayed(() -> apply(Rumble.Motors.OFF, false, true), 150);
        });
    }

    /* ---- the rumble thread ---- */

    private final Runnable silence = () -> {
        if (!want.off() && SystemClock.uptimeMillis() - heardMs >= Rumble.SILENCE_MS - 5) apply(Rumble.Motors.OFF);
    };

    private void apply(Rumble.Motors m) {
        want = m;
        heardMs = SystemClock.uptimeMillis();
        handler.removeCallbacks(silence);
        if (!m.off()) handler.postDelayed(silence, Rumble.SILENCE_MS);
        apply(m, padOn, deviceOn);
    }

    private void apply(Rumble.Motors m, boolean toPads, boolean toDevice) {
        if (toPads) {
            int l = Rumble.amplitude(m.left), r = Rumble.amplitude(m.right);
            boolean changed = Rumble.differs(l, padLeft) || Rumble.differs(r, padRight);
            if (changed) {
                padLeft = l; padRight = r;
                for (Pad p : gamepads()) vibrate(p, l, r);
            }
            long now = SystemClock.uptimeMillis();
            if (rootOn && (changed || ((l != 0 || r != 0) && now - rootSentMs >= 1000))) {
                root.send(RootRumble.percent(l), RootRumble.percent(r));
                rootSentMs = now;
            }
        }
        if (toDevice) {
            int a = Rumble.amplitude(m.strongest());
            if (Rumble.differs(a, deviceAmp)) {
                deviceAmp = a;
                Vibrator v = deviceVibrator();
                if (v != null && v.hasVibrator()) vibrate(v, a);
            }
        }
    }

    /** The gamepads with motors, looked up again every 2 s while in use. */
    private List<Pad> gamepads() {
        long now = SystemClock.uptimeMillis();
        if (padsAtMs >= 0 && now - padsAtMs < 2000) return pads;
        padsAtMs = now;
        pads.clear();
        // a gamepad came or went: the root way looks for the controllers again
        StringBuilder sig = new StringBuilder();
        for (int id : InputDevice.getDeviceIds()) if (PadInput.isGamepad(InputDevice.getDevice(id))) sig.append(id).append(',');
        if (!sig.toString().equals(padSignature)) {
            padSignature = sig.toString();
            if (rootOn) new Thread(root::rescan, "sft-root-rumble").start();
        }
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (!PadInput.isGamepad(d)) continue;
            Pad p = new Pad();
            p.name = d.getName();
            if (Build.VERSION.SDK_INT >= 31) {
                android.os.VibratorManager vm = d.getVibratorManager();
                int[] ids = vm.getVibratorIds();
                if (ids.length > 0) { p.manager = vm; p.ids = ids; }
                else {
                    // some gamepads show their motor only the old way
                    @SuppressWarnings("deprecation") Vibrator v = d.getVibrator();
                    if (v == null || !v.hasVibrator()) continue;
                    p.single = v;
                }
            } else {
                Vibrator v = d.getVibrator();
                if (v == null || !v.hasVibrator()) continue;
                p.single = v;
            }
            pads.add(p);
        }
        return pads;
    }

    private void vibrate(Pad p, int left, int right) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && p.manager != null) {
                android.os.VibratorManager vm = (android.os.VibratorManager)p.manager;
                if (left == 0 && right == 0) { vm.cancel(); return; }
                if (p.ids.length >= 2) {
                    // the first motor is the left (the strong, low one on most gamepads)
                    vm.vibrate(android.os.CombinedVibration.startParallel()
                            .addVibrator(p.ids[0], forever(vm.getVibrator(p.ids[0]), left))
                            .addVibrator(p.ids[1], forever(vm.getVibrator(p.ids[1]), right))
                            .combine());
                } else {
                    Vibrator v = vm.getVibrator(p.ids[0]);
                    vm.vibrate(android.os.CombinedVibration.createParallel(forever(v, Math.max(left, right))));
                }
            } else if (p.single != null) {
                vibrate(p.single, Math.max(left, right));
            }
        } catch (RuntimeException ignored) {
            padsAtMs = -1;   // the gamepad went away: look again next time
        }
    }

    private void cancel(Pad p) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && p.manager != null) ((android.os.VibratorManager)p.manager).cancel();
            else if (p.single != null) p.single.cancel();
        } catch (RuntimeException ignored) { }
    }

    /** One motor at this amplitude until told otherwise (0 = off). */
    @SuppressWarnings("deprecation")
    private static void vibrate(Vibrator v, int amp) {
        try {
            if (amp == 0) { v.cancel(); return; }
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(forever(v, amp));
            else v.vibrate(new long[]{ 0, 60_000 }, 0);
        } catch (RuntimeException ignored) { }
    }

    /** A repeating waveform: the motor stays on at this amplitude (or simply on,
     *  without amplitude control) until cancelled. */
    @android.annotation.TargetApi(26)
    private static VibrationEffect forever(Vibrator v, int amp) {
        boolean control = v != null && v.hasAmplitudeControl();
        if (!control) {
            boolean on = amp > 0 && Rumble.onOff(Math.round(amp * amp / 255f));   // back to the Switch's scale
            return VibrationEffect.createWaveform(new long[]{ 1000 }, new int[]{ on ? 255 : 0 }, 0);
        }
        return VibrationEffect.createWaveform(new long[]{ 1000 }, new int[]{ Math.max(0, Math.min(255, amp)) }, 0);
    }

    @SuppressWarnings("deprecation")
    private Vibrator deviceVibrator() {
        if (Build.VERSION.SDK_INT >= 31) {
            android.os.VibratorManager vm = (android.os.VibratorManager)context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        }
        return (Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
