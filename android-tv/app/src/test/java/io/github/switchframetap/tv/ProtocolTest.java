package io.github.switchframetap.tv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Exercises transport fragmentation and reference recovery, not Android mocks. */
public final class ProtocolTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name); checks++;
    }
    private static final class Chunks implements SftProtocol.Source {
        final ByteArrayInputStream stream;
        int calls;
        Chunks(byte[] data) { stream = new ByteArrayInputStream(data); }
        @Override public int read(byte[] b, int offset, int length) {
            if (++calls % 4 == 0) return 0; // idle timeout between partial reads
            return stream.read(b, offset, Math.min(length, calls % 17 + 1));
        }
        @Override public void close() { }
    }
    private static byte[] wire(int flags, int width, int height, long seq, byte[] payload) {
        byte[] h = new byte[32 + payload.length];
        put(h,0,0x52544653); h[4] = 2; h[6] = (byte)flags;
        put(h,8,width); put(h,12,height); put(h,16,25000); put(h,20,payload.length); put(h,28,seq);
        System.arraycopy(payload,0,h,32,payload.length); return h;
    }
    private static void put(byte[] h, int i, long value) { for (int j=0;j<4;j++) h[i+j]=(byte)(value >>> (j*8)); }
    private static void invalid(byte[] data, String name) throws Exception {
        try { SftProtocol.readPacket(new Chunks(data), () -> false); throw new AssertionError(name); }
        catch (IOException expected) { checks++; }
    }
    private static SftProtocol.Packet frame(long seq, boolean key, int w, long ns) {
        return new SftProtocol.Packet(2 | (key ? 4 : 0), w,720,seq,20000,new byte[100],ns);
    }
    public static void main(String[] args) throws Exception {
        byte[] nal = {0,0,0,1,0x67,0x64,0,0,1,0x68,0x12,0,0,1,0x65,9};
        byte[] audio = {1,2,3,4,5,6,7,8};
        ByteArrayOutputStream concatenated = new ByteArrayOutputStream();
        concatenated.write(wire(8,48000,2,1,audio)); concatenated.write(wire(6,1280,720,0xffffffffL,nal));
        Chunks source = new Chunks(concatenated.toByteArray());
        SftProtocol.Packet a = SftProtocol.readPacket(source, () -> false);
        SftProtocol.Packet v = SftProtocol.readPacket(source, () -> false);
        check(a.audio() && Arrays.equals(a.data,audio), "interleaved PCM with partial reads and timeouts");
        check(v.key() && v.sequence == 0xffffffffL && v.consoleAgeUs == 25000, "unsigned fields and v2 age");
        check(Arrays.equals(v.data,nal), "exact video boundary");
        byte[][] csd = SftProtocol.parameterSets(nal);
        check(csd[0][4] == 0x67 && csd[1][3] == 0x68, "mixed 3/4 byte Annex-B start codes");
        // The console's USB keep-alive (an empty header) is skipped, not an error.
        ByteArrayOutputStream hello = new ByteArrayOutputStream();
        hello.write(wire(0,0,0,0,new byte[0])); hello.write(wire(0,0,0,0,new byte[0])); hello.write(wire(8,48000,2,2,audio));
        SftProtocol.Packet afterHello = SftProtocol.readPacket(new Chunks(hello.toByteArray()), () -> false);
        check(afterHello.audio() && afterHello.sequence == 2, "empty keep-alive headers skipped");
        // Status: audio + status flags, rate 0; version, reason, 64-byte head, 120-byte detail.
        byte[] st = new byte[192];
        put(st,0,1); put(st,4,1);
        byte[] head = "Connected - no game is running".getBytes("UTF-8"), detail = "Start a game.".getBytes("UTF-8");
        System.arraycopy(head,0,st,8,head.length); System.arraycopy(detail,0,st,72,detail.length);
        SftProtocol.Packet status = SftProtocol.readPacket(new Chunks(wire(24,0,0,3,st)), () -> false);
        check(status.status() && !status.audio() && status.statusReason() == 1, "status packet recognised");
        check(status.statusHead().equals("Connected - no game is running") && status.statusDetail().equals("Start a game."), "status text");
        invalid(wire(18,0,0,0,st), "status only together with audio");
        invalid(wire(24,0,0,0,new byte[4]), "status too short");
        invalid(Arrays.copyOf(wire(6,1280,720,0,nal),37), "truncated payload");
        byte[] bad = wire(6,1280,720,0,nal); bad[0]=0; invalid(bad,"bad magic");
        bad = wire(6,1280,720,0,nal); bad[4]=1; invalid(bad,"old version");
        bad = wire(6,1280,720,0,nal); put(bad,20,0xffffffffL); invalid(bad,"unsigned oversized allocation rejected");
        invalid(wire(8,44100,2,0,audio), "unsupported sample rate");
        invalid(wire(8,48000,2,0,new byte[3]), "unaligned PCM");
        invalid(wire(2,4096,720,0,nal), "invalid dimensions");
        invalid(wire(10,1280,720,0,nal), "audio/video flag collision");
        try { SftProtocol.parameterSets(new byte[]{0,0,1,0x65,1}); throw new AssertionError("missing SPS"); }
        catch (IOException expected) { checks++; }
        try { SftProtocol.readPacket(new Chunks(new byte[0]), () -> true); throw new AssertionError("cancellation"); }
        catch (IOException expected) { checks++; }
        check(SftProtocol.ipv4("192.168.1.50") && !SftProtocol.ipv4("256.1.1.1")
                && !SftProtocol.ipv4("1.2.3.4x") && !SftProtocol.ipv4("1..3.4"), "strict manual IP");
        check(SftProtocol.beaconPort("SFTAP1 192.168.1.3 9950 Switch Frame Tap") == 9950
                && SftProtocol.beaconPort("SFTAP1 1.2.3.4 65536") == -1
                && SftProtocol.beaconPort("OTHER 1.2.3.4 9950") == -1, "beacon validation");
        VideoQueue q = new VideoQueue();
        q.offer(frame(0,false,1280,0)); check(q.poll(1) == null,"initial P ignored");
        q.offer(frame(1,true,1280,0)); check(q.poll(1).discontinuity,"start at key frame");
        q.offer(frame(3,false,1280,0)); check(q.poll(1) == null,"gap invalidates P references");
        q.offer(frame(4,true,1280,0)); check(q.poll(1).discontinuity,"gap recovers at IDR");
        q.offer(frame(5,false,1920,0)); check(q.poll(1) == null,"resolution change waits for IDR");
        q.offer(frame(6,true,1920,0)); check(q.poll(1).discontinuity,"resolution change recovers");
        // 0.7.16: a Wi-Fi burst of 8 frames (more than the old limit of 6) is kept and decoded in order
        for(int i=7;i<=14;i++) q.offer(frame(i,false,1920,0));
        boolean burstKept = true;
        for(int i=7;i<=14;i++) { SftProtocol.Packet b = q.poll(1); burstKept &= b != null && b.sequence == i && !b.discontinuity; }
        check(burstKept,"a burst below the limit is kept in order");
        q.offer(frame(15,false,1920,0)); q.offer(frame(16,false,1920,150_000_000L));
        check(q.poll(1).sequence==15 && q.poll(1).sequence==16,"a 150 ms backlog is kept");
        for(int i=17;i<=17+VideoQueue.MAX_FRAMES;i++) q.offer(frame(i,false,1920,150_000_000L));
        check(q.poll(1)==null,"overflow discards complete pending dependency chain");
        q.offer(frame(18+VideoQueue.MAX_FRAMES,false,1920,150_000_000L)); check(q.poll(1)==null,"overflow ignores dependent P");
        int k = 19+VideoQueue.MAX_FRAMES;
        q.offer(frame(k,true,1920,150_000_000L)); check(q.poll(1).discontinuity,"overflow recovers at key");
        q.offer(frame(k+1,false,1920,150_000_000L)); q.offer(frame(k+2,false,1920,150_000_000L+VideoQueue.LATE_NS+10_000_000L));
        check(q.poll(1)==null,"aged queue invalidates references");
        q.offer(frame(0,true,1920,500_000_000L)); check(q.poll(1).discontinuity,"new session sequence restart");
        VideoQueue wrap = new VideoQueue(); wrap.offer(frame(0xffffffffL,true,1280,0)); wrap.poll(1);
        wrap.offer(frame(0,false,1280,0)); check(!wrap.poll(1).discontinuity,"unsigned sequence wrap");
        q.close(); q.offer(frame(1,true,1280,0)); check(q.poll(1)==null,"closed queue");
        framePacerChecks();
        Path fixture = Paths.get(args[0], "logs/m83-runI/stream-first10.sft");
        if (Files.exists(fixture)) {
            Chunks real = new Chunks(Files.readAllBytes(fixture));
            int frames = 0;
            while (real.stream.available() > 0) {
                SftProtocol.Packet packet = SftProtocol.readPacket(real, () -> false);
                check(!packet.audio() && packet.width == 1280 && packet.height == 720,"real Switch wire packet");
                SftProtocol.parameterSets(packet.data); frames++;
            }
            check(frames > 0, "real NVENC fixture parsed");
            System.out.println("Real Switch fixture: " + frames + " frames");
        } else System.out.println("Real Switch fixture unavailable; synthetic tests completed");
        touchChecks(args.length > 1 ? args[1] : null);
        padChecks(args.length > 2 ? args[2] : null);
        rumbleChecks();
        rootRumbleChecks();
        virtualPadChecks();
        System.out.println("PASS: " + checks + " checks");
    }

    /** Remote touch (0.7.16): the bytes must be what the console's
     *  sft_touch_proto.hpp parses; tier4/applet-mitm/test/touch_test.cpp checks that side. */
    private static void touchChecks(String dumpTo) throws Exception {
        Touch.Frame f = new Touch.Frame(2);
        f.x[0] = 640; f.y[0] = 360; f.id[0] = 0;
        f.x[1] = 5000; f.y[1] = -4; f.id[1] = 3;
        byte[] m = Touch.encode(f, 0x1234);
        check(m.length == 48 && m[0] == 'S' && m[1] == 'F' && m[2] == 'T' && m[3] == 'T', "SFTT magic, 48 bytes");
        check(m[4] == 1 && m[5] == 2 && (m[6] & 255) == 0x34 && (m[7] & 255) == 0x12, "version, count, sequence LE");
        check(SftProtocol.u16(m, 8) == 640 && SftProtocol.u16(m, 10) == 360 && m[12] == 0, "first finger");
        check(SftProtocol.u16(m, 16) == 1279 && SftProtocol.u16(m, 18) == 0 && m[20] == 3, "second finger clamped to 1280x720");
        boolean zero = true; for (int i = 24; i < 48; i++) zero &= m[i] == 0;
        check(zero, "unused slots are zero");
        byte[] none = Touch.encode(Touch.Frame.NONE, 0);
        check(none[5] == 0, "lift = count 0");
        check(Touch.toScreen(0f, 1000, 1280) == 0 && Touch.toScreen(999.9f, 1000, 1280) == 1279
              && Touch.toScreen(500f, 1000, 1280) == 640 && Touch.toScreen(-3f, 1000, 1280) == 0
              && Touch.toScreen(1500f, 1000, 1280) == 1279, "view -> touch screen mapping");
        // finger ids: lowest free, kept while down, reused after
        Touch.Fingers fingers = new Touch.Fingers();
        check(fingers.finger(7) == 0 && fingers.finger(9) == 1 && fingers.finger(7) == 0, "finger ids stay with their pointer");
        fingers.release(7);
        check(fingers.finger(11) == 0 && fingers.finger(9) == 1, "a freed id is reused");
        // the queue: moves merge, downs and ups never do
        Touch.Queue q = new Touch.Queue();
        Touch.Frame down = one(0, 10), move1 = one(0, 20), move2 = one(0, 30);
        q.offer(down); q.offer(move1); q.offer(move2);
        check(q.size() == 1 && q.take(0) == move2, "moves of the same finger merge into the newest");
        q.offer(down); q.offer(Touch.Frame.NONE); q.offer(down); q.offer(Touch.Frame.NONE);
        check(q.size() == 4, "a quick tap keeps its down and its up");
        q.clear();
        for (int i = 0; i < 100; i++) q.offer(i % 2 == 0 ? down : Touch.Frame.NONE);
        check(q.size() == Touch.Queue.MAX, "bounded");
        check(q.take(1_000_000L) != null && new Touch.Queue().take(1_000_000L) == null, "take waits, then gives up");
        if (dumpTo != null) {   // for the C++ side's cross-check
            Files.write(Paths.get(dumpTo), m);
        }
    }
    /** Remote gamepad (0.7.16): the bytes must be what the console's
     *  sft_pad_proto.hpp parses (touch_test.cpp reads the dump); the mapping,
     *  the sticks and the merging of several pads. */
    private static void padChecks(String dumpTo) throws Exception {
        Pad.State st = new Pad.State(true, (1 << Pad.A) | (1 << Pad.ZR) | (1 << Pad.HOME), 40000, 0, 0, -40000);
        byte[] m = Pad.encode(st, 0x0102);
        check(m.length == 32 && m[0] == 'S' && m[1] == 'F' && m[2] == 'T' && m[3] == 'G', "SFTG magic, 32 bytes");
        check(m[4] == 1 && m[5] == 1 && m[6] == 2 && m[7] == 1, "version, connected, sequence LE");
        check(SftProtocol.u16(m, 8) == ((1 << 0) | (1 << 9)) && SftProtocol.u16(m, 10) == (1 << 2), "buttons as the Switch's bits (Home = 18)");
        check(SftProtocol.u16(m, 12) == 32767 && SftProtocol.u16(m, 18) == (-32767 & 0xffff), "sticks clamped to +-32767");
        boolean zero = true; for (int i = 20; i < 32; i++) zero &= m[i] == 0;
        check(zero, "reserved bytes are zero");
        byte[] none = Pad.encode(Pad.State.NONE, 0);
        check(none[5] == 0 && SftProtocol.u16(none, 8) == 0, "no gamepad = flags 0, no input");
        check(new Pad.State(true, 0xFFFFFFFF, 0, 0, 0, 0).buttons == Pad.VALID, "unknown buttons are dropped");

        // sticks: dead zone, stretched travel, y up
        int[] c = Pad.stick(0.05f, 0.05f, 0.10f);
        check(c[0] == 0 && c[1] == 0, "inside the dead zone: centred");
        int[] up = Pad.stick(0f, -1f, 0.10f);
        check(up[0] == 0 && up[1] == 32767, "stick up (Android -1) is the Switch's +32767");
        int[] half = Pad.stick(0.55f, 0f, 0.10f);
        check(Math.abs(half[0] - 16384) < 40, "the travel beyond the dead zone is stretched to full");
        int[] diag = Pad.stick(1f, 1f, 0.10f);
        check(diag[0] == 23170 || Math.abs(diag[0] - 23170) < 3, "a diagonal stays on the circle");

        // the mapping: presets, remapping, saving
        Pad.Mapping pos = Pad.Mapping.defaults(true), let = Pad.Mapping.defaults(false);
        check(pos.codes[Pad.A][0] == Pad.KEY_BUTTON_B && pos.codes[Pad.B][0] == Pad.KEY_BUTTON_A && pos.facePreset() == 1, "positions: the bottom button is B");
        check(let.codes[Pad.A][0] == Pad.KEY_BUTTON_A && let.facePreset() == 2, "letters: A is A");
        Pad.Reader r = new Pad.Reader();
        r.key(Pad.KEY_BUTTON_A, true);
        check(r.buttons(pos) == (1 << Pad.B) && r.buttons(let) == (1 << Pad.A), "a key -> the mapped Switch button");
        r.key(Pad.KEY_BUTTON_A, false);
        r.axis(Pad.AXIS_HAT_Y, -1f); r.axis(Pad.AXIS_LTRIGGER, 0.8f);
        check(r.buttons(pos) == ((1 << Pad.UP) | (1 << Pad.ZL)), "the hat and the analog trigger");
        r.triggerL = Pad.AXIS_BRAKE;
        check(r.buttons(pos) == (1 << Pad.UP), "the trigger is whichever axis the device has");
        r.clear();
        Pad.Mapping m2 = pos.copy();
        m2.bind(Pad.X, Pad.KEY_BUTTON_A);
        check(m2.codes[Pad.X].length == 1 && m2.codes[Pad.X][0] == Pad.KEY_BUTTON_A && m2.codes[Pad.B].length == 0 && m2.facePreset() == 0,
              "remapping moves the input off its old button");
        m2.bind(Pad.ZL, Pad.axisCode(Pad.AXIS_Z, false));
        Pad.Mapping back = Pad.Mapping.parse(m2.serialize(), Pad.Mapping.defaults(true));
        boolean same = true;
        for (int b = 0; b < Pad.BUTTON_BITS; b++) same &= java.util.Arrays.equals(back.codes[b], m2.codes[b]);
        check(same, "a mapping survives saving");
        Pad.Mapping junk = Pad.Mapping.parse("0:96;x:1;99:5;1:abc;8:", Pad.Mapping.defaults(true));
        check(junk.codes[Pad.A][0] == 96 && junk.codes[Pad.ZL].length == 0 && junk.codes[Pad.B][0] == Pad.KEY_BUTTON_A, "a damaged mapping keeps what it can");
        check(pos.describe(Pad.ZL, Pad.PLAIN_NAMES).equals("L2 / LT") && pos.describe(Pad.CAPTURE, Pad.PLAIN_NAMES).equals("Not set"), "binding names");

        // several gamepads play as one
        Pad.Reader p1 = new Pad.Reader(), p2 = new Pad.Reader();
        p1.key(Pad.KEY_BUTTON_START, true); p1.axis(Pad.AXIS_X, 0.3f);
        p2.key(Pad.KEY_BUTTON_L1, true); p2.axis(Pad.AXIS_X, -0.9f);
        Pad.State both = Pad.combine(java.util.Arrays.asList(p1, p2), pos, 0.1f);
        check(both.connected && both.buttons == ((1 << Pad.PLUS) | (1 << Pad.L)) && both.lx < -25000, "buttons of both, the stick pushed furthest");
        check(Pad.combine(new java.util.ArrayList<Pad.Reader>(), pos, 0.1f) == Pad.State.NONE, "no gamepad: NONE");
        check(Pad.shortcutMask(Pad.SHORTCUT_MINUS_PLUS) == ((1 << Pad.MINUS) | (1 << Pad.PLUS)) && Pad.shortcutMask(Pad.SHORTCUT_OFF) == 0, "Settings shortcut");
        check(st.without(1 << Pad.A).buttons == ((1 << Pad.ZR) | (1 << Pad.HOME)) && !st.equals(Pad.State.IDLE) && Pad.State.IDLE.neutral(), "state helpers");
        if (dumpTo != null) Files.write(Paths.get(dumpTo), m);
    }
    /** Rumble (0.7.16): the bytes sft_rumble_proto.hpp writes (touch_test.cpp
     *  checks the console side against the same vector). */
    private static void rumbleChecks() throws Exception {
        byte[] body = { 1, 0, 0x02, 0x01, 0x00, (byte)0x80, (byte)0xFF, 0x40 };
        Rumble.Motors m = Rumble.parse(body);
        check(m != null && m.seq == 0x0102 && m.left == 128 && m.right == 255, "rumble body parsed");
        check(m.strongest() == 255 && !m.off() && Rumble.Motors.OFF.off(), "rumble strongest / off");
        byte[] v2 = body.clone(); v2[0] = 2;
        check(Rumble.parse(v2) == null && Rumble.parse(new byte[4]) == null, "rumble: other versions and short bodies ignored");
        SftProtocol.Packet p = SftProtocol.readPacket(new Chunks(wire(32, 0, 0, 9, body)), () -> false);
        check(p.rumble() && !p.audio() && !p.status() && p.data.length == 8 && Rumble.parse(p.data).right == 255, "rumble packet on the wire");
        byte[] longer = Arrays.copyOf(body, 16);
        check(SftProtocol.readPacket(new Chunks(wire(32, 0, 0, 9, longer)), () -> false).rumble(), "rumble body may grow");
        invalid(wire(32, 0, 0, 0, new byte[4]), "rumble too short");
        invalid(wire(32, 0, 0, 0, new byte[65]), "rumble too long");
        invalid(wire(34, 1280, 720, 0, body), "rumble flag only on its own");
        // a rumble then a frame on one stream
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        both.write(wire(32, 0, 0, 1, body));
        byte[] nal = {0,0,0,1,0x67,0x64,0,0,1,0x68,0x12,0,0,1,0x65,9};
        both.write(wire(6, 1280, 720, 1, nal));
        Chunks c = new Chunks(both.toByteArray());
        check(SftProtocol.readPacket(c, () -> false).rumble() && !SftProtocol.readPacket(c, () -> false).rumble(), "rumble before a frame");
        check(Rumble.amplitude(0) == 0 && Rumble.amplitude(4) == 0 && Rumble.amplitude(255) == 255, "amplitude ends");
        check(Rumble.amplitude(64) > 64 && Rumble.amplitude(64) < 255 && Rumble.amplitude(10) >= 1, "amplitude lifts the quiet part");
        int last = 0; boolean rising = true;
        for (int i = 0; i <= 255; i++) { int a = Rumble.amplitude(i); if (a < last) rising = false; last = a; }
        check(rising, "amplitude never falls");
        check(Rumble.differs(0, 1) && Rumble.differs(40, 0) && !Rumble.differs(100, 110) && Rumble.differs(100, 116), "differs");
        check(!Rumble.onOff(37) && Rumble.onOff(38), "on/off motors");
        check(new String(Rumble.REQUEST, "US-ASCII").equals("SFTV"), "rumble request");
    }

    /** Rumble through root (0.7.16): Linux hid-microsoft's report 3. */
    /** 0.7.16 Smooth video: in order, one per refresh, at most two waiting, even cadence. */
    private static void framePacerChecks() {
        final long ms = 1_000_000L;
        FramePacer p = new FramePacer(60f);
        check(p.drop(7, 1) == 3 && p.drop(4, 2) == 0 && p.drop(0, 3) == 0, "pacer: at most four waiting");
        // a burst (3 waiting, then 2, 1, 0) is never trimmed; a standing backlog of 2 is, after 250 ms
        FramePacer tp = new FramePacer(60f);
        int trimmed = 0;
        for (int i = 0; i < 60; i++) trimmed += tp.drop(3 - i % 4, 10 * ms + i * 16_666_667L);
        check(trimmed == 0, "pacer: bursts are shown whole");
        int standing = 0, firstAt = -1;
        for (int i = 0; i < 60; i++) { int d = tp.drop(2, 2_000 * ms + i * 16_666_667L); if (d > 0 && firstAt < 0) firstAt = i; standing += d; }
        check(standing >= 3 && firstAt >= 0 && firstAt <= 16, "pacer trims a standing backlog within 1/4 s");
        check(!p.present(0, 0), "pacer: nothing to show");
        check(p.present(1, 16 * ms), "pacer: first frame at once");
        p.shown(16 * ms);
        // 60 fps stream on a 60 Hz screen: every refresh
        for (int i = 0; i < 40; i++) p.arrived(1_000_000_000L + i * 16_666_667L);
        check(Math.abs(p.intervalNs() - 16_666_667L) < 2 * ms, "pacer measures 60 fps");
        check(p.present(1, 16 * ms + 16_666_667L), "pacer: 60 on 60 Hz, every refresh");
        // the same stream on a 120 Hz screen: every other refresh while on time,
        // every refresh while catching up
        FramePacer q = new FramePacer(120f);
        long t = 2_000_000_000L;
        for (int i = 0; i < 70; i++) { q.arrived(t); t += 16_666_667L; }
        for (int i = 0; i < 20; i++) q.vsync(t + i * 8_333_333L);
        check(Math.abs(q.vsyncNs() - 8_333_333L) < ms, "pacer vsync period 120 Hz");
        q.shown(t);
        check(!q.present(1, t + 8_333_333L), "pacer: 60 on 120 Hz waits one refresh");
        check(q.present(1, t + 16_666_666L), "pacer: 60 on 120 Hz shows the next");
        check(!q.present(2, t + 8_333_333L), "pacer: two waiting on 120 Hz keep the even cadence");
        check(q.present(3, t + 8_333_333L), "pacer: well behind, catches up each refresh");
        // a burst of frames after a gap still averages to the true rate
        FramePacer b = new FramePacer(60f);
        long u = 5_000_000_000L;
        for (int i = 0; i < 20; i++) {           // 5 frames together every 83 ms = 60 fps
            for (int j = 0; j < 5; j++) b.arrived(u + j * 200_000L);
            u += 83_333_333L;
        }
        check(Math.abs(b.intervalNs() - 16_666_667L) < 3 * ms, "pacer rate survives bursts");
        // a 30 fps game on 60 Hz: every other refresh
        FramePacer h = new FramePacer(60f);
        long v = 9_000_000_000L;
        for (int i = 0; i < 40; i++) { h.arrived(v); v += 33_333_333L; }
        h.shown(v);
        check(!h.present(1, v + 16_666_667L) && h.present(1, v + 33_333_333L), "pacer: 30 fps on 60 Hz");
        // a missed refresh callback does not stretch the measured period
        FramePacer m = new FramePacer(60f);
        m.vsync(0 + 1); m.vsync(16_666_667L); m.vsync(50_000_000L); m.vsync(66_666_667L);
        check(Math.abs(m.vsyncNs() - 16_666_667L) < ms, "pacer ignores missed refreshes");
    }
    private static void rootRumbleChecks() {
        byte[] r = RootRumble.report(100, 40);
        check(r.length == RootRumble.REPORT_SIZE && r.length == 9, "xbox report is 9 bytes");
        check(r[0] == 3 && r[1] == 0x03 && r[2] == 0 && r[3] == 0, "report 3, both motors, triggers off");
        check(r[4] == 100 && r[5] == 40 && (r[6] & 255) == 0xFF && r[7] == 0 && r[8] == 0, "strong, weak, 2.55 s once");
        byte[] over = RootRumble.report(300, -5);
        check(over[4] == 100 && over[5] == 0, "magnitudes clamped to 0..100");
        check(RootRumble.percent(0) == 0 && RootRumble.percent(255) == 100 && RootRumble.percent(1) == 1, "amplitude -> percent");
        check(RootRumble.matches("0005:0000045E:00000B13", "Xbox Wireless Controller"), "Series X|S over Bluetooth");
        check(RootRumble.matches("0005:0000045E:000002FD", ""), "One S 1708");
        check(RootRumble.matches("0005:0000045e:00000b20", null), "hex case");
        check(!RootRumble.matches("0003:0000045E:00000B12", "Xbox Wireless Controller"), "USB is the kernel's (xpad)");
        check(!RootRumble.matches("0005:0000045E:000007A5", "Microsoft Bluetooth Mouse"), "not a Microsoft mouse");
        check(RootRumble.matches("0005:0000045E:00000B99", "Xbox Adaptive Controller"), "unknown Xbox by name");
        check(!RootRumble.matches("0005:0000054C:000009CC", "Wireless Controller"), "not a DualShock");
        check(!RootRumble.matches(null, null) && !RootRumble.matches("garbage", "x"), "bad ids");
    }

    /** The on-screen gamepad (0.7.16): its layout, and what a finger means. */
    private static void virtualPadChecks() {
        VirtualLayout d = VirtualLayout.defaults(640, 360);
        check(d.items.length == VirtualLayout.IDS.length, "every control in the layout");
        check(d.get("a").shown && d.get("ls").shown && !d.get("capture").shown && !d.get("l3").shown, "default controls");
        check(d.get("a").x > 0.5f && d.get("ls").x < 0.5f && d.get("zl").y < 0.3f, "default places");
        for (VirtualLayout.Item i : d.items)
            check(i.x >= 0 && i.x <= 1 && i.y >= 0 && i.y <= 1 && i.scale == 1f, "default " + i.id + " on screen");
        // the corners stay free: the show / hide button bottom left, the edit button top right (and the fps top left)
        for (float[] size : new float[][]{ {640, 360}, {568, 320}, {800, 360}, {960, 600} }) {
            VirtualLayout v = VirtualLayout.defaults(size[0], size[1]);
            for (VirtualLayout.Item i : v.items) {
                if (!i.shown) continue;
                float cx = i.x * size[0], cy = i.y * size[1];
                float l = cx - VirtualLayout.widthDp(i.id) / 2, r = cx + VirtualLayout.widthDp(i.id) / 2;
                float t = cy - VirtualLayout.heightDp(i.id) / 2, b = cy + VirtualLayout.heightDp(i.id) / 2;
                boolean topRight = r > size[0] - 52 && t < 52, bottomLeft = l < 52 && b > size[1] - 52, topLeft = l < 52 && t < 52;
                check(!topRight && !bottomLeft && !topLeft, "corner free of " + i.id + " at " + (int)size[0] + "x" + (int)size[1]);
            }
        }
        VirtualLayout e = d.copy();
        e.get("a").x = 0.25f; e.get("a").scale = 1.3f; e.get("plus").shown = false; e.get("capture").shown = true;
        check(d.get("a").x != 0.25f, "copy is a copy");
        VirtualLayout r = VirtualLayout.parse(e.serialize(), 640, 360);
        check(Math.abs(r.get("a").x - 0.25f) < 0.001f && Math.abs(r.get("a").scale - 1.3f) < 0.001f, "layout round trip");
        check(!r.get("plus").shown && r.get("capture").shown && r.hidden().contains("plus") && !r.hidden().contains("capture"), "shown / hidden round trip");
        check(VirtualLayout.parse("", 640, 360).serialize().equals(d.serialize()), "empty -> defaults");
        check(VirtualLayout.parse("garbage;;a:x", 640, 360).serialize().equals(d.serialize()), "garbage -> defaults");
        VirtualLayout odd = VirtualLayout.parse("v1;a:7,-3,9,1", 640, 360);
        check(odd.get("a").x == 1f && odd.get("a").y == 0f && odd.get("a").scale == VirtualLayout.MAX_SCALE, "out of range clamped");
        check(VirtualLayout.button("a") == Pad.A && VirtualLayout.button("zr") == Pad.ZR && VirtualLayout.button("home") == Pad.HOME, "control -> button");
        int[] s = VirtualLayout.stick(0, -50, 50);
        check(s[0] == 0 && s[1] == Pad.STICK_MAX, "stick up is +y");
        s = VirtualLayout.stick(200, 0, 50);
        check(s[0] == Pad.STICK_MAX && s[1] == 0, "stick clamps at the rim");
        s = VirtualLayout.stick(100, 100, 50);
        check(Math.abs(s[0] - Math.round(Pad.STICK_MAX * Math.sqrt(0.5))) <= 1 && s[1] < 0, "stick diagonal stays round");
        check(VirtualLayout.dpad(2, 2, 50) == 0, "D-pad: middle is nothing");
        check(VirtualLayout.dpad(0, -40, 50) == 1 << Pad.UP && VirtualLayout.dpad(40, 0, 50) == 1 << Pad.RIGHT, "D-pad up / right");
        check(VirtualLayout.dpad(-30, 30, 50) == (1 << Pad.LEFT | 1 << Pad.DOWN), "D-pad diagonal");
        Pad.State phys = new Pad.State(true, 1 << Pad.A, 1000, 0, 0, -20000);
        Pad.State virt = new Pad.State(true, 1 << Pad.B, -9000, 9000, 100, 0);
        Pad.State m = Pad.merge(phys, virt);
        check(m.connected && m.buttons == (1 << Pad.A | 1 << Pad.B), "merge: buttons together");
        check(m.lx == -9000 && m.ly == 9000 && m.rx == 0 && m.ry == -20000, "merge: the stick pushed further");
        check(Pad.merge(Pad.State.NONE, virt) == virt && Pad.merge(phys, Pad.State.NONE) == phys, "merge with nothing");
        check(!Pad.merge(Pad.State.NONE, Pad.State.NONE).connected && Pad.merge(Pad.State.NONE, Pad.State.IDLE).connected, "merge connected");
    }

    private static Touch.Frame one(int id, int x) {
        Touch.Frame f = new Touch.Frame(1); f.id[0] = id; f.x[0] = x; f.y[0] = 100; return f;
    }
}
