package android.os;
public class HandlerThread extends Thread {
    private final Looper looper = new Looper();
    public HandlerThread(String name, int priority) { super(name); setDaemon(true); }
    @Override public void run() { looper.loop(); }
    public Looper getLooper() { return looper; }
    public boolean quit() { looper.quit = true; return true; }
}
