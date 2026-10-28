/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

/**
 * 0.7.16 "Smooth video": which decoded frame to show at each display refresh.
 *
 * The PC viewer learnt this in v0.2 (raw-view.c, "the live pipeline"): frames
 * reach the viewer in bursts (Wi-Fi, and more so at 1080p, where each frame is
 * bigger), and "show only the newest, right away" throws most of a burst away -
 * the picture jumps several times a second although every frame arrived.
 * Here, as there, every frame is shown IN ORDER, one per refresh (vsync).
 * Unlike there, a burst may briefly queue up to {@link #MAX_WAITING} frames
 * (the PC's fixed cap of two still dropped a frame of every three-frame burst);
 * the delay that costs is taken back only when it STAYS: if two or more frames
 * have been waiting at every refresh for {@link #TRIM_NS}, the oldest goes.
 * So a burst is shown whole, and a lasting backlog is trimmed within 1/4 s.
 *
 * On a screen faster than the stream (a 120 Hz tablet, a 60 Hz screen with a
 * 30 fps game) one frame per refresh would show a burst at double speed and
 * then wait; so a frame on its own also waits until about one stream frame
 * interval has passed since the last one shown. That keeps the cadence even:
 * every other refresh for 60 fps on 120 Hz.
 *
 * Pure logic (no Android classes) so ProtocolTest can check it.
 */
final class FramePacer {
    /** Frames that may wait for the screen; beyond this the oldest is dropped. */
    static final int MAX_WAITING = 4;
    /** Two or more frames waiting at every refresh this long: drop one. */
    static final long TRIM_NS = 250_000_000L;
    private long trimStartNs;
    private int trimMin = Integer.MAX_VALUE;
    private long vsyncNs, lastVsyncNs, lastShownNs;
    private long intervalNs = 16_666_667L;          // the stream's frame interval, measured
    private long windowStartNs, windowFrames;

    FramePacer(float refreshHz) {
        vsyncNs = refreshHz >= 20 && refreshHz <= 500 ? (long)(1e9 / refreshHz) : 16_666_667L;
    }

    /** A decoded frame is ready (System.nanoTime()). The rate is counted over
     *  half a second, so bursts and gaps average out. */
    void arrived(long nowNs) {
        if (windowStartNs == 0) { windowStartNs = nowNs; windowFrames = 0; return; }
        windowFrames++;
        long span = nowNs - windowStartNs;
        if (span >= 500_000_000L) {
            if (windowFrames >= 5) {
                long measured = clamp(span / windowFrames, 4_000_000L, 100_000_000L);
                intervalNs = intervalNs + (measured - intervalNs) / 2;
            }
            windowStartNs = nowNs; windowFrames = 0;
        }
    }

    /** A display refresh (Choreographer's frame time). Missed callbacks (two
     *  periods or more apart) do not count towards the period. */
    void vsync(long frameTimeNs) {
        long d = frameTimeNs - lastVsyncNs;
        if (lastVsyncNs != 0 && d > 2_000_000L && d < vsyncNs * 3 / 2) vsyncNs += (d - vsyncNs) / 8;
        lastVsyncNs = frameTimeNs;
    }

    /** How many of the oldest waiting frames to drop at this refresh. */
    int drop(int waiting, long frameTimeNs) {
        int n = Math.max(0, waiting - MAX_WAITING);
        int left = waiting - n;
        if (trimStartNs == 0) trimStartNs = frameTimeNs;
        if (left < trimMin) trimMin = left;
        if (frameTimeNs - trimStartNs >= TRIM_NS) {
            if (trimMin >= 2) n++;                // a standing backlog: one frame of delay less
            trimStartNs = frameTimeNs; trimMin = Integer.MAX_VALUE;
        }
        return n;
    }

    /** Show the oldest waiting frame at this refresh? */
    boolean present(int waiting, long frameTimeNs) {
        if (waiting <= 0) return false;
        if (waiting > 2 || lastShownNs == 0) return true;      // well behind: one per refresh
        long minGap = intervalNs - vsyncNs / 2;                // else about a stream frame since the last one
        return frameTimeNs - lastShownNs >= minGap;
    }

    void shown(long frameTimeNs) { lastShownNs = frameTimeNs; }

    long intervalNs() { return intervalNs; }
    long vsyncNs() { return vsyncNs; }

    private static long clamp(long v, long lo, long hi) { return v < lo ? lo : v > hi ? hi : v; }
}
