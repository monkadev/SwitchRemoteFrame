/*
 * v0.7.16: remote touch - see applet_mitm_touch.hpp. The AutoPilot calls
 * follow sys-botbase (olliz0r, touch commands) and switch-touch-overlay: the
 * first sample of a finger carries HidTouchAttribute_Start, and the empty
 * state stays one HID frame before the AutoPilot is removed, so the game sees
 * the finger go up.
 */
#include "applet_mitm_touch.hpp"
#include "applet_mitm_sm.hpp"
#include "applet_mitm_log.hpp"
#include "sft_touch_proto.hpp"

namespace ams::mitm::applet {

    constinit std::atomic<bool> g_touch_enabled{true};

    namespace {

        namespace tp = ::sft::touch;

        constexpr u32 FingerDiameter = 24;
        constexpr u64 SampleNs       = UINT64_C(16666667);   /* delta_time: one 60 Hz frame */
        /* a finger that went down is held at least this long before a lift
         * (or a different set of fingers) is applied: a tap whose down and up
         * arrive together must still be one HID sample down */
        constexpr s64 MinHoldMs      = 20;
        constexpr s64 LiftHoldMs     = 34;                   /* empty state before Unset: two samples */

        /* the queue: moves of the same fingers replace the newest entry,
         * a change of fingers (down, up) is appended, so none is lost */
        constexpr u32 QueueLen = 8;
        constinit os::SdkMutex g_lock;
        constinit os::SdkConditionVariable g_cv;
        constinit tp::Frame g_queue[QueueLen] = {};
        constinit u32 g_q_head = 0, g_q_count = 0;
        constinit bool g_release_req = false;
        constinit const char *g_release_why = nullptr;

        constinit std::atomic<u32> s_msgs{0}, s_applied{0}, s_lifts{0}, s_timeouts{0}, s_fail{0}, s_merged{0};
        constinit std::atomic<bool> s_logged_first{false};

        alignas(os::ThreadStackAlignment) constinit u8 g_touch_stack[8_KB];
        constinit os::ThreadType g_touch_thread;

        u32 Mask(const tp::Frame &f) {
            u32 m = 0;
            for (u32 i = 0; i < f.count; ++i) { m |= 1u << f.pts[i].id; }
            return m;
        }

        s64 NowMs() { return static_cast<s64>(armTicksToNs(armGetSystemTick()) / 1000000); }

        class Pilot {
            public:
                /* the frame onto the touch screen; false when hid:dbg is unusable */
                bool Apply(const tp::Frame &f) {
                    if (f.count == 0) { Lift(); return true; }
                    if (!Open()) { return false; }
                    const u32 mask = Mask(f);
                    if (m_active && mask != m_mask) { HoldAtLeast(MinHoldMs); }
                    HidTouchState st[tp::MaxFingers] = {};
                    for (u32 i = 0; i < f.count; ++i) {
                        st[i].delta_time = SampleNs;
                        st[i].attributes = (m_mask & (1u << f.pts[i].id)) ? 0u : static_cast<u32>(HidTouchAttribute_Start);
                        st[i].finger_id  = f.pts[i].id;
                        st[i].x          = f.pts[i].x;
                        st[i].y          = f.pts[i].y;
                        st[i].diameter_x = FingerDiameter;
                        st[i].diameter_y = FingerDiameter;
                    }
                    const ::Result rc = ::hiddbgSetTouchScreenAutoPilotState(st, static_cast<s32>(f.count));
                    if (R_FAILED(rc)) {
                        if (s_fail++ < 4) { LogLine("touch: SetTouchScreenAutoPilotState(%u) rc=0x%x", f.count, rc); }
                        return true;
                    }
                    if (mask != m_mask) { m_changed_ms = NowMs(); }
                    m_active = true;
                    m_mask = mask;
                    ++s_applied;
                    return true;
                }

                /* every finger up, and the touch screen back to the console */
                void Lift() {
                    if (!m_active) { return; }
                    HoldAtLeast(MinHoldMs);
                    static_cast<void>(::hiddbgSetTouchScreenAutoPilotState(nullptr, 0));
                    os::SleepThread(TimeSpan::FromMilliSeconds(LiftHoldMs));
                    static_cast<void>(::hiddbgUnsetTouchScreenAutoPilotState());
                    m_active = false;
                    m_mask = 0;
                    ++s_lifts;
                }

                bool Down() const { return m_active && m_mask != 0; }

            private:
                bool Open() {
                    if (m_open) { return true; }
                    if (m_failed) { return false; }
                    ::Result rc;
                    {
                        SmUse sm;   /* only for getting the service (applet_mitm_sm.hpp) */
                        rc = ::hiddbgInitialize();
                    }
                    if (R_FAILED(rc)) {
                        m_failed = true;
                        LogLine("touch: hid:dbg unavailable rc=0x%x - remote touch stays off this boot", rc);
                        return false;
                    }
                    m_open = true;
                    LogLine("touch: hid:dbg open - the viewer's touches go to the touch screen");
                    return true;
                }

                void HoldAtLeast(s64 ms) {
                    const s64 left = m_changed_ms + ms - NowMs();
                    if (left > 0 && left <= ms) { os::SleepThread(TimeSpan::FromMilliSeconds(left)); }
                }

                bool m_open = false, m_failed = false, m_active = false;
                u32 m_mask = 0;
                s64 m_changed_ms = 0;
        };

        void TouchThread(void *) {
            Pilot pilot;
            s64 last_msg_ms = 0;
            for (;;) {
                tp::Frame f;
                bool have = false, release = false;
                const char *why = nullptr;
                {
                    std::scoped_lock lk(g_lock);
                    while (g_q_count == 0 && !g_release_req) {
                        if (pilot.Down()) {
                            /* a finger is down: wake up to check the viewer still talks */
                            if (!g_cv.TimedWait(g_lock, TimeSpan::FromMilliSeconds(250))) { break; }
                        } else {
                            g_cv.Wait(g_lock);
                        }
                    }
                    if (g_release_req) {
                        release = true; why = g_release_why;
                        g_release_req = false; g_release_why = nullptr;
                        g_q_count = 0;   /* nothing queued before the release is still wanted */
                    } else if (g_q_count > 0) {
                        f = g_queue[g_q_head];
                        g_q_head = (g_q_head + 1) % QueueLen;
                        --g_q_count;
                        have = true;
                    }
                }
                const s64 now = NowMs();
                if (release) {
                    if (pilot.Down() && why != nullptr) { LogLine("touch: fingers lifted (%s)", why); }
                    pilot.Lift();
                    continue;
                }
                if (have) {
                    last_msg_ms = now;
                    if (!g_touch_enabled.load(std::memory_order_relaxed)) { pilot.Lift(); continue; }
                    if (!pilot.Apply(f)) {
                        /* hid:dbg cannot be had: drop everything from now on */
                        g_touch_enabled.store(false, std::memory_order_relaxed);
                    }
                    continue;
                }
                if (pilot.Down() && now - last_msg_ms > static_cast<s64>(tp::HoldTimeoutMs)) {
                    ++s_timeouts;
                    LogLine("touch: no word from the viewer for %u ms with a finger down - lifted", tp::HoldTimeoutMs);
                    pilot.Lift();
                }
            }
        }

    }

    void StartTouch() {
        SFT_ABORT_UNLESS(os::CreateThread(&g_touch_thread, TouchThread, nullptr, g_touch_stack, sizeof(g_touch_stack), os::GetThreadPriority(os::GetCurrentThread())));
        os::SetThreadNamePointer(&g_touch_thread, "applet-mitm.Touch");
        os::StartThread(&g_touch_thread);
        NoteThread(&g_touch_thread);
    }

    void TouchFromViewer(const u8 *msg, size_t len) {
        tp::Frame f;
        if (tp::Parse(msg, len, &f)) { TouchFrame(f); }
    }

    void TouchFrame(const tp::Frame &f) {
        ++s_msgs;
        if (!g_touch_enabled.load(std::memory_order_relaxed)) {
            if (!s_logged_first.exchange(true)) { LogLine("touch: the viewer sends touches, but remote_touch = 0 - ignored"); }
            return;
        }
        if (!s_logged_first.exchange(true)) { LogLine("touch: first touch from the viewer (%u finger%s)", f.count, f.count == 1 ? "" : "s"); }
        std::scoped_lock lk(g_lock);
        if (g_q_count > 0) {   /* the newest entry is still queued, not yet applied */
            tp::Frame &newest = g_queue[(g_q_head + g_q_count - 1) % QueueLen];
            if (Mask(newest) == Mask(f)) { newest = f; ++s_merged; g_cv.Signal(); return; }   /* a move: the newest position wins */
        }
        if (g_q_count == QueueLen) {
            /* full (the thread is holding a tap): the newest entry gives way */
            g_queue[(g_q_head + g_q_count - 1) % QueueLen] = f;
            ++s_merged;
        } else {
            g_queue[(g_q_head + g_q_count) % QueueLen] = f;
            ++g_q_count;
        }
        g_cv.Signal();
    }

    void TouchRelease(const char *why) {
        std::scoped_lock lk(g_lock);
        g_release_req = true;
        g_release_why = why;
        s_logged_first.store(false);   /* the next viewer's first touch is logged again */
        g_cv.Signal();
    }

    void TouchLogStats(const char *who) {
        if (s_msgs.load() == 0) { return; }
        LogLine("   %s: touch - %u messages, %u applied, %u merged, %u lifts, %u timeouts, %u failures", who,
                s_msgs.load(), s_applied.load(), s_merged.load(), s_lifts.load(), s_timeouts.load(), s_fail.load());
    }

}
