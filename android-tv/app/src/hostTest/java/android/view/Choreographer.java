package android.view;
import java.util.ArrayList;
import java.util.List;

/** Host double: a display refreshing every periodNs, driven by the Looper double. */
public final class Choreographer {
    public interface FrameCallback { void doFrame(long frameTimeNanos); }
    public static volatile long periodNs = 16_666_667L;
    private static final ThreadLocal<Choreographer> LOCAL = new ThreadLocal<Choreographer>() {
        @Override protected Choreographer initialValue() { return new Choreographer(); }
    };
    private final List<FrameCallback> pending = new ArrayList<>();
    private long next;
    public static Choreographer getInstance() { return LOCAL.get(); }
    public void postFrameCallback(FrameCallback c) { pending.add(c); }
    /** Test double only: called by the Looper between tasks. */
    public static void tick() {
        Choreographer c = LOCAL.get();
        long now = System.nanoTime();
        if (c.next == 0) c.next = now + periodNs;
        if (now < c.next) return;
        long t = c.next;
        c.next += periodNs;
        if (c.next < now) c.next = now + periodNs;
        List<FrameCallback> run = new ArrayList<>(c.pending); c.pending.clear();
        for (FrameCallback cb : run) cb.doFrame(t);
    }
}
