/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import java.util.ArrayDeque;

/** Remote touch (Switch Frame Tap 0.7.16+): the fingers on the picture, sent to
 *  the console's touch screen. Plain Java, so ProtocolTest checks it on the PC.
 *
 *  The wire format is the console's tier4/applet-mitm/source/sft_touch_proto.hpp:
 *  48 bytes, little-endian - "SFTT", u8 version 1, u8 count (0..5), u16 sequence,
 *  then 5 x {u16 x, u16 y, u8 id, 3 reserved}; x 0..1279, y 0..719 on the touch
 *  screen whatever the stream's resolution, id 0..15 per finger while it is down.
 *  Each message is the whole touch screen; count 0 lifts every finger. Consoles
 *  before 0.7.16 skip it. */
final class Touch {
    static final int MSG_SIZE = 48, VERSION = 1, MAX_FINGERS = 5, MAX_ID = 15;
    static final int WIDTH = 1280, HEIGHT = 720;
    /** While a finger rests, the state is repeated this often: the console lifts
     *  fingers it has not heard about for 2 s (a viewer that froze). */
    static final long KEEPALIVE_NS = 500_000_000L;

    private Touch() { }

    static final class Frame {
        final int count;
        final int[] x = new int[MAX_FINGERS], y = new int[MAX_FINGERS], id = new int[MAX_FINGERS];
        Frame(int count) { this.count = Math.max(0, Math.min(MAX_FINGERS, count)); }
        static final Frame NONE = new Frame(0);
        /** The fingers down, one bit per id: a frame with the same mask only moves them. */
        int mask() {
            int m = 0;
            for (int i = 0; i < count; i++) m |= 1 << id[i];
            return m;
        }
    }

    static byte[] encode(Frame f, int seq) {
        byte[] m = new byte[MSG_SIZE];
        m[0] = 'S'; m[1] = 'F'; m[2] = 'T'; m[3] = 'T';
        m[4] = (byte)VERSION; m[5] = (byte)f.count;
        m[6] = (byte)seq; m[7] = (byte)(seq >>> 8);
        for (int i = 0; i < f.count; i++) {
            int o = 8 + i * 8;
            int x = clamp(f.x[i], WIDTH - 1), y = clamp(f.y[i], HEIGHT - 1);
            m[o] = (byte)x; m[o + 1] = (byte)(x >>> 8);
            m[o + 2] = (byte)y; m[o + 3] = (byte)(y >>> 8);
            m[o + 4] = (byte)(f.id[i] & MAX_ID);
        }
        return m;
    }
    private static int clamp(int v, int max) { return v < 0 ? 0 : Math.min(v, max); }

    /** A point on the picture (view pixels) -> the touch screen's 1280x720. */
    static int toScreen(float v, int viewSize, int screenSize) {
        if (viewSize <= 0) return 0;
        int s = (int)(v * screenSize / viewSize);
        return clamp(s, screenSize - 1);
    }

    /** Android pointer ids -> finger ids 0..15, the lowest free one for each new
     *  pointer, for as long as it is down. */
    static final class Fingers {
        private final int[] pointerOf = new int[MAX_ID + 1];
        Fingers() { clear(); }
        void clear() { java.util.Arrays.fill(pointerOf, -1); }
        /** The finger for this pointer, given one if it is new; -1 when all are taken. */
        int finger(int pointerId) {
            int free = -1;
            for (int i = 0; i <= MAX_ID; i++) {
                if (pointerOf[i] == pointerId) return i;
                if (free < 0 && pointerOf[i] < 0) free = i;
            }
            if (free >= 0) pointerOf[free] = pointerId;
            return free;
        }
        void release(int pointerId) {
            for (int i = 0; i <= MAX_ID; i++) if (pointerOf[i] == pointerId) pointerOf[i] = -1;
        }
    }

    /** What is waiting to be sent. A move of the same fingers replaces the newest
     *  waiting state (only the latest position matters); a finger going down or up
     *  is appended, so a quick tap is never merged away. Bounded: when full, the
     *  newest entry gives way. */
    static final class Queue {
        static final int MAX = 32;
        private final ArrayDeque<Frame> q = new ArrayDeque<>();
        synchronized void offer(Frame f) {
            Frame last = q.peekLast();
            if (last != null && last.mask() == f.mask()) { q.pollLast(); }
            else if (q.size() >= MAX) { q.pollLast(); }
            q.addLast(f);
            notifyAll();
        }
        synchronized void clear() { q.clear(); }
        synchronized int size() { return q.size(); }
        /** The next state, or null after waitNs with nothing new. */
        synchronized Frame take(long waitNs) throws InterruptedException {
            long end = System.nanoTime() + waitNs;
            while (q.isEmpty()) {
                long left = end - System.nanoTime();
                if (left <= 0) return null;
                wait(Math.max(1, left / 1_000_000L));
            }
            return q.pollFirst();
        }
    }
}
