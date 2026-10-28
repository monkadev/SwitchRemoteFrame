/*
 * v0.7.16: remote touch - the Android viewer's fingers on the console's touch
 * screen.
 *
 * The viewer sends the touch screen's state ("SFTT", sft_touch_proto.hpp) on
 * the stream's connection; the network thread (any time a viewer is
 * connected) and the USB stream loop (while it streams) hand each message to
 * TouchFromViewer, which only queues it. A thread of its own makes the hid:dbg
 * calls - SetTouchScreenAutoPilotState, as sys-botbase does - so neither the
 * network thread nor the capture ever waits on hid.
 *
 * hid:dbg is opened the first time a finger comes in (a console nobody
 * touches from the viewer never opens it), and the touch screen is handed
 * back (UnsetTouchScreenAutoPilotState) as soon as the last finger is lifted,
 * the viewer leaves, or it has said nothing for 2 s with a finger down: the
 * console's own touch screen works again in between.
 *
 * On unless config.ini has remote_touch = 0 (token "notouch"); the Switch
 * app's Stream page switches it, and it applies at once (sftap reload).
 * Do not run another program that drives the touch AutoPilot (sys-botbase)
 * at the same time.
 */
#pragma once
#include <stratosphere.hpp>
#include <atomic>
#include "sft_touch_proto.hpp"

namespace ams::mitm::applet {

    extern std::atomic<bool> g_touch_enabled;

    /* the touch thread (idle until the first message) */
    void StartTouch();

    /* one viewer -> console message, exactly sft::touch::MsgSize bytes (a
     * USB transfer, or what the network stream reassembled). Never blocks. */
    void TouchFromViewer(const u8 *msg, size_t len);
    /* the same, already parsed (the network stream, sft::touch::Stream) */
    void TouchFrame(const ::sft::touch::Frame &f);

    /* the viewer left (or the stream over USB ended): lift every finger */
    void TouchRelease(const char *why);

    void TouchLogStats(const char *who);

}
