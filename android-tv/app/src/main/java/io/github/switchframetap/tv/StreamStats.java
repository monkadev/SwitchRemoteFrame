/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

final class StreamStats {
    volatile long bytes, received, rendered, skipped, decoderErrors, videoResets, audioResets;
    volatile long decoded, inputSubmitted, unmatchedPts, videoDropped, audioPackets, audioBytes, audioWrittenFrames, audioPlayedFrames;
    volatile long lastRenderedNs, lastVideoNs;
    volatile long touchSent, touchFailed;   // remote touch (0.7.16) messages
    volatile long padSent, padFailed;       // remote gamepad (0.7.16) messages
    volatile long rumblePackets;            // the game's rumble (0.7.16) from the console
    volatile double decoderMs, consoleMs, audioQueuedMs;
    volatile double audioBufferMs;
    volatile double worstGapMs;             // 0.7.16: longest wait between two shown frames (the overlay resets it each second)
    volatile boolean audioMuted;
    volatile int width, height;
    volatile String decoder = "—", audio = "Waiting for audio", transport = "", pacing = "";
}
