package android.media;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal driver model: delayed first input, delayed output, modified PTS, stalled HW. */
public final class MediaCodec {
    public static final int INFO_OUTPUT_FORMAT_CHANGED=-2, INFO_OUTPUT_BUFFERS_CHANGED=-3;
    public static final int BUFFER_FLAG_CODEC_CONFIG=2, BUFFER_FLAG_END_OF_STREAM=4;
    public static volatile int inputDelayMs, outputDelayMs, outputJitterUs;
    private static final java.util.Random jitter=new java.util.Random(7);
    public static volatile boolean rewritePts, stallHardware;
    /** A hardware decoder configured with this key accepts frames and never outputs one. */
    public static volatile String stallWithKey;
    /** The last format a decoder was configured with, and its name. */
    public static volatile MediaFormat lastFormat; public static volatile String lastName;
    private boolean stalled;
    private final String name;
    public static final AtomicInteger created=new AtomicInteger(), flushed=new AtomicInteger();
    private final boolean hardware;
    private final ArrayDeque<Output> outputs=new ArrayDeque<>();
    private final ByteBuffer input=ByteBuffer.allocate(8*1024*1024);
    private long readyNs;
    private static final class Output {
        long pts, readyNs;
        Output(long pts) { this.pts=pts; readyNs=System.nanoTime()+outputDelayMs*1_000_000L+(outputJitterUs>0?jitter.nextInt(outputJitterUs)*1000L:0); }
    }
    public static final class BufferInfo { public long presentationTimeUs; public int flags,size; }
    private MediaCodec(String name) { this.name=name; hardware=!name.contains("google")&&!name.startsWith("c2.android"); created.incrementAndGet(); }
    public static MediaCodec createByCodecName(String name) throws IOException { return new MediaCodec(name); }
    public void configure(MediaFormat format,Surface surface,Object crypto,int flags) {
        lastFormat=format; lastName=name;
        String k=stallWithKey; stalled=hardware&&k!=null&&format.ints.containsKey(k);
    }
    public void start() { readyNs=System.nanoTime()+inputDelayMs*1_000_000L; }
    public int dequeueInputBuffer(long timeoutUs) {
        if (System.nanoTime()>=readyNs) return 0;
        try { Thread.sleep(Math.max(1,timeoutUs/1000)); } catch (InterruptedException ignored) { }
        return -1;
    }
    public ByteBuffer getInputBuffer(int index) { return input; }
    public void queueInputBuffer(int index,int offset,int length,long pts,int flags) {
        if (!(hardware&&stallHardware)&&!stalled) outputs.add(new Output(pts));
    }
    /** As a real codec: returns as soon as an output is ready, or after timeoutUs. */
    public int dequeueOutputBuffer(BufferInfo info,long timeoutUs) {
        long deadline=System.nanoTime()+Math.max(0,timeoutUs)*1000;
        while (true) {
            Output head=outputs.peek();
            long now=System.nanoTime();
            if (head!=null&&head.readyNs<=now) {
                outputs.remove(); info.presentationTimeUs=rewritePts?0:head.pts;
                info.flags=0; info.size=0; // Surface outputs can have no accessible byte buffer.
                pickupNs.add(now-head.readyNs);
                return 0;
            }
            if (timeoutUs<=0||now>=deadline) return -1;
            long until=head!=null?Math.min(head.readyNs,deadline):deadline;
            java.util.concurrent.locks.LockSupport.parkNanos(Math.max(20_000L,until-now));
        }
    }
    /** How long each decoded frame waited for the app to collect it (ns). */
    public static final java.util.List<Long> pickupNs=java.util.Collections.synchronizedList(new java.util.ArrayList<Long>());
    public void releaseOutputBuffer(int index,boolean render) { }
    /** Frames sent to the Surface (System.nanoTime()), for the pacing test. */
    public static final java.util.List<Long> renderedAt=java.util.Collections.synchronizedList(new java.util.ArrayList<Long>());
    public void releaseOutputBuffer(int index,long timestampNs) { renderedAt.add(System.nanoTime()); }
    public void flush() { outputs.clear(); flushed.incrementAndGet(); }
    public void stop() { }
    public void release() { outputs.clear(); }
    public static void reset() { inputDelayMs=outputDelayMs=outputJitterUs=0; rewritePts=stallHardware=false; stallWithKey=null; lastFormat=null; lastName=null; created.set(0); flushed.set(0); renderedAt.clear(); pickupNs.clear(); }
}
