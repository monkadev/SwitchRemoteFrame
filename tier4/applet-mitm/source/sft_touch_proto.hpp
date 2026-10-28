/*
 * v0.7.16: remote touch - the viewer's touch message ("SFTT") and the byte
 * stream it travels in. Pure C++ (no libnx), so test/touch_test.cpp checks it
 * on the PC.
 *
 * The viewer sends, on the stream's own connection (TCP 9950, or the USB OUT
 * endpoint 0x01 - one bulk transfer per message), the whole touch screen as
 * it is now, every time it changes. 48 bytes, little-endian:
 *
 *   0  "SFTT"
 *   4  u8  version (1)
 *   5  u8  count - fingers down, 0..5 (0: every finger lifted)
 *   6  u16 sequence (wraps; for the log only)
 *   8  5 x { u16 x; u16 y; u8 id; u8 reserved[3]; }
 *        x 0..1279, y 0..719: the console's touch screen, whatever the
 *        stream's resolution; id 0..15, one per finger for as long as it is
 *        down. Unused slots are zero.
 *
 * While a finger is down and nothing moves, the viewer repeats the message
 * every 500 ms; the console lifts every finger when it has heard nothing for
 * HoldTimeoutMs (a viewer that froze must not leave a finger on the screen).
 *
 * The same connection carries the 4-byte requests "SFTK" (a keyframe) and,
 * over USB, "SFTQ" (goodbye), "SFTV" (v0.7.16: rumble, please -
 * sft_rumble_proto.hpp), and the gamepad's 32-byte "SFTG"
 * (sft_pad_proto.hpp). Stream reassembles TCP's arbitrary segments into
 * those messages.
 */
#pragma once
#include <cstdint>
#include <cstddef>
#include <cstring>
#include "sft_pad_proto.hpp"

namespace sft::touch {

    constexpr size_t   MsgSize       = 48;
    constexpr uint8_t  Version       = 1;
    constexpr uint32_t MaxFingers    = 5;
    constexpr uint32_t Width         = 1280;   /* the touch screen's own coordinates */
    constexpr uint32_t Height        = 720;
    constexpr uint32_t MaxFingerId   = 15;
    constexpr uint32_t HoldTimeoutMs = 2000;

    struct Point { uint16_t x, y; uint8_t id; };
    struct Frame {
        uint32_t count = 0;
        uint16_t seq = 0;
        Point pts[MaxFingers] = {};
    };

    inline uint16_t U16(const uint8_t *p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }

    inline bool IsTouchMagic(const uint8_t *p) { return std::memcmp(p, "SFTT", 4) == 0; }

    /* One message -> frame. False (and the frame untouched) for anything that
     * is not a valid version-1 message: a later version may only add to it
     * under a new version number. Coordinates are clamped to the screen; a
     * repeated finger id keeps its first point. */
    inline bool Parse(const uint8_t *p, size_t n, Frame *out) {
        if (n < MsgSize || !IsTouchMagic(p) || p[4] != Version || p[5] > MaxFingers) { return false; }
        Frame f;
        f.seq = U16(p + 6);
        uint32_t seen = 0;
        for (uint32_t i = 0; i < p[5]; ++i) {
            const uint8_t *q = p + 8 + i * 8;
            const uint8_t id = q[4];
            if (id > MaxFingerId) { return false; }
            if (seen & (1u << id)) { continue; }
            seen |= 1u << id;
            uint16_t x = U16(q), y = U16(q + 2);
            if (x >= Width)  { x = Width - 1; }
            if (y >= Height) { y = Height - 1; }
            f.pts[f.count++] = { x, y, id };
        }
        *out = f;
        return true;
    }

    /* The viewer -> console byte stream, reassembled. Feed it what recv
     * returned; it calls on_key for "SFTK", on_touch for every valid "SFTT",
     * on_pad for every valid "SFTG" and skips other "SFT?" requests and
     * garbage (resyncing on the next "SFT"). Holds at most one partial message
     * between calls. */
    class Stream {
        public:
            template<typename OnKey, typename OnTouch, typename OnPad>
            void Feed(const uint8_t *data, size_t n, OnKey &&on_key, OnTouch &&on_touch, OnPad &&on_pad) {
                while (n > 0) {
                    const size_t take = (n < sizeof(m_buf) - m_len) ? n : sizeof(m_buf) - m_len;
                    std::memcpy(m_buf + m_len, data, take);
                    m_len += take; data += take; n -= take;
                    Drain(on_key, on_touch, on_pad);
                }
            }
            /* without a gamepad handler: "SFTG" is parsed and dropped */
            template<typename OnKey, typename OnTouch>
            void Feed(const uint8_t *data, size_t n, OnKey &&on_key, OnTouch &&on_touch) {
                Feed(data, n, on_key, on_touch, [](const ::sft::pad::State &) {});
            }
            void Reset() { m_len = 0; }
            size_t Pending() const { return m_len; }
            uint32_t Bad() const { return m_bad; }
            /* how many "SFTV" (the viewer wants rumble) since the last call */
            uint32_t TakeAsks() { const uint32_t n = m_asks; m_asks = 0; return n; }
        private:
            template<typename OnKey, typename OnTouch, typename OnPad>
            void Drain(OnKey &&on_key, OnTouch &&on_touch, OnPad &&on_pad) {
                size_t i = 0;
                while (m_len - i >= 4) {
                    const uint8_t *p = m_buf + i;
                    if (std::memcmp(p, "SFT", 3) != 0) { ++i; continue; }   /* resync */
                    if (p[3] == 'T') {
                        if (m_len - i < MsgSize) { break; }                   /* wait for the rest */
                        Frame f;
                        if (Parse(p, MsgSize, &f)) { on_touch(f); } else { ++m_bad; }
                        i += MsgSize;
                        continue;
                    }
                    if (p[3] == 'G') {
                        if (m_len - i < ::sft::pad::MsgSize) { break; }
                        ::sft::pad::State st;
                        if (::sft::pad::Parse(p, ::sft::pad::MsgSize, &st)) { on_pad(st); } else { ++m_bad; }
                        i += ::sft::pad::MsgSize;
                        continue;
                    }
                    if (p[3] == 'K') { on_key(); }
                    if (p[3] == 'V') { ++m_asks; }                             /* v0.7.16: rumble wanted (sft_rumble_proto.hpp) */
                    i += 4;                                                    /* SFTK, SFTQ, ... */
                }
                /* fewer than 4 bytes left: keep the shortest tail that can
                 * still begin "SFT" ("xS" keeps "S", "SS" keeps "S") */
                while (i < m_len && m_len - i < 4 && std::memcmp(m_buf + i, "SFT", m_len - i) != 0) { ++i; }
                if (i > 0) { std::memmove(m_buf, m_buf + i, m_len - i); m_len -= i; }
            }
            static_assert(::sft::pad::MsgSize <= MsgSize, "the buffer holds the longest message");
            uint8_t m_buf[2 * MsgSize] = {};
            size_t m_len = 0;
            uint32_t m_bad = 0;
            uint32_t m_asks = 0;
    };

}
