/*
 * v0.7.16: rumble for the viewer - the game's vibration of the remote
 * gamepad (applet_mitm_pad.hpp), sent back to the Android viewer, which
 * vibrates its gamepad and / or the phone. Pure C++ (no libnx), so
 * test/touch_test.cpp checks it on the PC.
 *
 * Asking for it - viewer -> console, on the stream's connection (TCP 9950, or
 * the USB OUT endpoint 0x01, one transfer):
 *
 *   "SFTV"  (4 bytes) - "this viewer vibrates". Repeated every 2 s while the
 *           viewer wants rumble; the console stops sending it AskTimeoutMs
 *           after the last one (or when the viewer leaves). Consoles before
 *           0.7.16 skip unknown "SFT?" requests, so it is always safe to send.
 *
 * The rumble - console -> viewer, a packet in the stream (only ever to a
 * viewer that asked, so older viewers never see one):
 *
 *   SFTR v2 header (32 bytes), flags = FlagRumble (32), width = height = 0,
 *   length = BodySize, then:
 *     0  u8  version (1)
 *     1  u8  reserved (0)
 *     2  u16 sequence (wraps)
 *     4  u8  left  motor, low band amplitude   (0..255 = 0.0..1.0)
 *     5  u8  left  motor, high band amplitude
 *     6  u8  right motor, low band amplitude
 *     7  u8  right motor, high band amplitude
 *
 * Sent with the stream (before a video frame, as the audio is): when the
 * value changes, and every KeepaliveMs while it is not zero. The viewer
 * stops vibrating when it has heard nothing for 300 ms, so a stalled stream
 * never leaves it buzzing.
 */
#pragma once
#include <cstdint>
#include <cstddef>
#include <cstring>

namespace sft::rumble {

    constexpr uint16_t FlagRumble    = 32;
    constexpr size_t   BodySize      = 8;
    constexpr uint8_t  Version       = 1;
    constexpr uint32_t AskTimeoutMs  = 5000;
    constexpr uint32_t KeepaliveMs   = 100;
    /* a value nothing has refreshed for this long is taken as stopped (a game
     * that quit mid-rumble; the session's close normally does it first) */
    constexpr uint32_t StaleMs       = 3000;

    /* nn::hid's NpadStyleIndex of a Pro Controller (HidVibrationDeviceHandle
     * npad_style_index): 3 = FullKey, 4 = Handheld, 5 = JoyDual, 6/7 = one
     * Joy-Con, 8 = GameCube */
    constexpr uint32_t StyleIndexFullKey = 3;

    inline bool IsAsk(const uint8_t *p, size_t n) { return n >= 4 && std::memcmp(p, "SFTV", 4) == 0; }

    /* HidVibrationDeviceHandle: style index, player (npad id), device (0 left, 1 right) */
    struct Handle {
        uint32_t style, player, device;
    };
    inline Handle DecodeHandle(uint32_t v) {
        return Handle{ v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF };
    }

    /* an amplitude (0.0..1.0 in nn::hid; anything else clamped, NaN = 0) as a byte */
    inline uint8_t AmpByte(float a) {
        if (!(a > 0.0f)) { return 0; }
        if (a >= 1.0f) { return 255; }
        return static_cast<uint8_t>(a * 255.0f + 0.5f);
    }

    /* both motors in one word: [7:0] left low, [15:8] left high, [23:16] right low, [31:24] right high */
    inline uint32_t SetMotor(uint32_t packed, uint32_t device, float amp_low, float amp_high) {
        if (device > 1) { return packed; }
        const uint32_t shift = device * 16;
        const uint32_t motor = static_cast<uint32_t>(AmpByte(amp_low)) | (static_cast<uint32_t>(AmpByte(amp_high)) << 8);
        return (packed & ~(0xFFFFu << shift)) | (motor << shift);
    }

    inline void EncodeBody(uint8_t *out, uint32_t packed, uint16_t seq) {
        out[0] = Version;
        out[1] = 0;
        out[2] = static_cast<uint8_t>(seq);
        out[3] = static_cast<uint8_t>(seq >> 8);
        out[4] = static_cast<uint8_t>(packed);
        out[5] = static_cast<uint8_t>(packed >> 8);
        out[6] = static_cast<uint8_t>(packed >> 16);
        out[7] = static_cast<uint8_t>(packed >> 24);
    }

    /* the sender's decision, once per video frame: send now? */
    struct Sender {
        uint32_t sent = 0;
        int64_t sent_ms = -1;
        uint16_t seq = 0;

        void Reset() { sent = 0; sent_ms = -1; }

        /* value: what the viewer should feel now (0 when it does not want
         * rumble any more - one last 0 goes out if it was buzzing) */
        bool Due(uint32_t value, int64_t now_ms) const {
            if (value != sent) { return true; }
            return value != 0 && (sent_ms < 0 || now_ms - sent_ms >= static_cast<int64_t>(KeepaliveMs));
        }
        void Sent(uint32_t value, int64_t now_ms) { sent = value; sent_ms = now_ms; ++seq; }
    };

}
