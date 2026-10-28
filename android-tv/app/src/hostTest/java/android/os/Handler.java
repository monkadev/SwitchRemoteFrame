package android.os;
public class Handler {
    private final Looper looper;
    public Handler(Looper looper) { this.looper = looper; }
    public final boolean post(Runnable r) { looper.tasks.add(r); return true; }
}
