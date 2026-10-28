/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.Surface;
import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

final class VideoDecoder implements AutoCloseable {
    interface Status { void accept(String value); }
    private static final String MIME = "video/avc";
    private final Surface surface;
    private final VideoQueue queue;
    private final StreamStats stats;
    private final Status status;
    private final Thread thread;
    private volatile boolean stopped;
    private MediaCodec codec;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final Map<Long, Stamp> stamps = new LinkedHashMap<>();
    private final Set<String> failedCodecs = new HashSet<>();
    // decoders that kept up only without the low-latency/priority keys tried first
    private final Set<String> slowWhenOptimized = new HashSet<>();
    // 0.7.19-touch-4: decoders that failed with the vendor low-latency options (level 2)
    private final Set<String> vendorFailed = new HashSet<>();
    private String codecName = "";
    private boolean codecOptimized;
    /** 2: the vendor low-latency options (Moonlight's), 1: the standard keys, 0: none. */
    private int codecLevel;
    // 0.7.19-touch-4: frames given to the decoder and not out yet - while there are,
    // the thread waits on the decoder itself (it wakes the moment one is done)
    // instead of sleeping 2-4 ms on the queue: ~2 ms less delay per frame
    private int inFlight;
    private long lastInputNs;
    static final long OUTPUT_WAIT_US = 1000;
    // throughput watch: a decoder that gives a frame for every few it is fed
    // (#27: c2.amlogic.avc.decoder showed 1 fps of a 60 fps stream) is replaced
    private long watchStartNs, watchInputs, watchDecoded;
    private int alternatives;   // plainer settings or other decoders left to try; 0: keep this one
    private int width, height;
    private long lastPts, ptsOriginNs, firstInputNs, lastOutputNs;
    private int inputsSinceOutput;
    // 0.7.16 "Smooth video" (FramePacer): decoded frames wait here, in order, and
    // a vsync-driven thread shows one per display refresh - instead of the newest
    // at once, which threw bursts away (the PC viewer's v0.2 fix). The lock also
    // covers flush/stop, so a buffer index is never used after its codec reset.
    private final boolean smooth;
    private final FramePacer pacer;
    private final Object outLock = new Object();
    private final ArrayDeque<Out> ready = new ArrayDeque<>();
    /** Held by the decoder side at most; the presenter keeps it to MAX_WAITING. */
    private static final int MAX_READY = FramePacer.MAX_WAITING + 2;
    private HandlerThread presenter;
    private long lastShownNs;
    private static final class Out {
        final MediaCodec codec; final int index; final Stamp stamp;
        Out(MediaCodec codec, int index, Stamp stamp) { this.codec = codec; this.index = index; this.stamp = stamp; }
    }
    /** Decoding far fewer frames than arrive: try it plainer, or another decoder. */
    private static final class SlowDecoderException extends IOException {
        SlowDecoderException(String message) { super(message); }
    }
    private static final class Stamp {
        final long receivedNs, ageUs;
        Stamp(SftProtocol.Packet p) { receivedNs = p.receivedNs; ageUs = p.consoleAgeUs; }
    }
    VideoDecoder(Surface surface, VideoQueue queue, StreamStats stats, Status status, boolean smooth, float refreshHz) {
        this.surface = surface; this.queue = queue; this.stats = stats; this.status = status;
        this.smooth = smooth; this.pacer = new FramePacer(refreshHz);
        stats.pacing = smooth ? "smooth" : "lowest latency";
        thread = new Thread(this::run, "sft-video");
    }
    void start() {
        thread.start();
        if (!smooth) return;
        presenter = new HandlerThread("sft-present", android.os.Process.THREAD_PRIORITY_DISPLAY);
        presenter.start();
        new Handler(presenter.getLooper()).post(() -> Choreographer.getInstance().postFrameCallback(frame));
    }
    void requestStop() {
        stopped = true; queue.close(); thread.interrupt();
        HandlerThread p = presenter; if (p != null) p.quit();
    }
    @Override public void close() {
        requestStop();
        try { thread.join(1000); } catch (InterruptedException ignored) { }
    }
    /** Every display refresh, on the presenter thread. */
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (stopped) return;
            present(frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };
    private void present(long frameTimeNs) {
        synchronized (outLock) {
            pacer.vsync(frameTimeNs);
            for (int n = pacer.drop(ready.size(), frameTimeNs); n > 0; n--) { discard(ready.pollFirst()); stats.skipped++; }
            if (!pacer.present(ready.size(), frameTimeNs)) return;
            Out o = ready.pollFirst();
            try { o.codec.releaseOutputBuffer(o.index, System.nanoTime()); }   // shown at the next refresh
            catch (RuntimeException e) { return; }                               // the codec failed; the decoder thread recovers
            pacer.shown(frameTimeNs);
            shown(o.stamp);
        }
    }
    private static void discard(Out o) {
        try { o.codec.releaseOutputBuffer(o.index, false); } catch (RuntimeException ignored) { }
    }
    /** Statistics for a frame just sent to the screen. */
    private void shown(Stamp stamp) {
        long now = System.nanoTime();
        stats.decoderMs = stamp == null ? 0 : (now - stamp.receivedNs) / 1_000_000.0;
        stats.consoleMs = stamp == null ? 0 : stamp.ageUs / 1000.0;
        if (lastShownNs != 0) {
            double gap = (now - lastShownNs) / 1_000_000.0;
            if (gap < 1000 && gap > stats.worstGapMs) stats.worstGapMs = gap;
        }
        lastShownNs = now; stats.lastRenderedNs = now; stats.rendered++;
    }
    private void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        SftProtocol.Packet pending = null;
        SftProtocol.Packet configuredPacket = null;
        long inputWaitNs = 0;
        try {
            while (!stopped) {
                try {
                    if (inFlight > 0 && System.nanoTime() - lastInputNs > 250_000_000L) inFlight = 0;   // a decoder that keeps some frames
                    boolean decoding = codec != null && inFlight > 0;
                    // a frame is being decoded and none is waiting: wait for its output (up to
                    // 1 ms at a time, so a new frame waits at most that), else for a new frame
                    if (codec != null) drain(decoding && pending == null ? OUTPUT_WAIT_US : 0);
                    if (pending == null) pending = decoding ? queue.pollNow() : queue.poll(smooth ? 2 : 4);   // 0.7.16: collect decoded frames promptly for the next refresh
                    if (pending == null) continue;
                    boolean resized = pending.width != width || pending.height != height;
                    if (pending != configuredPacket && (pending.discontinuity || codec == null || resized)) {
                        if (!pending.key()) { pending = null; continue; }
                        if (codec != null && !resized) {
                            // v0.7.15 (#27, #32): a gap or a queue reset - the keyframe restarts
                            // the references, so the decoder is flushed and kept. Recreating it
                            // took a TV long enough to overrun the queue again: one frame shown
                            // per keyframe, ~1 fps at 60 fps (30 fps kept up).
                            synchronized (outLock) { ready.clear(); codec.flush(); }   // flush returns every buffer
                            stamps.clear(); inFlight = 0;
                            inputsSinceOutput = 0; lastOutputNs = System.nanoTime();
                        } else {
                            releaseCodec();
                            if (resized) failedCodecs.clear();
                            width = pending.width; height = pending.height;
                            configure(pending);
                        }
                        configuredPacket = pending;
                        inputWaitNs = System.nanoTime();
                    }
                    int index = codec.dequeueInputBuffer(smooth ? 1000 : 2000);
                    if (index < 0) {
                        if (System.nanoTime() - inputWaitNs > 500_000_000L)
                            throw new IOException("The decoder is not responding");
                        continue;
                    }
                    ByteBuffer buffer = codec.getInputBuffer(index);
                    if (buffer == null || buffer.capacity() < pending.data.length)
                        throw new IOException("The decoder cannot accept this frame size; lower the quality on the Switch");
                    ((java.nio.Buffer)buffer).clear(); buffer.put(pending.data);
                    if (ptsOriginNs == 0) ptsOriginNs = pending.receivedNs;
                    long pts = Math.max(lastPts + 1, (pending.receivedNs - ptsOriginNs) / 1000);
                    lastPts = pts; stamps.put(pts, new Stamp(pending));
                    while (stamps.size() > 128) stamps.remove(stamps.keySet().iterator().next());
                    codec.queueInputBuffer(index, 0, pending.data.length, pts, 0);
                    stats.inputSubmitted++; inputsSinceOutput++; inFlight++; lastInputNs = System.nanoTime();
                    if (firstInputNs == 0) firstInputNs = System.nanoTime();
                    checkThroughput();
                    long progress = lastOutputNs == 0 ? firstInputNs : lastOutputNs;
                    if (inputsSinceOutput >= 8 && System.nanoTime() - progress > 1_500_000_000L)
                        throw new IOException("The decoder accepts input but produces no video output");
                    inputWaitNs = System.nanoTime(); pending = null; configuredPacket = null;
                } catch (IOException | RuntimeException e) {
                    stats.decoderErrors++;
                    if (codec != null) {
                        // 0.7.19-touch-4: the vendor options first go - the decoder is tried again with the standard keys
                        if (codecLevel == 2) vendorFailed.add(codecName);
                        else if (e instanceof SlowDecoderException && codecOptimized) slowWhenOptimized.add(codecName);
                        else failedCodecs.add(codecName);
                    }
                    releaseCodec(); queue.recover(); pending = null; configuredPacket = null;
                    status.accept("Video: " + e.getMessage() + ". Waiting for another keyframe…");
                    Log.w("SFT-TV", "Video", e);
                }
            }
        } catch (InterruptedException ignored) {
        } finally { releaseCodec(); }
    }
    private static boolean software(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isSoftwareOnly();
        String n = info.getName().toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.");
    }
    private static boolean lowLatency(MediaCodecInfo info) {
        try { return Build.VERSION.SDK_INT >= 30 && info.getCapabilitiesForType(MIME)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency); }
        catch (IllegalArgumentException e) { return false; }
    }
    private void configure(SftProtocol.Packet packet) throws IOException {
        byte[][] csd = SftProtocol.parameterSets(packet.data);
        List<MediaCodecInfo> candidates = new ArrayList<>();
        for (MediaCodecInfo item : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (item.isEncoder() || failedCodecs.contains(item.getName())) continue;
            for (String type : item.getSupportedTypes()) if (type.equalsIgnoreCase(MIME)) {
                try {
                    if (item.getCapabilitiesForType(MIME).getVideoCapabilities().isSizeSupported(width, height)) candidates.add(item);
                } catch (RuntimeException ignored) { }
                break;
            }
        }
        Collections.sort(candidates, (a,b) -> Integer.compare(score(a), score(b)));
        String lastFailure = "No H.264 decoder is available for " + width + "×" + height;
        for (int i = 0; i < candidates.size(); i++) {
            MediaCodecInfo item = candidates.get(i);
            String name = item.getName();
            int top = slowWhenOptimized.contains(name) ? 0 : vendorFailed.contains(name) || !LowLatency.worthVendorLevel(lowLatency(item), software(item)) ? 1 : 2;
            for (int level = top; level >= 0; level--) {
                MediaCodec attempt = null;
                try {
                    MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(csd[0]));
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(csd[1]));
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SftProtocol.MAX_VIDEO);
                    if (level == 2) {
                        LowLatency.vendorOptions(format, name);
                    } else if (level == 1) {
                        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                        if (Build.VERSION.SDK_INT >= 30 && lowLatency(item)) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    }
                    if (level >= 1 && Build.VERSION.SDK_INT >= 24) {
                        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
                        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
                    }
                    attempt = MediaCodec.createByCodecName(name);
                    attempt.configure(format, surface, null, 0); attempt.start();
                    codec = attempt; codecName = name; codecOptimized = level >= 1; codecLevel = level;
                    inFlight = 0;
                    watchStartNs = 0; alternatives = level + (candidates.size() - 1 - i);
                    stats.width = width; stats.height = height;
                    stats.decoder = codecName + (software(item) ? " · software" : " · hardware")
                            + (level == 2 ? " · vendor low latency" : level == 1 && lowLatency(item) ? " · low latency" : "");
                    status.accept("Receiving " + width + "×" + height + (software(item) ? " · compatibility mode" : ""));
                    return;
                } catch (IOException | RuntimeException e) {
                    lastFailure = item.getName() + ": " + e.getMessage();
                    if (attempt != null) try { attempt.release(); } catch (RuntimeException ignored) { }
                }
            }
        }
        throw new IOException(lastFailure);
    }
    /** Every 2 s of feeding: fewer than a third of the frames decoded is a decoder
     *  that cannot keep up (the first 2 s after a start are its warm-up). */
    private void checkThroughput() throws SlowDecoderException {
        long now = System.nanoTime();
        if (watchStartNs == 0) { watchStartNs = now + 2_000_000_000L; return; }
        if (now < watchStartNs) { watchInputs = stats.inputSubmitted; watchDecoded = stats.decoded; return; }
        if (now - watchStartNs < 2_000_000_000L) return;
        long in = stats.inputSubmitted - watchInputs, out = stats.decoded - watchDecoded;
        watchStartNs = now; watchInputs = stats.inputSubmitted; watchDecoded = stats.decoded;
        if (alternatives > 0 && in >= 40 && out * 3 < in)
            throw new SlowDecoderException(codecName + " decoded " + out + " of " + in + " frames"
                    + (codecOptimized ? "; trying it without low-latency mode" : "; trying another decoder"));
    }
    private static int score(MediaCodecInfo item) { return software(item) ? 2 : lowLatency(item) ? 0 : 1; }
    /** waitUs: how long the first look may wait for an output (0: only what is ready). */
    private void drain(long waitUs) {
        int latest = -1;
        Stamp latestStamp = null;
        for (int n = 0; n < 32; n++) {
            int index = codec.dequeueOutputBuffer(info, n == 0 ? waitUs : 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue;
            if (index < 0) break;
            if ((info.flags & (MediaCodec.BUFFER_FLAG_CODEC_CONFIG | MediaCodec.BUFFER_FLAG_END_OF_STREAM)) != 0) {
                codec.releaseOutputBuffer(index, false); continue;
            }
            Stamp stamp = stamps.remove(info.presentationTimeUs);
            if (stamp == null && !stamps.isEmpty()) {
                // SFTR's encoder has no B frames. Some TV codecs rewrite timestamps;
                // FIFO metadata is safe here, and image output must not depend on it.
                stamp = stamps.remove(stamps.keySet().iterator().next()); stats.unmatchedPts++;
            }
            stats.decoded++; lastOutputNs = System.nanoTime(); inputsSinceOutput = 0;
            if (inFlight > 0) inFlight--;
            if (smooth) {
                synchronized (outLock) {
                    if (stopped) { codec.releaseOutputBuffer(index, false); continue; }
                    ready.addLast(new Out(codec, index, stamp)); pacer.arrived(lastOutputNs);
                    // the presenter is not running (screen off): keep the decoder's buffers free
                    while (ready.size() > MAX_READY) { discard(ready.pollFirst()); stats.skipped++; }
                }
                continue;
            }
            if (latest >= 0) { codec.releaseOutputBuffer(latest, false); stats.skipped++; }
            latest = index; latestStamp = stamp;
        }
        if (latest >= 0) {
            boolean show = !stopped;
            // "Smooth video" off: present the newest decoded output immediately. A fixed
            // 120 ms cutoff could discard every frame on a TV during startup or in a deeper pipeline.
            if (show) codec.releaseOutputBuffer(latest, System.nanoTime());
            else codec.releaseOutputBuffer(latest, false);
            if (show) shown(latestStamp); else stats.skipped++;
        }
    }
    private void releaseCodec() {
        MediaCodec old = codec; codec = null; stamps.clear(); inFlight = 0;
        lastPts = ptsOriginNs = firstInputNs = lastOutputNs = 0; inputsSinceOutput = 0;
        synchronized (outLock) {
            ready.clear();   // stop() takes every buffer back
            if (old != null) {
                try { old.stop(); } catch (RuntimeException ignored) { }
                try { old.release(); } catch (RuntimeException ignored) { }
            }
        }
    }
}
