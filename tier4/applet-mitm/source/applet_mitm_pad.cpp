/*
 * v0.7.16: remote gamepad - see applet_mitm_pad.hpp. The HDLS calls follow
 * sys-botbase (olliz0r, initController / detachController): a 0x1000-byte
 * work buffer, a Pro Controller (HidDeviceType_FullKey3) over "Bluetooth",
 * then hiddbgSetHdlsState for every change.
 */
#include "applet_mitm_pad.hpp"
#include "applet_mitm_sm.hpp"
#include "applet_mitm_log.hpp"
#include "sft_pad_proto.hpp"
#include "applet_mitm_rumble.hpp"

namespace ams::mitm::applet {

    constinit std::atomic<bool> g_pad_enabled{true};

    namespace {

        namespace gp = ::sft::pad;

        /* a change of buttons is held at least this long before the next
         * change is applied: a press and release that arrive together (the
         * network bunched them) must still be seen by a 30 fps game */
        constexpr s64 MinHoldMs = 34;

        /* the queue: stick moves with the same buttons replace the newest
         * entry, a change of buttons is appended, so no press is lost */
        constexpr u32 QueueLen = 8;
        constinit os::SdkMutex g_lock;
        constinit os::SdkConditionVariable g_cv;
        constinit gp::State g_queue[QueueLen] = {};
        constinit u32 g_q_head = 0, g_q_count = 0;
        constinit bool g_release_req = false;
        constinit const char *g_release_why = nullptr;

        constinit std::atomic<u32> s_msgs{0}, s_applied{0}, s_attaches{0}, s_detaches{0}, s_timeouts{0}, s_fail{0}, s_merged{0};
        constinit std::atomic<bool> s_logged_first{false};

        alignas(os::ThreadStackAlignment) constinit u8 g_pad_stack[8_KB];
        constinit os::ThreadType g_pad_thread;

        /* transfer memory for HDLS: page aligned, page sized (as sys-botbase) */
        alignas(0x1000) constinit u8 g_hdls_work[0x1000] = {};

        s64 NowMs() { return static_cast<s64>(armTicksToNs(armGetSystemTick()) / 1000000); }

        class VirtualPad {
            public:
                /* the state onto the virtual controller; false when hid:dbg is unusable */
                bool Apply(const gp::State &s) {
                    if (!s.connected) { Detach("the viewer's gamepad is gone"); return true; }
                    if (!Attach()) { return !m_failed; }
                    if (s.buttons != m_buttons) { HoldAtLeast(MinHoldMs); }
                    if (!Set(s) && Reattach()) { static_cast<void>(Set(s)); }
                    return true;
                }

                /* everything let go, the controller stays */
                void Neutral() {
                    if (!m_attached || m_neutral) { return; }
                    gp::State n; n.connected = true;
                    HoldAtLeast(MinHoldMs);
                    static_cast<void>(Set(n));
                }

                void Detach(const char *why) {
                    if (!m_attached) { return; }
                    Neutral();
                    const ::Result rc = ::hiddbgDetachHdlsVirtualDevice(m_handle);
                    if (R_FAILED(rc)) { LogLine("pad: DetachHdlsVirtualDevice rc=0x%x", rc); }
                    m_attached = false;
                    m_buttons = 0;
                    ++s_detaches;
                    RumblePadDetached();
                    if (why != nullptr) { LogLine("pad: virtual Pro Controller detached (%s)", why); }
                }

                bool Attached() const { return m_attached; }
                bool Held() const { return m_attached && !m_neutral; }

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
                        LogLine("pad: hid:dbg unavailable rc=0x%x - the remote gamepad stays off this boot", rc);
                        return false;
                    }
                    rc = ::hiddbgAttachHdlsWorkBuffer(&m_session, g_hdls_work, sizeof(g_hdls_work));
                    if (R_FAILED(rc)) {
                        m_failed = true;
                        LogLine("pad: AttachHdlsWorkBuffer rc=0x%x - the remote gamepad stays off this boot", rc);
                        return false;
                    }
                    m_open = true;
                    return true;
                }

                bool Attach() {
                    if (m_attached) { return true; }
                    if (!Open()) { return false; }
                    HiddbgHdlsDeviceInfo info = {};
                    info.deviceType         = HidDeviceType_FullKey3;          /* Pro Controller */
                    info.npadInterfaceType  = HidNpadInterfaceType_Bluetooth;
                    info.singleColorBody    = RGBA8_MAXALPHA(0x2C, 0x31, 0x3B);
                    info.singleColorButtons = RGBA8_MAXALPHA(0xEC, 0xEE, 0xF3);
                    info.colorLeftGrip      = RGBA8_MAXALPHA(0x38, 0xBD, 0xF8);
                    info.colorRightGrip     = RGBA8_MAXALPHA(0x38, 0xBD, 0xF8);
                    const ::Result rc = ::hiddbgAttachHdlsVirtualDevice(&m_handle, &info);
                    if (R_FAILED(rc)) {
                        if (s_fail++ < 4) { LogLine("pad: AttachHdlsVirtualDevice rc=0x%x", rc); }
                        return false;
                    }
                    m_attached = true;
                    m_neutral = true;
                    m_buttons = 0;
                    ++s_attaches;
                    RumblePadAttached();   /* v0.7.16: the games' vibration of it goes to the viewer */
                    LogLine("pad: virtual Pro Controller attached - the viewer's gamepad plays");
                    return true;
                }

                /* the device went away under us (hid reset it): attach a new one */
                bool Reattach() {
                    bool still = false;
                    if (R_SUCCEEDED(::hiddbgIsHdlsVirtualDeviceAttached(m_session, m_handle, &still)) && still) { return false; }
                    m_attached = false;
                    RumblePadDetached();
                    LogLine("pad: the virtual controller was gone - attaching it again");
                    return Attach();
                }

                bool Set(const gp::State &s) {
                    HiddbgHdlsState st = {};
                    st.battery_level    = 4;      /* full */
                    st.flags            = 1;      /* powered */
                    st.buttons          = s.buttons & gp::ButtonMask;
                    st.analog_stick_l.x = s.lx;
                    st.analog_stick_l.y = s.ly;
                    st.analog_stick_r.x = s.rx;
                    st.analog_stick_r.y = s.ry;
                    const ::Result rc = ::hiddbgSetHdlsState(m_handle, &st);
                    if (R_FAILED(rc)) {
                        if (s_fail++ < 4) { LogLine("pad: SetHdlsState rc=0x%x", rc); }
                        return false;
                    }
                    if (s.buttons != m_buttons) { m_changed_ms = NowMs(); }
                    m_buttons = s.buttons;
                    m_neutral = s.Neutral();
                    ++s_applied;
                    return true;
                }

                void HoldAtLeast(s64 ms) {
                    const s64 left = m_changed_ms + ms - NowMs();
                    if (left > 0 && left <= ms) { os::SleepThread(TimeSpan::FromMilliSeconds(left)); }
                }

                bool m_open = false, m_failed = false, m_attached = false, m_neutral = true;
                HiddbgHdlsSessionId m_session = {};
                HiddbgHdlsHandle m_handle = {};
                u32 m_buttons = 0;
                s64 m_changed_ms = 0;
        };

        void PadThread(void *) {
            VirtualPad pad;
            s64 last_msg_ms = 0;
            for (;;) {
                gp::State s;
                bool have = false, release = false;
                const char *why = nullptr;
                {
                    std::scoped_lock lk(g_lock);
                    while (g_q_count == 0 && !g_release_req) {
                        if (pad.Held()) {
                            /* something is held: wake up to check the viewer still talks */
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
                        s = g_queue[g_q_head];
                        g_q_head = (g_q_head + 1) % QueueLen;
                        --g_q_count;
                        have = true;
                    }
                }
                const s64 now = NowMs();
                if (release) {
                    pad.Detach(why);
                    continue;
                }
                if (have) {
                    last_msg_ms = now;
                    if (!g_pad_enabled.load(std::memory_order_relaxed)) { pad.Detach("remote_pad = 0"); continue; }
                    if (!pad.Apply(s)) {
                        /* hid:dbg cannot be had: drop everything from now on */
                        g_pad_enabled.store(false, std::memory_order_relaxed);
                    }
                    continue;
                }
                if (pad.Held() && now - last_msg_ms > static_cast<s64>(gp::HoldTimeoutMs)) {
                    ++s_timeouts;
                    LogLine("pad: no word from the viewer for %u ms with a button held - let go", gp::HoldTimeoutMs);
                    pad.Neutral();
                }
            }
        }

    }

    void StartPad() {
        SFT_ABORT_UNLESS(os::CreateThread(&g_pad_thread, PadThread, nullptr, g_pad_stack, sizeof(g_pad_stack), os::GetThreadPriority(os::GetCurrentThread())));
        os::SetThreadNamePointer(&g_pad_thread, "applet-mitm.Pad");
        os::StartThread(&g_pad_thread);
        NoteThread(&g_pad_thread);
    }

    void PadFromViewer(const u8 *msg, size_t len) {
        gp::State s;
        if (gp::Parse(msg, len, &s)) { PadInput(s); }
    }

    void PadInput(const gp::State &s) {
        ++s_msgs;
        if (!g_pad_enabled.load(std::memory_order_relaxed)) {
            if (!s_logged_first.exchange(true)) { LogLine("pad: the viewer sends a gamepad, but remote_pad = 0 - ignored"); }
            /* switched off while attached: one release so the controller goes */
            std::scoped_lock lk(g_lock);
            if (!g_release_req) { g_release_req = true; g_release_why = nullptr; g_cv.Signal(); }
            return;
        }
        if (!s_logged_first.exchange(true)) { LogLine("pad: first gamepad message from the viewer"); }
        std::scoped_lock lk(g_lock);
        if (g_q_count > 0) {   /* the newest entry is still queued, not yet applied */
            gp::State &newest = g_queue[(g_q_head + g_q_count - 1) % QueueLen];
            if (newest.connected == s.connected && newest.buttons == s.buttons) { newest = s; ++s_merged; g_cv.Signal(); return; }   /* a stick move: the newest wins */
        }
        if (g_q_count == QueueLen) {
            /* full (the thread is holding a press): the newest entry gives way */
            g_queue[(g_q_head + g_q_count - 1) % QueueLen] = s;
            ++s_merged;
        } else {
            g_queue[(g_q_head + g_q_count) % QueueLen] = s;
            ++g_q_count;
        }
        g_cv.Signal();
    }

    void PadRelease(const char *why) {
        std::scoped_lock lk(g_lock);
        g_release_req = true;
        g_release_why = why;
        s_logged_first.store(false);   /* the next viewer's first message is logged again */
        RumbleViewerGone();            /* v0.7.16: and no more rumble to it */
        g_cv.Signal();
    }

    void PadLogStats(const char *who) {
        if (s_msgs.load() == 0) { return; }
        LogLine("   %s: pad - %u messages, %u applied, %u merged, %u attaches, %u detaches, %u timeouts, %u failures", who,
                s_msgs.load(), s_applied.load(), s_merged.load(), s_attaches.load(), s_detaches.load(), s_timeouts.load(), s_fail.load());
    }

}
