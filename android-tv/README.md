# Switch Remote Frame for Android and Android TV

An **Android / Android TV / Google TV viewer for Android 6.0 (API 23) and later**.
It receives H.264 video and PCM audio
directly from the Switch Remote Frame console build over the local network or USB, like the PC viewer,
and looks like it: the same home screen, Settings drawer, colours and typeface. The app
shows and plays the stream; with the console build that has remote input
(0.7.15-touch-pad or later), touching the picture touches the Switch's screen and a
gamepad connected to the phone or TV plays on the Switch as a Pro Controller. On a
phone or tablet an **on-screen gamepad** can play instead, and (0.7.15-touch-pad-rumble
or later) the game's **rumble** vibrates the gamepad or the phone.

Since Switch Frame Tap 0.7.14 the app ships with each release
(`switch-frame-tap-<version>-android.apk`) and carries the release's version. It
has played on a Hisense Android TV (Android 11) over USB; other TVs, phones and
latency are still being reported, so please tell us how it does on yours.

## Installation and connection

1. Download `switch-frame-tap-<version>-android.apk` from the release, copy it to
   the TV, TV box or phone, and open it with a file manager that can install APKs.
   Allow installation from that file manager when Android asks. The app appears as
   **Switch Frame Tap**. (Coming from the 0.1.x preview APK: uninstall it first, it
   was signed with a different key.) Release APKs are signed with the key whose
   SHA-256 certificate fingerprint is
   `07:4B:E6:96:07:FD:85:5B:C7:5D:84:FA:25:05:C2:25:3B:4C:78:62:30:A7:5E:32:27:C8:14:50:A0:FE:8B:B6`.
2. In the Switch Frame Tap app on the console, set Connection to match (USB - PC
   viewer, or Network) and restart the console if it asks. Close the PC viewer:
   the console serves one viewer at a time.
3. **Network:** choose Network in the app. The Switch and this device must be on the
   same local network; the Switch can stay docked. Leave the IP address blank and the
   app finds the Switch (UDP 9951) or uses the last address; otherwise type the
   address the console's app shows. Streaming uses TCP 9950.
4. **USB:** choose USB in the app and connect the Switch's USB-C port to a USB host
   port of the device with a data cable (the Switch in handheld). Allow USB access
   when Android asks. Webcam modes are not supported by this app.
5. Select **Connect** and open a game. The picture replaces the home screen; while
   there is none, the home screen says why, as the console reports it ("Connected -
   no game is running", ...). The app connects again by itself the next time it opens.

## Controls

- **Back opens Settings** - over the game and on the home screen - and closes it
  again. **Exit** in Settings' top bar leaves the app. There is no button on the
  picture: with touch control every tap there is the Switch's.
- TV remote: the arrows move, OK selects. **Menu** opens Settings anywhere; during
  the game **OK** opens it too.
- Phone or tablet: the gear on the home screen, or Back (the back gesture).
- Gamepad: during the game it is the Switch's; **hold − and + together** (Select
  and Start on most gamepads) for a moment to open Settings. In Settings the
  gamepad moves around: D-pad or stick to move, A to select, B to go back.
- **Settings:** Connection (USB / Network), **Mute** (this device only), **Stable
  audio** (a 60 ms buffer instead of 20 ms, if the sound drops out - it reconnects),
  **Stats overlay** (frame rates, bitrate, decoder, dropped frames, sound, the
  device's audio buffer, touch and gamepad), **Framerate only** (just the frames
  shown each second, a small white number in the corner), **Touch control**,
  **Gamepad control**, **Gamepad settings**, **On-screen gamepad**, **On-screen
  gamepad layout** and **Device vibration**.

## Touch control

With the console build that has remote input (0.7.15-touch or later) on the console, touching the picture touches
the Switch's screen: tap, drag, hold and pinch, up to five fingers, over the network
or USB (over USB while the picture is streaming). The picture is mapped onto the
console's 1280x720 touch screen whatever the stream's resolution, so it lines up in
handheld and docked. Docked, games that switch touch off when docked ignore it, as
they ignore the real screen.

- **Touch control** in the app's Settings turns it off on this device; a tap on the
  game then opens Settings, as before.
- **Touch from the viewer** on the Switch app's Stream page turns it off on the
  console (`remote_touch = 0` in `config.ini`), for every viewer.
- The console's own touch screen keeps working: it is only taken over while a finger
  is down in the app, and handed back when the last one lifts, when the app
  disconnects, or 2 s after the app stops answering with a finger down.
- Do not run sys-botbase (or anything else that drives the touch screen's AutoPilot)
  at the same time.

The app sends the whole touch screen in a 48-byte message on the stream's own
connection whenever it changes ("SFTT": version 1, the number of fingers, a
sequence number, then x, y and a finger id for each; see
`tier4/applet-mitm/source/sft_touch_proto.hpp`). Consoles without remote touch ignore it.

## Gamepad control

With the console build that has remote input (0.7.15-touch-pad or later), a gamepad
connected to the phone, tablet or TV - Bluetooth or USB: Xbox, PlayStation, 8BitDo,
generic Android pads - plays on the Switch as a **Pro Controller**, over the network
or USB (over USB while the picture is streaming). Several gamepads play as one.

- The Switch sees a newly connected Pro Controller the first time you press
  something. On the HOME menu it just works; a game that wants players assigned
  shows its controller screen, or use **Controllers > Change Grip/Order** on the
  console.
- It leaves the Switch when the app disconnects or goes to the background, when
  Gamepad control is turned off, or when the last gamepad is disconnected. While
  Settings is open the controller stays but nothing is held; if the app stops
  answering with a button held, the console lets go after 1 s.
- **Gamepad control** in the app's Settings turns it off on this device; **Gamepad
  from the viewer** on the Switch app's Stream page turns it off on the console
  (`remote_pad = 0` in `config.ini`), for every viewer.
- Do not use sys-botbase's controller commands at the same time.

**Gamepad settings** (Settings > Control) is a page of its own:

- **Gamepad vibration** (on by default): the gamepad rumbles when the game rumbles
  the controller - see *Vibration* below. **Test vibration** gives a short buzz on
  every connected gamepad that has motors.

- **Test:** a Pro Controller drawn on the left lights up with what the Switch would
  get - after the mapping - with both sticks where they are, and the last input's
  name. Nothing reaches the Switch while the page is open.
- **A B X Y:** *Switch positions* (the default: the bottom face button is B and the
  right one A, as on a Switch controller) or *Letters* (the button marked A is A).
- **Stick dead zone:** 5, 10 (default), 15 or 25% of the travel around the centre.
- **Right stick axes:** Auto suits most gamepads; Z / RZ or RX / RY if the right
  stick moves the wrong way or acts as the triggers.
- **Settings from the gamepad:** hold − and + (default), hold both sticks in, or off.
  The shortcut's buttons are not passed to the game while both are held.
- **Buttons:** every Switch button (A, B, X, Y, L, R, ZL, ZR, −, +, the stick
  presses, the D-pad, HOME, Capture) with what it is bound to. Select one and press
  the gamepad button, trigger or D-pad direction that should be it; long-press to
  clear it. Defaults: L1/R1 = L/R, L2/R2 (or the analog triggers) = ZL/ZR,
  Select = −, Start = +, L3/R3 = stick presses, Guide = HOME, Capture unbound.
  **Reset buttons** brings them back.

The app sends the whole controller in a 32-byte message on the stream's own
connection whenever it changes, and every 250 ms while something is held ("SFTG":
version 1, a connected flag, a sequence number, the Switch's button bits and both
sticks; see `tier4/applet-mitm/source/sft_pad_proto.hpp`). Consoles without remote
input ignore it.

## On-screen gamepad

On a phone or tablet the Pro Controller can be played on the screen: two sticks, the
D-pad, A B X Y, L R ZL ZR, − + and HOME are drawn, see-through, over the game.

- **On-screen gamepad** (Settings > Control): *Auto* (the default) shows it on a
  touch screen while no gamepad is connected - connect one and it goes away;
  *Always*; or *Off*. It shows only while the game does, not under Settings.
- Several fingers at once: a thumb on the stick, another on A, a third on the
  game. A touch between the controls goes to the Switch's touch screen (with Touch
  control on) - or, with Touch control off, opens Settings.
- It plays as the same Pro Controller as the gamepads (both can be used together)
  and does not need *Gamepad control*; the Switch sees the controller from the first
  touch until the app disconnects. The buttons give a light tap with the system's
  touch feedback on.
- **Show / hide:** the round gamepad button in the **bottom left** corner hides
  the controls (to watch, or to use the touch screen freely) and shows them again;
  the app remembers which. While hidden nothing is held on the Switch.
- **Edit:** the pencil button in the **top right** corner (or **On-screen gamepad
  layout** in Settings > Control) opens the layout editor over the game:
  - **drag** a control to move it;
  - **tap** a control to select it: a bubble above it (one at a time, for the
    selected control) has three icons - **smaller**, **bigger** (50-200%) and
    **remove**. Tap anywhere else to put it away;
  - the **panel on the right**, over everything, has the **opacity** (25 / 50 /
    75 / 100%), **Add** (removed controls - and the stick presses L3, R3 and
    Capture, hidden at first - back on the screen), **Reset layout**, **Cancel**
    and **Done**. Its chevron slides it away to reach the controls under it; the
    pencil in the top right corner brings it back. It also fades while a control
    is dragged, and makes way when one is dropped under it;
  - nothing is kept until **Done**; **Cancel** (or Back) leaves the layout as it
    was. Positions are kept as a share of the screen, so a layout survives
    turning or resizing.
- With Touch control on, the two corner buttons take the touches on their own
  spot (40 dp) instead of the Switch's screen.

## Vibration

With the console build that has rumble (0.7.15-touch-pad-rumble or later), when a game
rumbles the viewer's Pro Controller the app vibrates:

- **the gamepads** connected to this device that have motors (Settings > Control >
  Gamepad settings > **Gamepad vibration**). On Android 12 and later both motors
  of a gamepad are driven (left and right); before Android 12 one motor does both;
- **this phone or tablet** (Settings > Control > **Device vibration**), the
  stronger of the two motors. Switching it on gives a short buzz; it says so if the
  device cannot vibrate.

The two are independent: on a TV or a phone without a motor, the gamepad still
rumbles. **Test vibration** buzzes every connected gamepad and says which ones have a
motor Android can use (some gamepads do not report theirs to Android, mostly over
Bluetooth before Android 12).

**Xbox controller, but "Android finds no motor"?** Over Bluetooth, an Xbox
controller's rumble comes from the Linux kernel's `hid-microsoft` driver (rumble
since Linux 4.20; the current controllers - Series X|S, and One S / Elite 2 on the
2021 firmware - since 6.1, backported by some vendors). Older kernels, such as the
Xiaomi Pad 5's 4.14 (stock and crDroid), have none, so Android - and every app -
sees no motor, while the same controller rumbles on a newer phone. Two ways round it:

- **A USB cable:** wired, the kernel's `xpad` driver rumbles it (most Android
  kernels have it), and the app uses it like any gamepad motor.
- **Vibration through root** (Gamepad settings, off by default): on a rooted device
  (KernelSU, Magisk...) the app sends the rumble to the controller itself - the
  same 9-byte output report `hid-microsoft` sends (report 3: both motors, strength
  0-100) written to the controller's `/dev/hidrawN`, which Android's Bluetooth
  stack passes on. The root manager asks once when it is turned on; **Test
  vibration** then says *Vibrating through root*. Each report lasts at most 2.55 s
  and is refreshed every second while the game rumbles, so the controller stops by
  itself if the app goes away. It covers the Xbox One S (1708), Elite Series 2 and
  Xbox Series X|S controllers over Bluetooth; turn it off on a device where Android
  already finds the motor.

Both are on by default; with both off the app does not ask the console for rumble.
The Switch's HD rumble is turned into plain strength: games use mostly gentle
rumble, so quiet values are lifted to something a phone motor can feel. If no
update arrives for 300 ms (the game stopped, the connection dropped) the motors stop.

On the console, **Vibration to the viewer** on the Switch app's Stream page
(`remote_rumble = 0` in `config.ini`) turns it off for every viewer; it needs
*Gamepad from the viewer*, and it takes effect after a restart. It works by watching
the rumble a game sends to the controllers (a `hid` MITM, for games only - nothing
the game sees changes). Only one program can do that: with **sys-con** (or another
`hid` MITM) installed, rumble to the viewer stays off and everything else works.

The app asks with a 4-byte "SFTV" every 2 s on the stream's connection; the console
then sends, before the next frame, a stream packet with flag 32 and an 8-byte body
whenever the rumble changes and every 100 ms while it runs (version 1, a sequence
number, then the left and right motor's low and high band, 0-255; see
`tier4/applet-mitm/source/sft_rumble_proto.hpp`). Viewers that do not ask get none.

The app closes the connection and releases audio and USB when it goes to the
background, and reconnects when it comes back.

## Quality and latency

The app preserves the received H.264 stream without re-encoding or reducing its
resolution. Quality is selected on the Switch, whose network sender can adjust it
to the available bandwidth. Start with High and 1080p if your configuration supports
them. If playback pauses or repeatedly resynchronizes, try Medium or 720p on the
console. Actual 1080p60 capture also depends on the game and Switch setup; the app
does not turn a 30 fps game into 60 fps.

Hardware H.264 decoders are preferred. On Android 11 and later, low-latency decoding
is requested when the driver advertises support. If an optimized configuration
fails, the app tries a basic configuration and other decoders, with software as a
last resort. Software decoding can increase latency.

**Lower decoder delay** (0.7.15-touch-6). Two changes to the end of the pipeline
(MediaCodec), measured by the playback test:
- **A decoded frame is shown the moment it is ready.** While a frame is being
  decoded, the decoder thread now waits on the decoder itself instead of
  sleeping 2-4 ms on the frame queue. A finished frame used to wait about 2 ms
  on average before it was collected (about 1 ms with Smooth video). It now
  waits about 0.1 ms. With Smooth video, the worst gap between frames in a
  Wi-Fi burst also went from 33 ms to 18 ms.
- **Vendor low-latency options**, as Moonlight sets them, for hardware decoders
  that do not advertise Android 11's low-latency mode:
  - Qualcomm: `vendor.qti-ext-dec-low-latency.enable`,
    `vendor.qti-ext-dec-picture-order.enable` and the highest operating rate.
  - MediaTek: `vdec-lowlatency`.
  - Exynos, Kirin and Amlogic: their own keys.

  If a decoder refuses these options, stops giving frames or slows down with
  them, it is set up again with the standard keys. It is not dropped for the
  software decoder. The stats overlay names the mode ("vendor low latency",
  "low latency").

The stream from the Switch already suits this: no B frames, POC type 2, and
`max_dec_frame_buffering = 1` in the SPS, so a decoder may output each frame at
once. No bitstream fix-up is needed.

**Smooth video** (Settings → Display, off by default since 0.7.15-touch-6; turn it on if Wi-Fi bursts stutter). Frames
often arrive in bursts, especially over Wi-Fi at 1080p, where each frame is
bigger: nothing for 50 ms, then three frames at once. Earlier versions always
showed only the newest frame immediately and discarded the others. That caused
the picture to jump several times a second even though every frame arrived. The
app now does what the PC viewer has done since v0.2. Every frame is decoded and
shown in order, one per screen refresh. The oldest is dropped only when more
than two frames are waiting, which adds at most about two frames of delay. On a
screen faster than the stream (a 120 Hz tablet, or a 30 fps game on 60 Hz), each
frame stays on screen for an even number of refreshes. Turn it off for the
lowest possible delay (the newest frame at once, jumpier). The stats overlay
shows the mode and the **worst gap**, the longest wait between two shown frames
in the last second. About 17 ms at 60 fps is perfect; regular spikes of 50 ms or
more mean frames are not reaching the device in time.

Queues are bounded. Input reference recovery starts at an IDR after a gap, or
when 12 frames or 250 ms are waiting for the decoder (before 0.7.16: 6 frames or
100 ms, which a Wi-Fi burst at 1080p could exceed, causing a freeze until the next
keyframe). A device or connection that cannot keep up can still cause skipped
frames or a pause until the next keyframe. Maximum quality and a fixed low
latency cannot be guaranteed on every TV.

**1080p still stutters?** Look at the stats overlay:

- **Video RX below about 58 FPS** means the console is sending fewer frames than
  the game draws. 1080p60 needs a CPU clock of 1785 MHz (sys-clk or Horizon OC);
  at the stock clock it runs a little below 60. Some games also run below 60
  in docked mode.
- **Video RX at 60 but a large worst gap and Mbps near the limit** means the
  network is the bottleneck. Choose Medium quality on the Switch, move closer to
  the router, use 5 GHz, or use USB (handheld, with ReverseNX-RT for 1080p).
- **decoded well below RX** means the device's decoder cannot keep up at 1080p.

Version 0.1.1 fixes repeated decoder initialization while waiting for the first
input buffer. It uses relative presentation timestamps and handles drivers that
rewrite them. It also removes the fixed 120 ms output rejection threshold, which
could discard every frame in a deeper pipeline. A decoder that accepts input but
produces no output is replaced at a subsequent IDR.

Audio is no longer flushed when video resynchronizes or whenever its native queue
exceeds 80 ms. Some TVs require a larger native buffer before playback can start.
AudioTrack bounds the system queue while the application keeps its own queue small.
On Android 12 and later, a low start threshold is requested when the driver allows it.

**Statistics do not measure total end-to-end latency.** Queue + decode measures
the interval from receiving the packet header to submitting decoded output for
display. It excludes network delay before arrival and the panel's own processing.
Console age comes from the sender and is a separate measurement. The protocol does
not provide a shared presentation clock for audio and video; the viewer limits
queue accumulation with small buffers.

## Compatibility

The same APK contains Java code and resources without CPU-specific native libraries.
It is intended for Android TV and Google TV devices, including Sony TVs running
Android TV, with **Android 6.0 or later**. There is no confirmed model compatibility
list yet. Sony TVs with another operating system or Android older than 6.0 are not
supported by this version.

USB is optional and detected at runtime. The TV firmware must expose USB host
support and permit access to device `1209:5f1e`, interface 0, bulk IN endpoint `0x81`.
A port that supports USB storage does not by itself confirm this capability.
Network reception does not depend on USB host support.

## Building

See [BUILDING.md](BUILDING.md) for requirements, environment setup, SDK installation,
build commands, tests, and optional distribution packaging.

Releases are built by GitHub Actions (`.github/workflows/viewer-android.yml`) on each
version tag; by hand it can also take screenshots on an Android TV and a phone
emulator. With JDK 17 and Android SDK Platform 34 / Build Tools 34.0.0 configured, run
from `android-tv/` on Windows:

```powershell
.\gradlew.bat :app:assembleDebug :app:protocolTest :app:playbackTest :app:lintDebug
python tools/package.py
```

On Linux/macOS, use `bash gradlew` in place of `.\gradlew.bat`.
The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Packaging copies
it to `../dist/` with the corresponding source archive and SHA-256 checksums.

This manually installed preview targets SDK 34. A Google Play release would require
reviewing the current target SDK and newer Android local-network permissions.

## Testing without a Switch

From the full repository root, on a PC on the same local network as the TV:

```shell
python android-tv/tools/replay_server.py logs/m83-runI/stream-first10.sft --tone
```

Enter the address printed by the server in the TV app and select **Connect**.
The server loops a real H.264 capture and generates a 440 Hz stereo PCM tone. This
allows testing Android decoding, audio, and TCP reconnection separately from the
console. The older capture contains only IDR frames; it does not replace testing
the current IDR/P stream, USB, or actual Switch latency. The fixture is not
included in the Android source ZIP.

See [VALIDATION.md](VALIDATION.md) for completed checks and the pending TV test plan.

## License

Copyright (C) 2026 monkadev. `LICENSE` contains GPL-2.0, and `UPSTREAM-NOTICE`
lists the third-party material the project depends on.
The app bundles DejaVu Sans (`app/src/main/assets/fonts/`, its licence alongside).
The distribution includes the corresponding source code for the Android viewer.

Official API references:
[MediaCodec](https://developer.android.com/reference/android/media/MediaCodec),
[low-latency decoding](https://source.android.com/docs/core/media/low-latency-media),
[AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[USB](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection),
[Android TV](https://developer.android.com/training/tv/get-started/create).