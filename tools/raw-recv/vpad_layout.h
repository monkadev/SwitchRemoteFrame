/* vpad_layout.h - v1.0.2: the on-screen gamepad's layout - which controls show,
 * where, and how big. The PC port of the Android app's VirtualLayout.java, so a
 * layout means the same thing on both and the format is interchangeable:
 *
 *   "v1;id:x,y,scale,shown;..."
 *
 * A control's position is its centre as a fraction of the screen (0..1 each
 * way), so a layout survives other window sizes; the defaults are placed from
 * the corners in dp for the screen they are made for. Unknown ids are skipped
 * and missing ones take their default, so a layout from a newer app still loads.
 *
 * Pure C with no SDL: tools/vpad_test.c checks the parse, the serialize round
 * trip and the stick / D-pad maths without a window.
 */
#pragma once

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* What a control is: a stick, the D-pad, a round button, a shoulder button, a
 * small round one. */
enum { VP_STICK = 0, VP_DPAD = 1, VP_ROUND = 2, VP_SHOULDER = 3, VP_SMALL = 4 };

#define VP_N 17
#define VP_MIN_SCALE 0.5f
#define VP_MAX_SCALE 2.0f
#define VP_SCALE_STEP 0.1f
#define VP_STICK_MAX 32767

/* The Switch's own button bits (HidNpadButton), as Pad.java and the viewer's
 * keyboard map have them. */
enum { VPB_A = 0, VPB_B = 1, VPB_X = 2, VPB_Y = 3, VPB_LSTICK = 4, VPB_RSTICK = 5,
       VPB_L = 6, VPB_R = 7, VPB_ZL = 8, VPB_ZR = 9, VPB_MINUS = 10, VPB_PLUS = 11,
       VPB_LEFT = 12, VPB_UP = 13, VPB_RIGHT = 14, VPB_DOWN = 15, VPB_HOME = 18, VPB_CAPTURE = 19 };

/* The controls, in the order the Android app lists them. */
static const char *const vp_ids[VP_N] = {
    "ls", "rs", "dpad", "a", "b", "x", "y", "l", "r", "zl", "zr",
    "minus", "plus", "l3", "r3", "home", "capture"
};

typedef struct {
    const char *id;
    float x, y, scale;      /* centre as a fraction of the screen; size at scale 1 is vp_width_dp */
    int shown;
} vp_item_t;

typedef struct { vp_item_t items[VP_N]; } vp_layout_t;

static int vp_kind(const char *id)
{
    if (!strcmp(id, "ls") || !strcmp(id, "rs")) return VP_STICK;
    if (!strcmp(id, "dpad")) return VP_DPAD;
    if (!strcmp(id, "a") || !strcmp(id, "b") || !strcmp(id, "x") || !strcmp(id, "y")) return VP_ROUND;
    if (!strcmp(id, "l") || !strcmp(id, "r") || !strcmp(id, "zl") || !strcmp(id, "zr")) return VP_SHOULDER;
    return VP_SMALL;
}

/* The Switch button a control presses, -1 for the sticks and the D-pad (they
 * are axes and directions, not one bit). */
static int vp_button(const char *id)
{
    if (!strcmp(id, "a")) return VPB_A;
    if (!strcmp(id, "b")) return VPB_B;
    if (!strcmp(id, "x")) return VPB_X;
    if (!strcmp(id, "y")) return VPB_Y;
    if (!strcmp(id, "l")) return VPB_L;
    if (!strcmp(id, "r")) return VPB_R;
    if (!strcmp(id, "zl")) return VPB_ZL;
    if (!strcmp(id, "zr")) return VPB_ZR;
    if (!strcmp(id, "minus")) return VPB_MINUS;
    if (!strcmp(id, "plus")) return VPB_PLUS;
    if (!strcmp(id, "l3")) return VPB_LSTICK;
    if (!strcmp(id, "r3")) return VPB_RSTICK;
    if (!strcmp(id, "home")) return VPB_HOME;
    if (!strcmp(id, "capture")) return VPB_CAPTURE;
    return -1;
}

/* What is drawn inside the control. */
static const char *vp_label(const char *id)
{
    if (!strcmp(id, "ls")) return "L";
    if (!strcmp(id, "rs")) return "R";
    if (!strcmp(id, "dpad")) return "";
    if (!strcmp(id, "minus")) return "-";
    if (!strcmp(id, "plus")) return "+";
    if (!strcmp(id, "l3")) return "L3";
    if (!strcmp(id, "r3")) return "R3";
    if (!strcmp(id, "home")) return "H";
    if (!strcmp(id, "capture")) return "C";
    /* a, b, x, y, l, r, zl, zr: the id in capitals */
    static char up[8];
    int i = 0;
    for (; id[i] && i < 6; i++) up[i] = (char)((id[i] >= 'a' && id[i] <= 'z') ? id[i] - 32 : id[i]);
    up[i] = 0;
    return up;
}

/* The name in the editor's Add list. */
static const char *vp_name(const char *id)
{
    if (!strcmp(id, "minus")) return "- (Minus)";
    if (!strcmp(id, "plus")) return "+ (Plus)";
    if (!strcmp(id, "l3")) return "L3 (left stick press)";
    if (!strcmp(id, "r3")) return "R3 (right stick press)";
    if (!strcmp(id, "home")) return "HOME";
    if (!strcmp(id, "capture")) return "Capture";
    if (!strcmp(id, "ls")) return "L stick";
    if (!strcmp(id, "rs")) return "R stick";
    if (!strcmp(id, "dpad")) return "D-pad";
    return vp_label(id);
}

/* The control's size at scale 1, in dp (Android's units; here 1 dp = 1 unit of
 * the 720-high logical space, which is what the picture is drawn in). */
static float vp_width_dp(const char *id)
{
    switch (vp_kind(id)) {
    case VP_STICK: return 128;
    case VP_DPAD: return 124;
    case VP_ROUND: return 56;
    case VP_SHOULDER: return 76;
    default: return 40;
    }
}
static float vp_height_dp(const char *id) { return vp_kind(id) == VP_SHOULDER ? 40 : vp_width_dp(id); }

static float vp_clamp01(float v) { return v != v ? 0.5f : (v < 0 ? 0 : (v > 1 ? 1 : v)); }
static float vp_clamp_scale(float v)
{
    if (v != v) return 1.f;
    if (v < VP_MIN_SCALE) v = VP_MIN_SCALE;
    if (v > VP_MAX_SCALE) v = VP_MAX_SCALE;
    return (float)((int)(v * 10.f + (v < 0 ? -0.5f : 0.5f))) / 10.f;   /* the editor steps in tenths */
}

static vp_item_t *vp_get(vp_layout_t *v, const char *id)
{
    for (int i = 0; i < VP_N; i++) if (!strcmp(v->items[i].id, id)) return &v->items[i];
    return NULL;
}

/* The default layout for a window this big (landscape): the left stick and the
 * D-pad bottom left, the buttons and the right stick bottom right, the
 * shoulders along the top (the corners stay free: the frame rate top left, the
 * edit button top right), - and + at the top, HOME at the bottom centre.
 * Capture, L3 and R3 start hidden (the editor adds them). */
static void vp_defaults(vp_layout_t *v, float w, float h)
{
    if (w < 320) w = 320;
    if (h < 240) h = 240;
    for (int k = 0; k < VP_N; k++) {
        const char *id = vp_ids[k];
        float x, y;
        int shown = 1;
        if (!strcmp(id, "ls")) { x = 100; y = h - 175; }
        else if (!strcmp(id, "dpad")) { x = 215; y = h - 72; }
        else if (!strcmp(id, "rs")) { x = w - 215; y = h - 72; }
        else if (!strcmp(id, "a")) { x = w - 46; y = h - 175; }
        else if (!strcmp(id, "b")) { x = w - 100; y = h - 121; }
        else if (!strcmp(id, "x")) { x = w - 100; y = h - 229; }
        else if (!strcmp(id, "y")) { x = w - 154; y = h - 175; }
        else if (!strcmp(id, "zl")) { x = 100; y = 34; }
        else if (!strcmp(id, "l")) { x = 188; y = 34; }
        else if (!strcmp(id, "zr")) { x = w - 100; y = 34; }
        else if (!strcmp(id, "r")) { x = w - 188; y = 34; }
        else if (!strcmp(id, "minus")) { x = w / 2 - 56; y = 30; }
        else if (!strcmp(id, "plus")) { x = w / 2 + 56; y = 30; }
        else if (!strcmp(id, "home")) { x = w / 2 + 34; y = h - 30; }
        else if (!strcmp(id, "capture")) { x = w / 2 - 34; y = h - 30; shown = 0; }
        else if (!strcmp(id, "l3")) { x = 200; y = h - 185; shown = 0; }
        else if (!strcmp(id, "r3")) { x = w - 230; y = h - 165; shown = 0; }
        else { x = w / 2; y = h / 2; }
        v->items[k].id = id;
        v->items[k].x = vp_clamp01(x / w);
        v->items[k].y = vp_clamp01(y / h);
        v->items[k].scale = 1.f;
        v->items[k].shown = shown;
    }
}

/* The ids are the array's, so a layout that was never filled in would hand
 * vp_kind() a NULL. Every user goes through here first. */
static int g_vp_layout_ready = 0;
static void vp_layout_init(vp_layout_t *v)
{
    if (g_vp_layout_ready) return;
    vp_defaults(v, 1280, 720);
    g_vp_layout_ready = 1;
}

/* "v1;id:x,y,scale,shown;..." - the Android app's format, so a layout can be
 * copied between them. */
static void vp_serialize(const vp_layout_t *v, char *out, size_t cap)
{
    size_t n = 0;
    n += (size_t)snprintf(out + n, cap - n, "v1");
    for (int i = 0; i < VP_N && n + 48 < cap; i++)
        n += (size_t)snprintf(out + n, cap - n, ";%s:%.4f,%.4f,%.1f,%d",
                              v->items[i].id, v->items[i].x, v->items[i].y,
                              v->items[i].scale, v->items[i].shown ? 1 : 0);
}

/* A saved layout over the defaults for this window; the defaults alone when
 * there is none or it cannot be read. */
static void vp_parse(vp_layout_t *v, const char *s, float w, float h)
{
    vp_defaults(v, w, h);
    g_vp_layout_ready = 1;
    if (!s || strncmp(s, "v1", 2) != 0) return;
    const char *p = s;
    while ((p = strchr(p, ';')) != NULL) {
        p++;
        const char *colon = strchr(p, ':');
        if (!colon || colon == p) continue;
        char id[24];
        size_t idn = (size_t)(colon - p);
        if (idn >= sizeof(id)) idn = sizeof(id) - 1;
        memcpy(id, p, idn);
        id[idn] = 0;
        vp_item_t *d = vp_get(v, id);
        if (!d) continue;                        /* an id from a newer app: skip it */
        float x, y, sc;
        int shown = 0;
        if (sscanf(colon + 1, "%f,%f,%f,%d", &x, &y, &sc, &shown) != 4) continue;
        d->x = vp_clamp01(x);
        d->y = vp_clamp01(y);
        d->scale = vp_clamp_scale(sc);
        d->shown = shown != 0;
    }
}

/* ---- what a pointer on a control means ------------------------------------ */

/* A pointer on a stick, from its centre (dx, dy with y down) with this radius
 * -> the Switch's stick (-32767..32767, y up), full at the rim. */
static void vp_stick(float dx, float dy, float radius, int *ox, int *oy)
{
    if (radius <= 0) { *ox = 0; *oy = 0; return; }
    float x = dx / radius, y = -dy / radius;
    const float len = sqrtf(x * x + y * y);
    if (len > 1) { x /= len; y /= len; }
    *ox = (int)(x * VP_STICK_MAX + (x < 0 ? -0.5f : 0.5f));
    *oy = (int)(y * VP_STICK_MAX + (y < 0 ? -0.5f : 0.5f));
}

/* A pointer on the D-pad (from its centre, y down) -> the directions held:
 * eight ways, nothing in the middle fifth. */
static uint32_t vp_dpad(float dx, float dy, float radius)
{
    if (radius <= 0) return 0;
    if (dx * dx + dy * dy < (0.2f * radius) * (0.2f * radius)) return 0;
    double angle = atan2(-(double)dy, (double)dx) * 180.0 / 3.14159265358979323846;   /* 0 = right, 90 = up */
    if (angle < 0) angle += 360.0;
    const int sector = (int)((angle + 22.5) / 45.0) % 8;
    switch (sector) {
    case 0: return 1u << VPB_RIGHT;
    case 1: return (1u << VPB_RIGHT) | (1u << VPB_UP);
    case 2: return 1u << VPB_UP;
    case 3: return (1u << VPB_UP) | (1u << VPB_LEFT);
    case 4: return 1u << VPB_LEFT;
    case 5: return (1u << VPB_LEFT) | (1u << VPB_DOWN);
    case 6: return 1u << VPB_DOWN;
    default: return (1u << VPB_DOWN) | (1u << VPB_RIGHT);
    }
}
