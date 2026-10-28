/*
 * v0.7.16: remote gamepad - the viewer's controller message ("SFTG"). Pure
 * C++ (no libnx), so test/touch_test.cpp checks it on the PC.
 *
 * The Android viewer reads the gamepad connected to the phone / TV and sends
 * the whole controller, as the Switch sees it, on the stream's own connection
 * (TCP 9950, or the USB OUT endpoint 0x01 - one bulk transfer per message)
 * every time it changes. 32 bytes, little-endian:
 *
 *   0  "SFTG"
 *   4  u8  version (1)
 *   5  u8  flags - bit0: a gamepad is there (0: take the virtual controller
 *          away again); other bits 0
 *   6  u16 sequence (wraps; for the log only)
 *   8  u32 buttons - the Switch's own bits (HidNpadButton): A 0, B 1, X 2,
 *          Y 3, StickL 4, StickR 5, L 6, R 7, ZL 8, ZR 9, Plus 10, Minus 11,
 *          Left 12, Up 13, Right 14, Down 15; Home 18, Capture 19
 *          (HiddbgNpadButton). Anything else is cleared.
 *  12  s16 left stick x, 14 s16 left stick y,
 *  16  s16 right stick x, 18 s16 right stick y - -32767..32767, y up
 *  20  12 bytes reserved (0)
 *
 * While anything is held (a button, a stick off centre) the viewer repeats
 * the message every 250 ms; the console lets go of everything when it has
 * heard nothing for HoldTimeoutMs, so a viewer that froze cannot leave a
 * button held. The virtual controller itself stays until flags bit0 is 0 or
 * the viewer disconnects.
 */
#pragma once
#include <cstdint>
#include <cstddef>
#include <cstring>

namespace sft::pad {

    constexpr size_t   MsgSize       = 32;
    constexpr uint8_t  Version       = 1;
    constexpr uint8_t  FlagConnected = 0x01;
    constexpr uint32_t HoldTimeoutMs = 1000;
    constexpr int32_t  StickMax      = 32767;
    /* A..Down (bits 0-15), Home (18), Capture (19) */
    constexpr uint32_t ButtonMask    = 0x0000FFFFu | (1u << 18) | (1u << 19);

    struct State {
        bool connected = false;
        uint16_t seq = 0;
        uint32_t buttons = 0;
        int32_t lx = 0, ly = 0, rx = 0, ry = 0;

        /* nothing held, both sticks centred */
        bool Neutral() const { return buttons == 0 && lx == 0 && ly == 0 && rx == 0 && ry == 0; }
    };

    inline bool IsPadMagic(const uint8_t *p) { return std::memcmp(p, "SFTG", 4) == 0; }

    inline int32_t S16(const uint8_t *p) { return static_cast<int16_t>(static_cast<uint16_t>(p[0] | (p[1] << 8))); }

    inline int32_t ClampStick(int32_t v) { return v < -StickMax ? -StickMax : (v > StickMax ? StickMax : v); }

    /* One message -> state. False (and the state untouched) for anything that
     * is not a valid version-1 message. Unknown buttons are cleared and the
     * sticks clamped (-32768 becomes -32767). A message without a gamepad
     * carries no input. */
    inline bool Parse(const uint8_t *p, size_t n, State *out) {
        if (n < MsgSize || !IsPadMagic(p) || p[4] != Version || (p[5] & ~FlagConnected) != 0) { return false; }
        State s;
        s.connected = (p[5] & FlagConnected) != 0;
        s.seq = static_cast<uint16_t>(p[6] | (p[7] << 8));
        if (s.connected) {
            s.buttons = (static_cast<uint32_t>(p[8]) | (static_cast<uint32_t>(p[9]) << 8) |
                         (static_cast<uint32_t>(p[10]) << 16) | (static_cast<uint32_t>(p[11]) << 24)) & ButtonMask;
            s.lx = ClampStick(S16(p + 12));
            s.ly = ClampStick(S16(p + 14));
            s.rx = ClampStick(S16(p + 16));
            s.ry = ClampStick(S16(p + 18));
        }
        *out = s;
        return true;
    }

}
