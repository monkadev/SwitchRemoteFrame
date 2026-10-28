/*
 * v0.7.16: remote gamepad - the controller connected to the Android viewer,
 * as a virtual Pro Controller on the console.
 *
 * The viewer sends the controller's state ("SFTG", sft_pad_proto.hpp) on the
 * stream's connection; the network thread (any time a viewer is connected)
 * and the USB stream loop (while it streams) hand each message to
 * PadFromViewer / PadInput, which only queue it. A thread of its own makes
 * the hid:dbg calls - the HDLS virtual device, as sys-botbase does - so
 * neither the network thread nor the capture ever waits on hid.
 *
 * hid:dbg is opened, and the virtual Pro Controller attached, the first time
 * the viewer says a gamepad is there (a console nobody plays from the viewer
 * never opens it). The controller is detached again when the viewer says the
 * gamepad is gone, the viewer leaves, or remote_pad is switched off; with
 * nothing heard for 1 s while a button is held, everything is let go (the
 * controller stays).
 *
 * The console treats it as any newly connected Pro Controller: on the HOME
 * menu it just works; a game that wants players assigned shows its
 * controller screen (or use Controllers > Change Grip/Order).
 *
 * On unless config.ini has remote_pad = 0 (token "nopad"); the Switch app's
 * Stream page switches it, and it applies at once (sftap reload). Do not
 * run another program that drives HDLS controllers (sys-botbase's click /
 * press commands) at the same time.
 */
#pragma once
#include <stratosphere.hpp>
#include <atomic>
#include "sft_pad_proto.hpp"

namespace ams::mitm::applet {

    extern std::atomic<bool> g_pad_enabled;

    /* the gamepad thread (idle until the first message) */
    void StartPad();

    /* one viewer -> console message, exactly sft::pad::MsgSize bytes (a USB
     * transfer). Never blocks. */
    void PadFromViewer(const u8 *msg, size_t len);
    /* the same, already parsed (the network stream, sft::touch::Stream) */
    void PadInput(const ::sft::pad::State &s);

    /* the viewer left (or the stream over USB ended): the virtual controller goes */
    void PadRelease(const char *why);

    void PadLogStats(const char *who);

}
