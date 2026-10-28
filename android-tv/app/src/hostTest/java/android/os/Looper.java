package android.os;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Host double: runs posted tasks and lets the thread's Choreographer tick. */
public final class Looper {
    final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    volatile boolean quit;
    void loop() {
        while (!quit) {
            try {
                Runnable r = tasks.poll(1, TimeUnit.MILLISECONDS);
                if (r != null) r.run();
            } catch (InterruptedException e) { return; }
            android.view.Choreographer.tick();
        }
    }
}
