/* gp_remap.h - v1.0.2: which gamepad input is which Switch button.
 *
 * The Android app lets the user pick a Switch button and then press the gamepad
 * button that should be it; this is the table that stores the answer. It is the
 * model on its own - no SDL - so tools/vpad_test.c can check the defaults, the
 * bounds and the saved-string round trip without a controller. gamepad_page.h
 * adds the part that reads a real controller through it.
 */
#pragma once

#include <stdint.h>

#include "vpad_layout.h"

/* A gamepad input, as a slot in the remap table: SDL's buttons first, then each
 * axis at each end (so a trigger or a stick direction can be a button). The
 * size is derived from SDL's own counts, not counted by hand - with SDL 2's 21
 * buttons and 6 axes the last slot is 21 + 6 + 5 = 32, which a hand-written 32
 * would run one past. */
#define GP_SLOTS 34
enum { GP_IN_BUTTONS = 21,                 /* SDL_CONTROLLER_BUTTON_MAX: 0 .. 20 */
       GP_IN_AX_LO = GP_IN_BUTTONS,        /* 21 .. 26: an axis pushed to -1 */
       GP_IN_AX_HI = GP_IN_BUTTONS + 6 };  /* 27 .. 32: an axis pushed to +1 */

/* gamepad_page.h checks these against SDL's own enums at compile time. */

/* The Switch buttons the settings page can remap, in the Android app's order. */
static const int gp_bits[20] = {
    VPB_A, VPB_B, VPB_X, VPB_Y, VPB_L, VPB_R, VPB_ZL, VPB_ZR,
    VPB_MINUS, VPB_PLUS, VPB_LSTICK, VPB_RSTICK,
    VPB_UP, VPB_DOWN, VPB_LEFT, VPB_RIGHT, VPB_HOME, VPB_CAPTURE
};
#define GP_BITS 20

static const char *gp_bit_name(int bit)
{
    switch (bit) {
    case VPB_A: return "A"; case VPB_B: return "B"; case VPB_X: return "X"; case VPB_Y: return "Y";
    case VPB_L: return "L"; case VPB_R: return "R"; case VPB_ZL: return "ZL"; case VPB_ZR: return "ZR";
    case VPB_MINUS: return "- (Minus)"; case VPB_PLUS: return "+ (Plus)";
    case VPB_LSTICK: return "L3 (left stick press)"; case VPB_RSTICK: return "R3 (right stick press)";
    case VPB_UP: return "D-pad up"; case VPB_DOWN: return "D-pad down";
    case VPB_LEFT: return "D-pad left"; case VPB_RIGHT: return "D-pad right";
    case VPB_HOME: return "HOME"; case VPB_CAPTURE: return "Capture";
    default: return "?";
    }
}

/* Which physical axes are the right stick. "Auto" is SDL's own RIGHTX / RIGHTY.
 * The Android app's choice is Auto / Z-RZ / RX-RY, but SDL's game-controller
 * API has no Z or RZ axis to read - those are raw-joystick axes - so the two
 * alternatives here are the ones a PC controller can actually be wrong about:
 * its Y running the wrong way, or its triggers standing in for the stick. */
enum { GP_RIGHT_AUTO = 0, GP_RIGHT_INVERT = 1, GP_RIGHT_TRIGGERS = 2 };
static const char *const gp_right_names[3] = { "Auto", "Flip up / down", "Triggers" };

static int8_t g_gp_map[GP_SLOTS];          /* a Switch button bit, or -1 for "nothing" */
static int g_gp_map_set = 0;
static int g_gp_right = GP_RIGHT_AUTO;
static int g_pad_rumble_on = 1;            /* "Gamepad vibration" */

/* SDL's own layout: what each input is before the user changes anything. The
 * button numbers are SDL's SDL_CONTROLLER_BUTTON_* values, spelled out so this
 * file stays free of SDL. */
enum { GPC_A = 0, GPC_B = 1, GPC_X = 2, GPC_Y = 3, GPC_BACK = 4, GPC_GUIDE = 5,
       GPC_START = 6, GPC_LEFTSTICK = 7, GPC_RIGHTSTICK = 8, GPC_LEFTSHOULDER = 9,
       GPC_RIGHTSHOULDER = 10, GPC_DPAD_UP = 11, GPC_DPAD_DOWN = 12,
       GPC_DPAD_LEFT = 13, GPC_DPAD_RIGHT = 14 };
/* the triggers are axes, not buttons, in SDL's game-controller API */
enum { GPA_TRIGGERLEFT = 4, GPA_TRIGGERRIGHT = 5 };

static void gp_map_defaults(void)
{
    for (int i = 0; i < GP_SLOTS; i++) g_gp_map[i] = -1;
    g_gp_map[GPC_A] = VPB_A;
    g_gp_map[GPC_B] = VPB_B;
    g_gp_map[GPC_X] = VPB_X;
    g_gp_map[GPC_Y] = VPB_Y;
    g_gp_map[GPC_LEFTSTICK] = VPB_LSTICK;
    g_gp_map[GPC_RIGHTSTICK] = VPB_RSTICK;
    g_gp_map[GPC_LEFTSHOULDER] = VPB_L;
    g_gp_map[GPC_RIGHTSHOULDER] = VPB_R;
    g_gp_map[GPC_START] = VPB_PLUS;
    g_gp_map[GPC_BACK] = VPB_MINUS;
    g_gp_map[GPC_GUIDE] = VPB_HOME;
    g_gp_map[GPC_DPAD_UP] = VPB_UP;
    g_gp_map[GPC_DPAD_DOWN] = VPB_DOWN;
    g_gp_map[GPC_DPAD_LEFT] = VPB_LEFT;
    g_gp_map[GPC_DPAD_RIGHT] = VPB_RIGHT;
    g_gp_map[GP_IN_AX_LO + GPA_TRIGGERLEFT] = VPB_ZL;
    g_gp_map[GP_IN_AX_HI + GPA_TRIGGERLEFT] = VPB_ZL;
    g_gp_map[GP_IN_AX_LO + GPA_TRIGGERRIGHT] = VPB_ZR;
    g_gp_map[GP_IN_AX_HI + GPA_TRIGGERRIGHT] = VPB_ZR;
    g_gp_right = GP_RIGHT_AUTO;
    g_gp_map_set = 1;
}

static void gp_map_ensure(void) { if (!g_gp_map_set) gp_map_defaults(); }

/* Which physical input is this Switch button right now (-1: none). */
static int gp_slot_for_bit(int bit)
{
    gp_map_ensure();
    for (int i = 0; i < GP_SLOTS; i++) if (g_gp_map[i] == bit) return i;
    return -1;
}

/* "1;0,0,1,2,-1,..." - the axis choice and the whole table in one viewer.ini
 * line, so a mapping survives a restart. */
static void gp_save_str(char *out, size_t cap)
{
    gp_map_ensure();
    size_t n = (size_t)snprintf(out, cap, "1;%d", g_gp_right);
    for (int i = 0; i < GP_SLOTS && n + 8 < cap; i++) n += (size_t)snprintf(out + n, cap - n, ",%d", g_gp_map[i]);
}

static void gp_load_str(const char *s)
{
    if (!s || !*s) { gp_map_defaults(); return; }
    int ver = 0, right = 0;
    if (sscanf(s, "%d;%d", &ver, &right) != 2 || ver != 1) { gp_map_defaults(); return; }
    gp_map_defaults();
    if (right >= 0 && right <= 2) g_gp_right = right;
    /* "1;<right>,<slot0>,<slot1>,..." - the axis choice has just been read from
     * the header, so the table starts after it. Reading it here too would slide
     * every slot along by one. */
    const char *p = strchr(s, ';');
    if (!p) return;
    p = strchr(p + 1, ',');
    if (!p) return;
    p++;
    for (int i = 0; i < GP_SLOTS; i++) {
        int v = -1;
        if (sscanf(p, "%d", &v) != 1) break;
        g_gp_map[i] = (int8_t)(v >= -1 && v < 32 ? v : -1);
        p = strchr(p, ',');
        if (!p) break;
        p++;
    }
    g_gp_map_set = 1;
}
