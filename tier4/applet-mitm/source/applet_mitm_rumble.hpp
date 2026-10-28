/*
 * v0.7.16: rumble for the viewer (sft_rumble_proto.hpp).
 *
 * hid:dbg's virtual controllers (the HDLS device applet_mitm_pad.cpp plays
 * the viewer's gamepad on) never report the vibration a game sends them -
 * sys-con's "hdls" mode has no rumble for the same reason. The only place the
 * values can be seen is on their way into hid, so this wraps the running
 * application's "hid" sessions (an Atmosphere mitm, as sys-con's "mitm" mode
 * and MissionControl do) and declares exactly two commands:
 *
 *   201 SendVibrationValue  (u32 handle, VibrationValue, pid + aruid)
 *   206 SendVibrationValues (u64 aruid, handles[], values[] - pointer buffers)
 *
 * Each only LOOKS at the values: a handle naming our virtual Pro Controller
 * is noted, then the handler returns ResultShouldForwardToSession, so
 * libstratosphere replays the game's original request on hid unchanged -
 * real controllers rumble as before, and hid answers exactly as it would
 * have. Everything else the game asks hid passes straight through.
 *
 * Which npad is ours: the handle's style must be FullKey, and its npad
 * the one showing our controller colours (hid's shared memory, read-only);
 * if no npad shows them, any Pro Controller's rumble counts.
 *
 * Only registered with remote_pad and remote_rumble on at boot (config.ini
 * remote_rumble = 0 -> token "norumble" leaves hid alone), and refused when
 * hid's pointer buffer or domain ids would not fit, or another mitm already
 * holds hid (sys-con's mitm mode). Its server lives in FeatureMemory.
 */
#pragma once
#include <stratosphere.hpp>
#include <atomic>
#include "sft_rumble_proto.hpp"

namespace ams::mitm::applet {

    extern std::atomic<bool> g_rumble_enabled;   /* !"norumble" */

    /* main.cpp, at boot: wrap the applications' hid (if enabled) */
    void StartRumbleMitm();

    /* "SFTV" from the viewer (the network thread or the USB stream loop) */
    void RumbleViewerAsks();
    /* the viewer left: nothing more goes to it */
    void RumbleViewerGone();

    /* the gamepad thread: our virtual controller came / went */
    void RumblePadAttached();
    void RumblePadDetached();

    /* the stream, before each video frame: true = send this body now
     * (sft::rumble::BodySize bytes) */
    bool RumbleTake(u8 *body);

    void RumbleLogStats(const char *who);

}
