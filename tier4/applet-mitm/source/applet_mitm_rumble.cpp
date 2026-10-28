/*
 * v0.7.16: rumble for the viewer - see applet_mitm_rumble.hpp.
 */
#include "applet_mitm_rumble.hpp"
#include "applet_mitm_pad.hpp"
#include "applet_mitm_sm.hpp"
#include "applet_mitm_log.hpp"
#include "applet_mitm_featmem.hpp"

/* hid's root, the two commands we look at. The raw layouts are libnx's
 * (hid.c): 201 = { u32 handle; VibrationValue value; u32 pad; u64 aruid } +
 * pid - libstratosphere places raw arguments by ascending alignment, so
 * (u32, 16-byte float struct, 8-byte pid placeholder) lands on 0, 4 and 24.
 * The pid is taken as a plain ClientProcessId: ClientAppletResourceUserId
 * would turn the request away when an aruid is not the pid. */
#define AMS_HID_RUMBLE_MITM_INTERFACE_INFO(C, H)                                                                                                    \
    AMS_SF_METHOD_INFO(C, H, 201, Result, SendVibrationValue, (u32 handle, ::HidVibrationValue value, const sf::ClientProcessId &client_pid), (handle, value, client_pid)) \
    AMS_SF_METHOD_INFO(C, H, 206, Result, SendVibrationValues, (u64 aruid, const sf::InPointerArray<u32> &handles, const sf::InPointerArray<::HidVibrationValue> &values), (aruid, handles, values))

AMS_SF_DEFINE_MITM_INTERFACE(ams::mitm::applet, IHidRumbleMitm, AMS_HID_RUMBLE_MITM_INTERFACE_INFO, 0x2AB1E160)

namespace ams::mitm::applet {

    constinit std::atomic<bool> g_rumble_enabled{true};

    namespace {

        namespace rb = ::sft::rumble;

        s64 NowMs() { return static_cast<s64>(armTicksToNs(armGetSystemTick()) / 1000000); }

        /* our virtual Pro Controller's colours (applet_mitm_pad.cpp Attach) */
        constexpr u32 OurBody    = RGBA8_MAXALPHA(0x2C, 0x31, 0x3B);
        constexpr u32 OurButtons = RGBA8_MAXALPHA(0xEC, 0xEE, 0xF3);

        constinit std::atomic<bool> s_registered{false};
        constinit std::atomic<bool> s_pad_attached{false};
        constinit std::atomic<u32>  s_pad_generation{0};
        constinit std::atomic<bool> s_hid_ready{false};
        constinit std::atomic<s64>  s_ask_ms{-1};
        constinit std::atomic<u32>  s_viewer_generation{0};

        /* what the game wants our controller to do now */
        constinit std::atomic<u32>  s_value{0};
        constinit std::atomic<s64>  s_value_ms{0};

        constinit std::atomic<u32>  s_sessions{0}, s_calls{0}, s_ours{0}, s_sent{0}, s_asks{0};
        constinit std::atomic<bool> s_logged_first{false};

        /* the npads showing our colours, refreshed when the controller is
         * attached again and every 2 s (only the server thread uses it) */
        struct OwnerCache {
            u32 mask = 0;
            u32 generation = ~0u;
            s64 at_ms = 0;
        };
        constinit OwnerCache g_owner = {};

        u32 OurNpads() {
            const u32 gen = s_pad_generation.load(std::memory_order_relaxed);
            const s64 now = NowMs();
            if (g_owner.generation == gen && now - g_owner.at_ms < 2000) { return g_owner.mask; }
            u32 mask = 0;
            if (s_hid_ready.load(std::memory_order_acquire)) {
                for (u32 id = 0; id < 8; ++id) {
                    if ((::hidGetNpadStyleSet(static_cast<HidNpadIdType>(id)) & HidNpadStyleTag_NpadFullKey) == 0) { continue; }
                    HidNpadControllerColor c = {};
                    if (R_FAILED(::hidGetNpadControllerColorSingle(static_cast<HidNpadIdType>(id), &c))) { continue; }
                    if ((c.main & 0xFFFFFFu) == (OurBody & 0xFFFFFFu) && (c.sub & 0xFFFFFFu) == (OurButtons & 0xFFFFFFu)) { mask |= 1u << id; }
                }
            }
            if (mask == 0) { mask = 0xFFu; }   /* not found (or no shared memory): any Pro Controller */
            if (g_owner.generation != gen) {
                LogLine("rumble: our controller is npad %s%#x", mask == 0xFFu ? "(not found - any Pro Controller) " : "mask ", mask);
            }
            g_owner = { mask, gen, now };
            return mask;
        }

        bool IsOurs(u32 raw) {
            if (!s_pad_attached.load(std::memory_order_relaxed)) { return false; }
            const rb::Handle h = rb::DecodeHandle(raw);
            if (h.style != rb::StyleIndexFullKey || h.player >= 8 || h.device > 1) { return false; }
            return (OurNpads() & (1u << h.player)) != 0;
        }

        void Note(u32 raw, const ::HidVibrationValue &v) {
            const rb::Handle h = rb::DecodeHandle(raw);
            u32 cur = s_value.load(std::memory_order_relaxed);
            u32 next;
            do { next = rb::SetMotor(cur, h.device, v.amp_low, v.amp_high); }
            while (!s_value.compare_exchange_weak(cur, next, std::memory_order_relaxed));
            s_value_ms.store(NowMs(), std::memory_order_relaxed);
            ++s_ours;
            if (!s_logged_first.exchange(true)) {
                LogLine("rumble: the game vibrates our controller (device %u: low %d%% at %d Hz, high %d%% at %d Hz)",
                        h.device, static_cast<int>(v.amp_low * 100.0f), static_cast<int>(v.freq_low), static_cast<int>(v.amp_high * 100.0f), static_cast<int>(v.freq_high));
            }
        }

    }

    class HidRumbleMitm : public sf::MitmServiceImplBase {
        public:
            using MitmServiceImplBase::MitmServiceImplBase;

            /* only the running game (hbl's homebrew too is left alone), and
             * only while the remote gamepad is on. No IPC here: sm waits on
             * this answer (applet_mitm_service.hpp g_app_pid). */
            static bool ShouldMitm(const sm::MitmProcessInfo &client_info) {
                return g_pad_enabled.load(std::memory_order_relaxed) && g_rumble_enabled.load(std::memory_order_relaxed) &&
                       ncm::IsApplicationId(client_info.program_id) && !client_info.override_status.IsHbl();
            }

            ~HidRumbleMitm() {
                /* the game closed its hid (it quit): it rumbles no more */
                if (m_noted) { s_value.store(0, std::memory_order_relaxed); }
            }

        public:
            Result SendVibrationValue(u32 handle, ::HidVibrationValue value, const sf::ClientProcessId &client_pid) {
                AMS_UNUSED(client_pid);
                ++s_calls;
                if (IsOurs(handle)) { Note(handle, value); m_noted = true; }
                R_THROW(sm::mitm::ResultShouldForwardToSession());
            }

            Result SendVibrationValues(u64 aruid, const sf::InPointerArray<u32> &handles, const sf::InPointerArray<::HidVibrationValue> &values) {
                AMS_UNUSED(aruid);
                ++s_calls;
                const size_t n = std::min(handles.GetSize(), values.GetSize());
                for (size_t i = 0; i < n; ++i) {
                    if (IsOurs(handles[i])) { Note(handles[i], values[i]); m_noted = true; }
                }
                R_THROW(sm::mitm::ResultShouldForwardToSession());
            }

        private:
            bool m_noted = false;
    };
    static_assert(IsIHidRumbleMitm<HidRumbleMitm>);

    namespace {

        enum PortIndex {
            PortIndex_Hid,
            PortIndex_Count,
        };

        constexpr sm::ServiceName HidServiceName = sm::ServiceName::Encode("hid");

        /* A game's hid sessions; their sub-objects (IAppletResource, the
         * vibration device list) are forwarded, so the game holds hid's own.
         * A refused session would break the game's input, so there is room
         * for several games' worth. Domains in case a game converts (a mitm
         * domain reserves hid's object ids, which come from hid's one table
         * for every client - probed at registration, as am's). */
        struct HidServerOptions {
            static constexpr size_t PointerBufferSize   = 0x1000;
            static constexpr size_t MaxDomains          = 4;
            static constexpr size_t MaxDomainObjects    = 0x100;
            static constexpr bool CanDeferInvokeRequest = false;
            static constexpr bool CanManageMitmServers  = true;
        };
        constexpr size_t HidMaxSessions = 12;

        class HidServerManager final : public sf::hipc::ServerManager<PortIndex_Count, HidServerOptions, HidMaxSessions> {
            private:
                virtual Result OnNeedsToAccept(int port_index, Server *server) override;
        };

        constinit HidServerManager *g_hid_server = nullptr;

        Result HidServerManager::OnNeedsToAccept(int port_index, Server *server) {
            std::shared_ptr<::Service> fsrv;
            sm::MitmProcessInfo client_info;
            server->AcknowledgeMitmSession(std::addressof(fsrv), std::addressof(client_info));
            AMS_ABORT_UNLESS(port_index == PortIndex_Hid);
            const u32 n = ++s_sessions;
            if (n <= 40) { LogLine("rumble: hid of program %016llx wrapped (%u this boot)", static_cast<unsigned long long>(client_info.program_id.value), n); }
            R_RETURN(this->AcceptMitmImpl(server, sf::CreateSharedObjectEmplaced<IHidRumbleMitm, HidRumbleMitm>(decltype(fsrv)(fsrv), client_info), fsrv));
        }

        /* 16 KB as libstratosphere's own mitm threads: its dispatch is deep
         * before a handler runs (sys-con overflowed 4 KB) */
        alignas(os::ThreadStackAlignment) constinit u8 g_hid_stack[16_KB];
        constinit os::ThreadType g_hid_thread;
        void HidLoopThread(void *) { LoopProcessSafely(*g_hid_server, "hid"); }

    }

    void StartRumbleMitm() {
        if (!g_rumble_enabled.load(std::memory_order_relaxed)) { LogLine("rumble: off (remote_rumble = 0) - hid not wrapped"); return; }
        if (!g_pad_enabled.load(std::memory_order_relaxed)) { LogLine("rumble: off with the remote gamepad (remote_pad = 0) - hid not wrapped"); return; }
        /* hid's pointer buffer must fit in ours, and its domain ids in our table */
        {
            os::NativeHandle h = os::InvalidNativeHandle;
            if (const Result rc = sm::GetServiceHandle(std::addressof(h), HidServiceName); R_FAILED(rc)) {
                LogLine("rumble: hid not reachable rc=0x%x - no rumble for the viewer", rc.GetValue());
                return;
            }
            ::Service s = {};
            serviceCreate(std::addressof(s), h);
            const u16 pbs = s.pointer_buffer_size;
            const Result crc = serviceConvertToDomain(std::addressof(s));
            const u32 probe_id = R_SUCCEEDED(crc) ? s.object_id : 0;
            serviceClose(std::addressof(s));
            LogLine("rumble: hid pointer buffer %#x (ours %#zx); a new domain gets object id %u (convert rc=0x%x, our table %zu)",
                    pbs, HidServerOptions::PointerBufferSize, probe_id, crc.GetValue(), HidServerOptions::MaxDomainObjects);
            if (pbs > HidServerOptions::PointerBufferSize) { LogLine("rumble: hid's pointer buffer is larger than ours - hid not wrapped, no rumble"); return; }
            if (R_SUCCEEDED(crc) && probe_id > HidServerOptions::MaxDomainObjects / 2) {
                LogLine("rumble: hid's object ids do not fit our domain table - hid not wrapped, no rumble");
                return;
            }
        }
        static_assert(alignof(HidServerManager) <= os::MemoryPageSize);
        void *mem = FeatureMemory(sizeof(HidServerManager), "rumble: the hid server");
        if (mem == nullptr) { LogLine("rumble: no memory for the hid server - no rumble"); return; }
        g_hid_server = std::construct_at(static_cast<HidServerManager *>(mem));
        /* never abort here: sm says AlreadyRegistered when another program
         * (sys-con's mitm mode) holds hid - then it is simply not ours */
        if (const Result rc = g_hid_server->RegisterMitmServer<HidRumbleMitm>(PortIndex_Hid, HidServiceName); R_FAILED(rc)) {
            LogLine("rumble: hid mitm not registered rc=0x%x (another program wraps hid?) - no rumble", rc.GetValue());
            return;
        }
        if (R_FAILED(os::CreateThread(std::addressof(g_hid_thread), HidLoopThread, nullptr, g_hid_stack, sizeof(g_hid_stack), os::GetThreadPriority(os::GetCurrentThread())))) {
            /* registered without a thread would hang every game's hid: cannot happen with a static stack, but never leave it so */
            SFT_ABORT_UNLESS(false);
        }
        os::SetThreadNamePointer(std::addressof(g_hid_thread), "applet-mitm.Hid");
        os::StartThread(std::addressof(g_hid_thread));
        NoteThread(std::addressof(g_hid_thread));
        s_registered.store(true);
        LogLine("rumble: the games' hid wrapped (%zu bytes) - their vibration of the remote gamepad goes to the viewer", sizeof(HidServerManager));
    }

    void RumbleViewerAsks() {
        ++s_asks;
        s_ask_ms.store(NowMs(), std::memory_order_relaxed);
    }

    void RumbleViewerGone() {
        s_ask_ms.store(-1, std::memory_order_relaxed);
        ++s_viewer_generation;
    }

    void RumblePadAttached() {
        /* hid's shared memory, read-only (as the screenshot buttons read it:
         * applet_mitm_shot.cpp) - only to find which npad shows our colours */
        if (s_registered.load() && !s_hid_ready.load()) {
            ::Result rc;
            {
                SmUse sm;
                rc = ::hidInitialize();
            }
            if (R_SUCCEEDED(rc)) { s_hid_ready.store(true, std::memory_order_release); }
            else { LogLine("rumble: hid shared memory rc=0x%x - any Pro Controller's rumble counts", rc); }
        }
        ++s_pad_generation;
        s_pad_attached.store(true, std::memory_order_relaxed);
    }

    void RumblePadDetached() {
        s_pad_attached.store(false, std::memory_order_relaxed);
        s_value.store(0, std::memory_order_relaxed);
    }

    bool RumbleTake(u8 *body) {
        static rb::Sender sender;
        static u32 viewer_gen = 0;
        if (!s_registered.load(std::memory_order_relaxed)) { return false; }
        if (const u32 g = s_viewer_generation.load(std::memory_order_relaxed); g != viewer_gen) { viewer_gen = g; sender.Reset(); }
        const s64 now = NowMs();
        const s64 asked = s_ask_ms.load(std::memory_order_relaxed);
        const bool wants = asked >= 0 && now - asked < static_cast<s64>(rb::AskTimeoutMs) &&
                           s_pad_attached.load(std::memory_order_relaxed) && g_pad_enabled.load(std::memory_order_relaxed);
        u32 value = wants ? s_value.load(std::memory_order_relaxed) : 0;
        if (value != 0 && now - s_value_ms.load(std::memory_order_relaxed) > static_cast<s64>(rb::StaleMs)) { value = 0; }
        /* a viewer that stopped asking (or left) gets one last 0 only if it is
         * still the same connection: RumbleViewerGone reset the sender */
        if (asked < 0 && sender.sent == 0) { return false; }
        if (!sender.Due(value, now)) { return false; }
        rb::EncodeBody(body, value, sender.seq);
        sender.Sent(value, now);
        ++s_sent;
        return true;
    }

    void RumbleLogStats(const char *who) {
        if (s_calls.load() == 0 && s_asks.load() == 0) { return; }
        LogLine("   %s: rumble - %u hid sessions, %u vibration calls, %u for our controller, %u packets to the viewer, %u asks",
                who, s_sessions.load(), s_calls.load(), s_ours.load(), s_sent.load(), s_asks.load());
    }

}
