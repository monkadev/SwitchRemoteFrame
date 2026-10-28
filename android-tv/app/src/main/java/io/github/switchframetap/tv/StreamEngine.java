/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.view.Surface;
import android.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

final class StreamEngine {
    interface Listener {
        void status(String message);
        /** The console says why there is no picture (reason 0 clears it). */
        void consoleStatus(int reason, String head, String detail);
        void usbPermission(UsbDevice device);
        void connectedAddress(String ip);
        /** 0.7.16: the game's rumble of the remote gamepad (receiver thread). */
        void rumble(Rumble.Motors motors);
    }
    final StreamStats stats = new StreamStats();
    private final Context context;
    private final Surface surface;
    private final boolean usb, stableAudio;
    private final boolean smoothVideo;   // 0.7.16: FramePacer
    private final float refreshHz;
    private final String address;
    private String rememberedAddress;
    private final Listener listener;
    private volatile boolean stopped, muted;
    private volatile SftProtocol.Source source;
    private volatile DatagramSocket discovery;
    private volatile AudioPump audio;
    private volatile VideoDecoder decoder;
    private final Thread thread;
    // remote touch (0.7.16): the picture's fingers go out on their own thread,
    // so a slow socket or USB transfer never holds up the touch events
    private final Touch.Queue touches = new Touch.Queue();
    private final Thread touchThread;
    // remote gamepad (0.7.16): only the newest controller state matters, so the
    // sender keeps one and sends it when it changes (and while anything is held)
    private final Object padLock = new Object();
    private Pad.State padWanted;
    private boolean padDirty;
    private final Thread padThread;
    // rumble (0.7.16): asked for with "SFTV" every 2 s while wanted (Rumble.java)
    private volatile boolean rumbleWanted;
    StreamEngine(Context context, Surface surface, boolean usb, String address, String lastAddress,
                 boolean stableAudio, boolean muted, boolean smoothVideo, float refreshHz, Listener listener) {
        this.context = context.getApplicationContext(); this.surface = surface;
        this.usb = usb; this.address = address;
        this.rememberedAddress = lastAddress;
        this.stableAudio = stableAudio; this.muted = muted; this.listener = listener;
        this.smoothVideo = smoothVideo; this.refreshHz = refreshHz;
        thread = new Thread(this::run, "sft-receiver");
        touchThread = new Thread(this::sendTouches, "sft-touch");
        padThread = new Thread(this::sendPads, "sft-pad");
    }
    void start() { thread.start(); touchThread.start(); padThread.start(); }
    /** The Switch's controller now (Pad.State.NONE: no gamepad, the console
     *  takes its virtual controller away). */
    void pad(Pad.State state) {
        if (stopped || state == null) return;
        synchronized (padLock) { padWanted = state; padDirty = true; padLock.notifyAll(); }
    }
    /** 0.7.16: ask the console for the game's rumble (or stop asking). */
    void wantRumble(boolean value) {
        rumbleWanted = value;
        if (value) synchronized (padLock) { padLock.notifyAll(); }
    }
    /** The touch screen's state now (Touch.Frame.NONE: every finger up). */
    void touch(Touch.Frame frame) { if (!stopped) touches.offer(frame); }
    void mute(boolean value) { muted = value; AudioPump a = audio; if (a != null) a.mute(value); }
    void stop() {
        stopped = true; thread.interrupt(); touchThread.interrupt(); padThread.interrupt();
        VideoDecoder v = decoder; if (v != null) v.requestStop();
        AudioPump a = audio; if (a != null) a.requestStop();
        DatagramSocket d = discovery; if (d != null) d.close();
        closeSource();
    }
    private void closeSource() {
        SftProtocol.Source s = source;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
    private void status(String message) { if (!stopped) listener.status(message); }
    private void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        try {
            while (!stopped) {
                VideoDecoder video = null;
                AudioPump a = null;
                try {
                    SftProtocol.Source connected = usb ? usbConnect() : networkConnect();
                    if (connected == null || stopped) break;
                    source = connected;
                    VideoQueue queue = new VideoQueue();
                    a = new AudioPump(stats, stableAudio); audio = a; a.mute(muted); a.start();
                    video = new VideoDecoder(surface, queue, stats, this::status, smoothVideo, refreshHz); decoder = video; video.start();
                    if (stopped) break;
                    status("Connected. Open a game on the Switch…");
                    long keyAskedNs = 0;
                    while (!stopped) {
                        SftProtocol.Packet packet = SftProtocol.readPacket(connected, () -> stopped);
                        stats.bytes += packet.data.length + 32;
                        if (packet.rumble()) {
                            Rumble.Motors m = Rumble.parse(packet.data);
                            if (m != null && !stopped) { stats.rumblePackets++; listener.rumble(m); }
                        }
                        else if (packet.status()) {
                            if (!stopped) listener.consoleStatus(packet.statusReason(), packet.statusHead(), packet.statusDetail());
                        }
                        else if (packet.audio()) {
                            stats.audioPackets++; stats.audioBytes += packet.data.length;
                            a.offer(packet);
                        }
                        else {
                            stats.received++; stats.lastVideoNs = packet.receivedNs;
                            queue.offer(packet);
                            if (queue.takeKeyRequest() && packet.receivedNs - keyAskedNs > 300_000_000L) {
                                connected.requestKeyframe(); keyAskedNs = packet.receivedNs;
                            }
                            stats.videoResets = queue.resets();
                            stats.videoDropped = queue.dropped();
                            // Video IDR recovery must not repeatedly flush the audio sink.
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    if (!stopped) {
                        Log.w("SFT-TV", "Transport", e);
                        status(e.getMessage() + ". Reconnecting…");
                    }
                } finally {
                    if (!stopped) listener.rumble(Rumble.Motors.OFF);   // nothing more comes from this connection
                    closeSource(); source = null;
                    // Cleanup must finish even when stop() interrupted the receiver.
                    Thread.interrupted();
                    if (video != null) video.close();
                    if (a != null) a.close();
                    audio = null;
                    decoder = null;
                    stats.lastRenderedNs = 0; stats.audioQueuedMs = 0;
                }
                pause(800);
            }
        } finally { closeSource(); }
    }
    private void sendTouches() {
        Touch.Frame last = Touch.Frame.NONE, retry = null;
        SftProtocol.Source lastSource = null;
        long lastSentNs = 0;
        int seq = 0, retries = 0;
        while (!stopped) {
            Touch.Frame f;
            try { f = touches.take(100_000_000L); } catch (InterruptedException e) { break; }
            SftProtocol.Source s = source;
            if (s != lastSource) { lastSource = s; last = Touch.Frame.NONE; retry = null; }   // a new connection: no finger is down there
            long now = System.nanoTime();
            if (f != null) { retry = null; retries = 0; }   // every message is the whole state: the newest supersedes
            else if (retry != null && ++retries <= 10) f = retry;                         // the last one did not go out (1 s at most)
            else if (last.count > 0 && now - lastSentNs >= Touch.KEEPALIVE_NS) f = last;  // a resting finger
            else continue;
            if (s == null) continue;
            if (s.sendInput(Touch.encode(f, seq++ & 0xffff))) {
                stats.touchSent++; last = f; lastSentNs = now; retry = null;
            } else {
                stats.touchFailed++; retry = f;
            }
        }
    }
    private void sendPads() {
        Pad.State last = null;            // what this connection's console has
        SftProtocol.Source lastSource = null;
        long lastSentNs = 0, askedNs = 0;
        int seq = 0;
        while (!stopped) {
            Pad.State want;
            synchronized (padLock) {
                if (!padDirty) {
                    try { padLock.wait(50); } catch (InterruptedException e) { break; }
                }
                want = padWanted; padDirty = false;
            }
            SftProtocol.Source s = source;
            if (s != lastSource) { lastSource = s; last = null; askedNs = 0; }   // a new connection: it has no controller yet
            // 0.7.16: "rumble, please" - repeated, the console forgets it after 5 s
            if (s != null && rumbleWanted && (askedNs == 0 || System.nanoTime() - askedNs >= Rumble.ASK_EVERY_NS)) {
                askedNs = s.sendInput(Rumble.REQUEST) ? System.nanoTime() : System.nanoTime() - Rumble.ASK_EVERY_NS + 250_000_000L;
            }
            if (want == null || s == null) continue;
            if (last == null && !want.connected) { last = want; continue; }   // nothing to take away
            long now = System.nanoTime();
            boolean changed = !want.equals(last);
            boolean keepalive = want.connected && !want.neutral() && now - lastSentNs >= Pad.KEEPALIVE_NS;
            if (!changed && !keepalive) continue;
            if (s.sendInput(Pad.encode(want, seq++ & 0xffff))) {
                stats.padSent++; last = want; lastSentNs = now;
            } else {
                stats.padFailed++;
                synchronized (padLock) { padDirty = true; }   // try again shortly
                pause(20);
            }
        }
    }
    private SftProtocol.Source usbConnect() throws IOException {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            status("This device does not support USB host mode. Use Network instead.");
            while (!stopped) pause(500);
            return null;
        }
        UsbManager manager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
        Set<String> requested = new HashSet<>();
        while (!stopped) {
            UsbDevice device = Transports.find(manager);
            if (device == null) {
                requested.clear();
                status("Waiting for the Switch over USB. Use USB viewer mode and a data cable.");
            } else if (!manager.hasPermission(device)) {
                status("Allow USB access. If permission was denied, select Connect to try again.");
                if (requested.add(device.getDeviceName())) listener.usbPermission(device);
            } else {
                source = new Transports.Usb(manager, device);
                if (stopped) { closeSource(); return null; }
                stats.transport = "USB";
                return source;
            }
            pause(500);
        }
        return null;
    }
    /** The address answered, but nothing listens there: the Switch is set to USB. */
    static final class RefusedException extends IOException {
        RefusedException(String message, Throwable cause) { super(message, cause); }
    }
    private Transports.Tcp tcp(String host, int port) throws IOException {
        Transports.Tcp tcp = new Transports.Tcp(); source = tcp;
        try {
            if (stopped) throw new IOException("Connection stopped");
            tcp.connect(host, port);
            if (stopped) throw new IOException("Connection stopped");
            stats.transport = "Network · " + host;
            rememberedAddress = host;
            listener.connectedAddress(host);
            return tcp;
        } catch (IOException e) {
            tcp.close(); source = null;
            // refused: the Switch is there, but set to USB (#24)
            if (String.valueOf(e.getMessage()).contains("ECONNREFUSED"))
                throw new RefusedException("The Switch at " + host + " answered but is not streaming over the network: set Connection to Network on the Switch and restart it", e);
            throw e;
        }
    }
    private SftProtocol.Source networkConnect() throws IOException {
        if (!address.isEmpty()) {
            status("Connecting to " + address + ":9950…");
            return tcp(address, Transports.PORT);
        }
        String recent = rememberedAddress;
        DatagramSocket beacon = new DatagramSocket(null);
        discovery = beacon;
        try {
            beacon.setReuseAddress(true);
            beacon.bind(new InetSocketAddress(Transports.BEACON));
            beacon.setSoTimeout(400);
            long nextTry = 0;
            String hint = null;
            while (!stopped) {
                if (SftProtocol.ipv4(recent) && System.nanoTime() >= nextTry) {
                    try { return tcp(recent, Transports.PORT); }
                    catch (IOException e) {
                        nextTry = System.nanoTime() + 3_000_000_000L;
                        hint = e instanceof RefusedException ? e.getMessage() : null;
                    }
                }
                status(hint != null ? hint : "Searching the network. On the Switch, set Connection to Network in the Switch Remote Frame app (then restart the Switch), or enter its IP address.");
                byte[] bytes = new byte[256];
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                try {
                    beacon.receive(packet);
                    int port = SftProtocol.beaconPort(new String(bytes, 0, packet.getLength(), StandardCharsets.US_ASCII));
                    if (port != Transports.PORT || !(packet.getAddress() instanceof Inet4Address)) continue;
                    recent = packet.getAddress().getHostAddress(); // trust the sender address, not the beacon's IP
                    try { return tcp(recent, port); } catch (IOException ignored) { }
                } catch (SocketTimeoutException ignored) { }
            }
        } finally { beacon.close(); discovery = null; }
        return null;
    }
    private void pause(int ms) {
        if (stopped) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
