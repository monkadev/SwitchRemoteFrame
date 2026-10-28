/*
 * Host test for sft_touch_proto.hpp, sft_pad_proto.hpp and (v0.7.16)
 * sft_rumble_proto.hpp - the viewer's
 * touch ("SFTT") and gamepad ("SFTG") messages, and the reassembly of the
 * network stream they share with "SFTK".
 *
 * With a file argument (the Android ProtocolTest's SFTG dump), that message
 * must parse to the state the Java side encoded.
 *
 *   g++ -std=c++20 -Wall -Wextra -I../source touch_test.cpp -o touch_test && ./touch_test
 */
#include "sft_touch_proto.hpp"
#include "sft_rumble_proto.hpp"
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>

namespace tp = sft::touch;
namespace gp = sft::pad;

static int g_fail = 0;
#define EXPECT(c) do { if (!(c)) { std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); ++g_fail; } } while (0)

struct P { unsigned x, y, id; };

static std::vector<uint8_t> Msg(std::initializer_list<P> pts, uint16_t seq = 7, uint8_t ver = tp::Version, int count = -1) {
    std::vector<uint8_t> m(tp::MsgSize, 0);
    m[0] = 'S'; m[1] = 'F'; m[2] = 'T'; m[3] = 'T';
    m[4] = ver;
    m[5] = static_cast<uint8_t>(count >= 0 ? count : static_cast<int>(pts.size()));
    m[6] = seq & 255; m[7] = seq >> 8;
    size_t i = 0;
    for (const P &p : pts) {
        uint8_t *q = m.data() + 8 + i * 8;
        q[0] = p.x & 255; q[1] = (p.x >> 8) & 255; q[2] = p.y & 255; q[3] = (p.y >> 8) & 255; q[4] = static_cast<uint8_t>(p.id);
        ++i;
    }
    return m;
}

static std::vector<uint8_t> Pad(uint32_t buttons, int lx, int ly, int rx, int ry, uint16_t seq = 9, uint8_t flags = gp::FlagConnected, uint8_t ver = gp::Version) {
    std::vector<uint8_t> m(gp::MsgSize, 0);
    m[0] = 'S'; m[1] = 'F'; m[2] = 'T'; m[3] = 'G';
    m[4] = ver; m[5] = flags;
    m[6] = seq & 255; m[7] = seq >> 8;
    for (int i = 0; i < 4; ++i) { m[8 + i] = (buttons >> (8 * i)) & 255; }
    const int v[4] = { lx, ly, rx, ry };
    for (int i = 0; i < 4; ++i) { const uint16_t u = static_cast<uint16_t>(static_cast<int16_t>(v[i])); m[12 + 2 * i] = u & 255; m[13 + 2 * i] = u >> 8; }
    return m;
}

static void PadChecks() {
    gp::State s;
    auto a = Pad(1u << 0 | 1u << 13 | 1u << 18, 32767, -32767, -100, 200);
    EXPECT(gp::Parse(a.data(), a.size(), &s) && s.connected && s.seq == 9 && s.buttons == (1u | 1u << 13 | 1u << 18)
           && s.lx == 32767 && s.ly == -32767 && s.rx == -100 && s.ry == 200 && !s.Neutral());
    /* unknown buttons cleared, -32768 clamped */
    auto b = Pad(0xFFFFFFFFu, -32768, 0, 0, -32768);
    EXPECT(gp::Parse(b.data(), b.size(), &s) && s.buttons == gp::ButtonMask && s.lx == -32767 && s.ry == -32767);
    /* no gamepad: no input, whatever the bytes say */
    auto gone = Pad(0xFFu, 5, 5, 5, 5, 1, 0);
    EXPECT(gp::Parse(gone.data(), gone.size(), &s) && !s.connected && s.Neutral());
    auto idle = Pad(0, 0, 0, 0, 0);
    EXPECT(gp::Parse(idle.data(), idle.size(), &s) && s.connected && s.Neutral());
    /* refused: short, version 2, unknown flags, wrong magic */
    EXPECT(!gp::Parse(a.data(), gp::MsgSize - 1, &s));
    auto v2 = Pad(0, 0, 0, 0, 0, 1, gp::FlagConnected, 2);
    EXPECT(!gp::Parse(v2.data(), v2.size(), &s));
    auto fl = Pad(0, 0, 0, 0, 0, 1, 0x03);
    EXPECT(!gp::Parse(fl.data(), fl.size(), &s));
    auto t = a; t[3] = 'T';
    EXPECT(!gp::Parse(t.data(), t.size(), &s));

    /* the stream: gamepad messages between touches and requests, in any chunks */
    std::vector<uint8_t> st;
    auto add = [&](const std::vector<uint8_t> &v) { st.insert(st.end(), v.begin(), v.end()); };
    add(Pad(1, 0, 0, 0, 0, 1));
    add(Msg({{5, 6, 0}}, 1));
    add({'S', 'F', 'T', 'K'});
    add({'S', 'F', 'T', 'V'});                 /* rumble, please: counted, nothing else */
    add(Pad(2, 0, 0, 0, 0, 2));
    add(v2);                                   /* skipped whole, counted bad */
    add({'S', 'F'});                           /* a false start */
    add(Pad(0, 0, 0, 0, 0, 3, 0));
    add(Msg({}, 2));
    for (size_t chunk : { size_t(1), size_t(2), size_t(31), size_t(32), size_t(33), size_t(96), st.size() }) {
        tp::Stream rx;
        int keys = 0;
        std::vector<uint16_t> touches, pads;
        for (size_t i = 0; i < st.size(); i += chunk) {
            const size_t n = std::min(chunk, st.size() - i);
            rx.Feed(st.data() + i, n, [&] { ++keys; }, [&](const tp::Frame &fr) { touches.push_back(fr.seq); },
                    [&](const gp::State &ps) { pads.push_back(ps.seq); });
        }
        const uint32_t asks = rx.TakeAsks();
        if (keys != 1 || asks != 1 || rx.TakeAsks() != 0 || touches != std::vector<uint16_t>{1, 2} || pads != std::vector<uint16_t>{1, 2, 3} || rx.Pending() != 0 || rx.Bad() != 1) {
            std::printf("FAIL pad stream in chunks of %zu: %d keys, %zu touches, %zu pads, %zu pending, %u bad\n", chunk, keys, touches.size(), pads.size(), rx.Pending(), rx.Bad());
            ++g_fail;
        }
    }
    /* the two-handler Feed (no gamepad wanted) still steps over SFTG whole */
    {
        tp::Stream rx;
        int touches = 0;
        rx.Feed(st.data(), st.size(), [] {}, [&](const tp::Frame &) { ++touches; });
        EXPECT(touches == 2 && rx.Pending() == 0);
    }
}

/* the Android side's encoder (ProtocolTest dumps it): buttons A + ZR + Home,
 * left stick full right, right stick full down, sequence 0x0102 */
static void JavaPadCheck(const char *path) {
    std::ifstream in(path, std::ios::binary);
    std::vector<uint8_t> m((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    gp::State s;
    EXPECT(m.size() == gp::MsgSize && gp::Parse(m.data(), m.size(), &s) && s.connected && s.seq == 0x0102
           && s.buttons == (1u << 0 | 1u << 9 | 1u << 18) && s.lx == 32767 && s.ly == 0 && s.rx == 0 && s.ry == -32767);
}

/* v0.7.16: the rumble the console sends back (sft_rumble_proto.hpp); the
 * same bytes are checked by the Android ProtocolTest's parser */
static void RumbleChecks() {
    namespace rb = sft::rumble;
    EXPECT(rb::IsAsk(reinterpret_cast<const uint8_t *>("SFTV"), 4) && !rb::IsAsk(reinterpret_cast<const uint8_t *>("SFTK"), 4));
    /* a Pro Controller on player 2, right motor: style 3, player 1, device 1 */
    const rb::Handle h = rb::DecodeHandle(0x00010103u);
    EXPECT(h.style == rb::StyleIndexFullKey && h.player == 1 && h.device == 1);
    EXPECT(rb::AmpByte(0.0f) == 0 && rb::AmpByte(1.0f) == 255 && rb::AmpByte(2.0f) == 255 && rb::AmpByte(-1.0f) == 0
           && rb::AmpByte(0.5f) == 128 && rb::AmpByte(__builtin_nanf("")) == 0);
    uint32_t v = 0;
    v = rb::SetMotor(v, 0, 0.0f, 0.5f);        /* left: low 0, high 128 */
    v = rb::SetMotor(v, 1, 1.0f, 0.25f);       /* right: low 255, high 64 */
    v = rb::SetMotor(v, 2, 1.0f, 1.0f);        /* no third motor: unchanged */
    EXPECT(v == 0x40FF8000u);
    v = rb::SetMotor(v, 0, 0.0f, 0.0f);        /* the left stops, the right goes on */
    EXPECT(v == 0x40FF0000u);
    uint8_t body[rb::BodySize];
    rb::EncodeBody(body, 0x40FF8000u, 0x0102);
    const uint8_t want[rb::BodySize] = { 1, 0, 0x02, 0x01, 0x00, 0x80, 0xFF, 0x40 };
    EXPECT(std::memcmp(body, want, sizeof(want)) == 0);
    /* when it goes out: a change at once, a steady buzz every 100 ms, silence once */
    rb::Sender s;
    EXPECT(!s.Due(0, 0));
    EXPECT(s.Due(5, 0)); s.Sent(5, 0);
    EXPECT(!s.Due(5, 50) && s.Due(5, 100) && s.Due(6, 10));
    EXPECT(s.Due(0, 20)); s.Sent(0, 20);
    EXPECT(!s.Due(0, 5000) && s.seq == 2);
    s.Reset();
    EXPECT(!s.Due(0, 0) && s.Due(1, 0));
}

int main(int argc, char **argv) {
    PadChecks();
    RumbleChecks();
    if (argc > 1) { JavaPadCheck(argv[1]); }
    tp::Frame f;
    /* one finger, two fingers, none */
    auto one = Msg({{640, 360, 0}});
    EXPECT(tp::Parse(one.data(), one.size(), &f) && f.count == 1 && f.pts[0].x == 640 && f.pts[0].y == 360 && f.pts[0].id == 0 && f.seq == 7);
    auto two = Msg({{10, 20, 3}, {1279, 719, 4}});
    EXPECT(tp::Parse(two.data(), two.size(), &f) && f.count == 2 && f.pts[1].x == 1279 && f.pts[1].y == 719 && f.pts[1].id == 4);
    auto none = Msg({});
    EXPECT(tp::Parse(none.data(), none.size(), &f) && f.count == 0);
    /* clamped to the screen */
    auto big = Msg({{5000, 900, 1}});
    EXPECT(tp::Parse(big.data(), big.size(), &f) && f.pts[0].x == 1279 && f.pts[0].y == 719);
    /* refused: short, wrong magic, version, too many fingers, a bad id */
    EXPECT(!tp::Parse(one.data(), tp::MsgSize - 1, &f));
    auto bad = one; bad[3] = 'K';
    EXPECT(!tp::Parse(bad.data(), bad.size(), &f));
    auto v2 = Msg({{1, 1, 0}}, 1, 2);
    EXPECT(!tp::Parse(v2.data(), v2.size(), &f));
    auto six = Msg({{1, 1, 0}}, 1, tp::Version, 6);
    EXPECT(!tp::Parse(six.data(), six.size(), &f));
    auto id16 = Msg({{1, 1, 16}});
    EXPECT(!tp::Parse(id16.data(), id16.size(), &f));
    /* a repeated id keeps the first point */
    auto dup = Msg({{1, 2, 5}, {3, 4, 5}});
    EXPECT(tp::Parse(dup.data(), dup.size(), &f) && f.count == 1 && f.pts[0].x == 1);

    /* the stream: SFTK, touches, garbage, split at every byte */
    std::vector<uint8_t> s;
    auto add = [&](const std::vector<uint8_t> &v) { s.insert(s.end(), v.begin(), v.end()); };
    add({'S', 'F', 'T', 'K'});
    add(Msg({{100, 200, 0}}, 1));
    add({'x', 'S', 'F', 'y', 'S'});            /* garbage, with a false start */
    add(Msg({{101, 201, 0}}, 2));
    add({'S', 'F', 'T', 'Q'});                 /* another request: skipped */
    add(v2);                                   /* a message from the future: skipped whole */
    add(Msg({}, 3));
    add({'S', 'F', 'T', 'K'});
    for (size_t chunk : { size_t(1), size_t(3), size_t(47), size_t(48), size_t(49), size_t(256), s.size() }) {
        tp::Stream st;
        int keys = 0;
        std::vector<uint16_t> seqs;
        for (size_t i = 0; i < s.size(); i += chunk) {
            const size_t n = std::min(chunk, s.size() - i);
            st.Feed(s.data() + i, n, [&] { ++keys; }, [&](const tp::Frame &fr) { seqs.push_back(fr.seq); });
        }
        if (keys != 2 || seqs != std::vector<uint16_t>{1, 2, 3} || st.Pending() != 0 || st.Bad() != 1) {
            std::printf("FAIL stream in chunks of %zu: %d keys, %zu touches, %zu pending, %u bad\n", chunk, keys, seqs.size(), st.Pending(), st.Bad());
            ++g_fail;
        }
    }
    /* a partial message waits for the rest */
    {
        tp::Stream st;
        int touches = 0;
        st.Feed(one.data(), 20, [] {}, [&](const tp::Frame &) { ++touches; });
        EXPECT(touches == 0 && st.Pending() == 20);
        st.Feed(one.data() + 20, one.size() - 20, [] {}, [&](const tp::Frame &) { ++touches; });
        EXPECT(touches == 1 && st.Pending() == 0);
    }
    if (g_fail == 0) { std::printf("touch_test: all passed (touch, gamepad, rumble%s)\n", argc > 1 ? ", the Java encoder" : ""); }
    return g_fail ? 1 : 0;
}
