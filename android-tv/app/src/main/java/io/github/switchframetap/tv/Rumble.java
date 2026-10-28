/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

/** 0.7.16: the game's rumble of the remote gamepad, sent back by the console
 *  (tier4/applet-mitm/source/sft_rumble_proto.hpp). Pure Java, so
 *  ProtocolTest checks it.
 *
 *  The viewer asks with "SFTV" every {@link #ASK_EVERY_NS} while it wants
 *  rumble (consoles before 0.7.16 skip it); the console then puts packets
 *  flagged {@link SftProtocol#RUMBLE} in the stream - an 8-byte body: version
 *  1, reserved, u16 sequence, then the left motor's low and high band
 *  amplitudes and the right motor's (0..255 each). They come when the value
 *  changes and every 100 ms while it is not zero; with nothing for
 *  {@link #SILENCE_MS} the motors stop on their own. */
final class Rumble {
    static final byte[] REQUEST = { 'S', 'F', 'T', 'V' };
    static final long ASK_EVERY_NS = 2_000_000_000L;
    static final int BODY_SIZE = 8, MAX_BODY = 64, VERSION = 1;
    static final long SILENCE_MS = 300;

    /** One packet: the two motors' strength, 0..255 (the stronger band of each). */
    static final class Motors {
        final int left, right, seq;
        Motors(int left, int right, int seq) { this.left = left; this.right = right; this.seq = seq; }
        static final Motors OFF = new Motors(0, 0, 0);
        boolean off() { return left == 0 && right == 0; }
        int strongest() { return Math.max(left, right); }
        @Override public boolean equals(Object o) {
            return o instanceof Motors && ((Motors)o).left == left && ((Motors)o).right == right;
        }
        @Override public int hashCode() { return left * 256 + right; }
    }

    /** The body of a rumble packet; null when it is not version 1 or too short. */
    static Motors parse(byte[] body) {
        if (body == null || body.length < BODY_SIZE || (body[0] & 255) != VERSION) return null;
        int seq = (body[2] & 255) | ((body[3] & 255) << 8);
        int left = Math.max(body[4] & 255, body[5] & 255);
        int right = Math.max(body[6] & 255, body[7] & 255);
        return new Motors(left, right, seq);
    }

    /** A Switch amplitude (0..255) -> an Android vibration amplitude (0 = off,
     *  1..255). Games use mostly small amplitudes (HD rumble is subtle), and
     *  phone motors barely move below ~20%, so the square root lifts them;
     *  anything under 2% is silence. */
    static int amplitude(int value) {
        if (value < 5) return 0;
        int a = (int)Math.round(Math.sqrt(Math.min(255, value) / 255.0) * 255.0);
        return Math.max(1, Math.min(255, a));
    }

    /** A motor without amplitude control: on from 15%. */
    static boolean onOff(int value) { return value >= 38; }

    /** Is it worth telling the motor again? Changes below ~6% are not felt,
     *  but on / off always is. */
    static boolean differs(int a, int b) {
        if ((a == 0) != (b == 0)) return true;
        return Math.abs(a - b) >= 16;
    }
}
