/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import java.util.ArrayDeque;

/** Never discard a P frame then decode its dependants. Recover at an IDR. */
final class VideoQueue {
    private final ArrayDeque<SftProtocol.Packet> queue = new ArrayDeque<>();
    private boolean waitingKey = true, closed;
    private int bytes;
    private long lastSequence = -1;
    private int width, height;
    private long resets, dropped;
    // v0.7.15 (#27, #32): a reset asks the console for a keyframe at once
    private boolean wantKey = true;   // and once at connect, as the PC viewer does
    static final int MAX_FRAMES = 12;
    static final long LATE_NS = 250_000_000L;
    synchronized void offer(SftProtocol.Packet p) {
        if (closed) return;
        boolean changed = width != p.width || height != p.height;
        // Gaps, session restarts and changed SPS/resolution all invalidate references.
        boolean gap = lastSequence >= 0 && p.sequence != ((lastSequence + 1) & 0xffffffffL);
        lastSequence = p.sequence; width = p.width; height = p.height;
        // 0.7.16: a burst after a Wi-Fi hiccup (bigger at 1080p) used to pass 6 frames or
        // 100 ms and throw everything away until a keyframe: a freeze, then a jump. A
        // hardware decoder clears a dozen frames in a few ms and the display side drops
        // all but two of them (FramePacer), so only a real stall resets now.
        boolean late = !queue.isEmpty() && p.receivedNs - queue.peekFirst().receivedNs > LATE_NS;
        if (gap || changed || late || queue.size() >= MAX_FRAMES || bytes + p.data.length > 16 * 1024 * 1024) {
            dropped += queue.size(); queue.clear(); bytes = 0; waitingKey = true; resets++; wantKey = true;
        }
        if (waitingKey) {
            if (!p.key()) { dropped++; return; }
            p.discontinuity = true; waitingKey = false;
        }
        queue.addLast(p); bytes += p.data.length; notifyAll();
    }
    synchronized SftProtocol.Packet poll(long timeoutMs) throws InterruptedException {
        if (queue.isEmpty() && !closed) wait(timeoutMs);
        SftProtocol.Packet p = queue.pollFirst();
        if (p != null) bytes -= p.data.length;
        return p;
    }
    /** The next frame if one is waiting, without waiting (poll(0) would wait forever). */
    synchronized SftProtocol.Packet pollNow() {
        SftProtocol.Packet p = queue.pollFirst();
        if (p != null) bytes -= p.data.length;
        return p;
    }
    synchronized void recover() {
        dropped += queue.size(); queue.clear(); bytes = 0; waitingKey = true; resets++; wantKey = true;
    }
    /** True once after a reset: the receiver then asks the console for a keyframe. */
    synchronized boolean takeKeyRequest() { boolean w = wantKey; wantKey = false; return w; }
    synchronized void close() { closed = true; queue.clear(); bytes = 0; notifyAll(); }
    synchronized long resets() { return resets; }
    synchronized long dropped() { return dropped; }
}
