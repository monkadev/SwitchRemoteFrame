/* gamepad_page.h - v1.0.2: the PC port of the Android app's Gamepad settings
 * page - remap the controller, watch what it sends, choose the right stick's
 * axes, and test the vibration.
 *
 * The Android app's rule, kept: "Select a Switch button, then press the gamepad
 * button (or trigger, or D-pad direction) that should be it." Nothing reaches
 * the console while the page is open, so a remap cannot press anything by
 * accident.
 *
 * gp_remap.h holds the table itself - no SDL, so tools/vpad_test.c can check
 * it - and this is the part that reads a real controller through it, plus the
 * page that edits it.
 *
 * The two Android-only options are not here: device vibration (a phone's motor)
 * and the root rumble fallback (an Android kernel workaround). The PC has
 * neither, and the gamepad's own motors are covered by "Gamepad vibration".
 */
#pragma once

#include "gp_remap.h"

/* gp_remap.h spells SDL's button and axis numbers out so it can be tested
 * without SDL; these say they are still the same ones. A future SDL that
 * renumbers them fails the build here rather than remapping silently. */
/* gp_remap.h spells SDL's button and axis numbers out so it can be tested
 * without SDL; these say they are still the same ones. A future SDL that
 * renumbers them fails the build here rather than remapping silently.
 * (Cast to int: comparing two different enum types is a warning in C++.) */
#define GP_SAME(a, b) ((int)(a) == (int)(b))
typedef char gp_check_slots[(GP_IN_BUTTONS == (int)SDL_CONTROLLER_BUTTON_MAX &&
                             GP_IN_AX_HI == (int)SDL_CONTROLLER_BUTTON_MAX + (int)SDL_CONTROLLER_AXIS_MAX &&
                             GP_SLOTS >= (int)SDL_CONTROLLER_BUTTON_MAX + 2 * (int)SDL_CONTROLLER_AXIS_MAX) ? 1 : -1];
typedef char gp_check_buttons[(GP_SAME(GPC_A, SDL_CONTROLLER_BUTTON_A) && GP_SAME(GPC_B, SDL_CONTROLLER_BUTTON_B) &&
                               GP_SAME(GPC_X, SDL_CONTROLLER_BUTTON_X) && GP_SAME(GPC_Y, SDL_CONTROLLER_BUTTON_Y) &&
                               GP_SAME(GPC_BACK, SDL_CONTROLLER_BUTTON_BACK) && GP_SAME(GPC_GUIDE, SDL_CONTROLLER_BUTTON_GUIDE) &&
                               GP_SAME(GPC_START, SDL_CONTROLLER_BUTTON_START) &&
                               GP_SAME(GPC_LEFTSTICK, SDL_CONTROLLER_BUTTON_LEFTSTICK) &&
                               GP_SAME(GPC_RIGHTSTICK, SDL_CONTROLLER_BUTTON_RIGHTSTICK) &&
                               GP_SAME(GPC_LEFTSHOULDER, SDL_CONTROLLER_BUTTON_LEFTSHOULDER) &&
                               GP_SAME(GPC_RIGHTSHOULDER, SDL_CONTROLLER_BUTTON_RIGHTSHOULDER) &&
                               GP_SAME(GPC_DPAD_UP, SDL_CONTROLLER_BUTTON_DPAD_UP) &&
                               GP_SAME(GPC_DPAD_DOWN, SDL_CONTROLLER_BUTTON_DPAD_DOWN) &&
                               GP_SAME(GPC_DPAD_LEFT, SDL_CONTROLLER_BUTTON_DPAD_LEFT) &&
                               GP_SAME(GPC_DPAD_RIGHT, SDL_CONTROLLER_BUTTON_DPAD_RIGHT)) ? 1 : -1];
typedef char gp_check_axes[(GP_SAME(GPA_TRIGGERLEFT, SDL_CONTROLLER_AXIS_TRIGGERLEFT) &&
                            GP_SAME(GPA_TRIGGERRIGHT, SDL_CONTROLLER_AXIS_TRIGGERRIGHT)) ? 1 : -1];

/* What a physical input is called. */
static const char *gp_input_name(int slot)
{
    if (slot < 0 || slot >= GP_SLOTS) return "-";
    if (slot < GP_IN_BUTTONS) return SDL_GameControllerGetStringForButton((SDL_GameControllerButton)slot);
    const int ax = (slot < GP_IN_AX_HI) ? slot - GP_IN_AX_LO : slot - GP_IN_AX_HI;
    const int hi = slot >= GP_IN_AX_HI;
    static char nm[8][24];
    static int nmi = 0;
    const char *base = SDL_GameControllerGetStringForAxis((SDL_GameControllerAxis)ax);
    snprintf(nm[nmi], sizeof(nm[nmi]), "%s %s", base ? base : "axis", hi ? "+" : "-");
    const char *out = nm[nmi];
    nmi = (nmi + 1) & 7;                    /* a few live at once is enough for one row */
    return out;
}

/* ---- reading the controller through the remap ----------------------------- */

static int gp_axis_raw(SDL_GameController *c, SDL_GameControllerAxis a)
{
    return SDL_GameControllerGetAxis(c, a);
}

/* The controller's buttons through the remap table. */
static uint32_t gp_buttons(SDL_GameController *c)
{
    uint32_t b = 0;
    if (!c) return 0;
    gp_map_ensure();
    for (int i = 0; i < GP_IN_BUTTONS; i++) {
        if (!SDL_GameControllerGetButton(c, (SDL_GameControllerButton)i)) continue;
        if (g_gp_map[i] >= 0) b |= 1u << g_gp_map[i];
    }
    for (int a = 0; a < SDL_CONTROLLER_AXIS_MAX; a++) {
        const int v = gp_axis_raw(c, (SDL_GameControllerAxis)a);
        if (v < -24000 && g_gp_map[GP_IN_AX_LO + a] >= 0) b |= 1u << g_gp_map[GP_IN_AX_LO + a];
        if (v > 24000 && g_gp_map[GP_IN_AX_HI + a] >= 0) b |= 1u << g_gp_map[GP_IN_AX_HI + a];
    }
    return b;
}

/* The sticks: the left one as SDL reports it, the right one as the user chose.
 * On a controller whose triggers stand in for the stick, they still reach
 * ZL / ZR, because gp_buttons reads them from the table rather than the stick. */
static void gp_sticks(SDL_GameController *c, int *lx, int *ly, int *rx, int *ry)
{
    *lx = *ly = *rx = *ry = 0;
    if (!c) return;
    *lx = clamp_stick(gp_axis_raw(c, SDL_CONTROLLER_AXIS_LEFTX));
    *ly = -clamp_stick(gp_axis_raw(c, SDL_CONTROLLER_AXIS_LEFTY));   /* SDL's y is down */
    switch (g_gp_right) {
    case GP_RIGHT_TRIGGERS: {
        const int lt = gp_axis_raw(c, SDL_CONTROLLER_AXIS_TRIGGERLEFT);
        const int rt = gp_axis_raw(c, SDL_CONTROLLER_AXIS_TRIGGERRIGHT);
        *rx = clamp_stick(rt - lt);
        *ry = 0;
        break;
    }
    case GP_RIGHT_INVERT:
        *rx = clamp_stick(gp_axis_raw(c, SDL_CONTROLLER_AXIS_RIGHTX));
        *ry = clamp_stick(gp_axis_raw(c, SDL_CONTROLLER_AXIS_RIGHTY));   /* not negated */
        break;
    default:
        *rx = clamp_stick(gp_axis_raw(c, SDL_CONTROLLER_AXIS_RIGHTX));
        *ry = -clamp_stick(gp_axis_raw(c, SDL_CONTROLLER_AXIS_RIGHTY));
        break;
    }
}

/* ---- the page -------------------------------------------------------------- */

enum { H_GP_NONE = 0, H_GP_CLOSE, H_GP_REMAP, H_GP_RIGHT, H_GP_RUMBLE, H_GP_RUMBLE_TEST, H_GP_MAP_RESET };

static int g_gp_remap_bit = -1;            /* the Switch button waiting for a press */
static uint32_t g_gp_shown_buttons = 0;    /* the test line's own view of the pad */
static int g_gp_slx = 0, g_gp_sly = 0, g_gp_srx = 0, g_gp_sry = 0;

typedef struct { SDL_FRect r; int id, arg; } gp_hit_t;
#define GP_HITS 64
static gp_hit_t g_gp_hits[GP_HITS];
static int g_gp_nhits;

static void gp_page_open(void)
{
    gp_map_ensure();
    g_gp_page = 1;
    g_gp_remap_bit = -1;
    input_release_all();                   /* nothing reaches the console while this is open */
}
static void gp_page_close(void) { g_gp_page = 0; g_gp_remap_bit = -1; }

static void gp_hit(float x, float y, float w, float h, int id, int arg)
{
    if (g_gp_nhits >= GP_HITS) return;
    g_gp_hits[g_gp_nhits].r.x = x; g_gp_hits[g_gp_nhits].r.y = y;
    g_gp_hits[g_gp_nhits].r.w = w; g_gp_hits[g_gp_nhits].r.h = h;
    g_gp_hits[g_gp_nhits].id = id;
    g_gp_hits[g_gp_nhits].arg = arg;
    g_gp_nhits++;
}

static int gp_hover(float x, float y, float w, float h)
{
    return g_ui_mx >= x && g_ui_mx < x + w && g_ui_my >= y && g_ui_my < y + h;
}

/* the page takes the controller's presses while a remap is waiting */
static void gp_page_poll(void)
{
    if (!g_gp_page) return;
    SDL_GameControllerUpdate();
    g_gp_shown_buttons = g_ctrl ? gp_buttons(g_ctrl) : 0;
    gp_sticks(g_ctrl, &g_gp_slx, &g_gp_sly, &g_gp_srx, &g_gp_sry);
    if (g_gp_remap_bit < 0 || !g_ctrl) return;
    /* the first input that is not already this button's own: that is the new one */
    const int own = gp_slot_for_bit(g_gp_remap_bit);
    for (int i = 0; i < GP_IN_BUTTONS; i++) {
        if (i == own) continue;
        if (!SDL_GameControllerGetButton(g_ctrl, (SDL_GameControllerButton)i)) continue;
        g_gp_map[i] = (int8_t)g_gp_remap_bit;
        if (own >= 0) g_gp_map[own] = -1;
        g_gp_remap_bit = -1;
        prefs_save();
        return;
    }
    for (int a = 0; a < SDL_CONTROLLER_AXIS_MAX; a++) {
        const int v = gp_axis_raw(g_ctrl, (SDL_GameControllerAxis)a);
        const int slot = v < -24000 ? GP_IN_AX_LO + a : v > 24000 ? GP_IN_AX_HI + a : -1;
        if (slot < 0 || slot == own) continue;
        g_gp_map[slot] = (int8_t)g_gp_remap_bit;
        if (own >= 0) g_gp_map[own] = -1;
        g_gp_remap_bit = -1;
        prefs_save();
        return;
    }
}

/* a short buzz on both motors, so the user can tell whether it works */
static void gp_rumble_test(void)
{
    if (!g_ctrl) return;
    SDL_GameControllerRumble(g_ctrl, 0xC000, 0xC000, 400);
}

#define GP_PANEL_W 460.f

/* A card over the game, like the settings drawer's popouts. */
static void gp_page_draw(SDL_Renderer *ren)
{
    float vw, vh;
    vpad_view(ren, &vw, &vh);
    text_init(ren);
    SDL_BlendMode old;
    SDL_GetRenderDrawBlendMode(ren, &old);
    SDL_SetRenderDrawBlendMode(ren, SDL_BLENDMODE_BLEND);
    ui_rrect(ren, 0, 0, vw, vh, 0, (SDL_Color){ 0, 0, 0, 150 });

    g_gp_nhits = 0;
    const float pw = GP_PANEL_W < vw - 40.f ? GP_PANEL_W : vw - 40.f;
    const float ph = vh - 40.f, x0 = (vw - pw) * 0.5f, y0 = 20.f;
    ui_rrect(ren, x0, y0, pw, ph, 14.f, UI_BG);

    const float x = x0 + 20.f, cw = pw - 40.f;
    float y = y0 + 16.f;
    text_draw(ren, "Gamepad", (int)x, (int)y, 20, 1, UI_INK, 0);
    {   /* the close button */
        const float d = 30.f, bx = x0 + pw - d - 14.f, by = y0 + 12.f;
        ui_rrect(ren, bx, by, d, d, 8.f, UI_SURF3);
        text_draw(ren, "X", (int)(bx + d * 0.5f), (int)(by + d * 0.5f - 7.f), 14, 1, UI_INK, 1);
        gp_hit(bx, by, d, d, H_GP_CLOSE, 0);
    }
    y += 30.f;

    const char *nm = g_ctrl ? SDL_GameControllerName(g_ctrl) : NULL;
    const int have = nm && *nm;
    text_draw(ren, have ? nm : "No gamepad connected", (int)x, (int)y, 13, 0, have ? UI_GOOD : UI_DIM, 0);
    y += 20.f;
    if (!have) {
        text_draw(ren, "Pair one over Bluetooth or plug it in by USB.", (int)x, (int)y, 12, 0, UI_FAINT, 0);
        y += 18.f;
    }

    /* what it sends right now */
    ve_section(ren, x, &y, "TEST");
    {
        char line[400];
        size_t n = 0;
        for (int i = 0; i < GP_BITS; i++)
            if (g_gp_shown_buttons & (1u << gp_bits[i]))
                n += (size_t)snprintf(line + n, n < sizeof(line) ? sizeof(line) - n : 0, "%s%s",
                                      n ? "  " : "", gp_bit_name(gp_bits[i]));
        if (!n) snprintf(line, sizeof(line), "Press anything: what shows here is what the Switch gets.");
        text_draw(ren, line, (int)x, (int)y, 13, 1, n ? UI_ACC : UI_FAINT, 0);
        y += 20.f;
        char st[120];
        snprintf(st, sizeof(st), "L %d,%d   R %d,%d", g_gp_slx, g_gp_sly, g_gp_srx, g_gp_sry);
        text_draw(ren, st, (int)x, (int)y, 12, 0, UI_DIM, 0);
        y += 18.f;
        text_draw(ren, "Nothing reaches the Switch while this page is open.", (int)x, (int)y, 11, 0, UI_FAINT, 0);
        y += 22.f;
    }

    /* the remap table, two columns */
    ve_section(ren, x, &y, "REMAP");
    text_draw(ren, "Select a Switch button, then press the gamepad button that should be it.",
              (int)x, (int)y, 12, 0, UI_DIM, 0);
    y += 22.f;
    {
        const float rh = 26.f, colw = cw * 0.5f;
        for (int k = 0; k < GP_BITS; k++) {
            const int half = k & 1, row = k >> 1;
            const float bx = x + half * colw, bw = colw - 6.f, by = y + row * (rh + 4.f);
            const int waiting = g_gp_remap_bit == gp_bits[k];
            const int slot = gp_slot_for_bit(gp_bits[k]);
            ui_rrect(ren, bx, by, bw, rh, 7.f,
                     waiting ? UI_ACC : (gp_hover(bx, by, bw, rh) ? UI_SURF3 : UI_SURF2));
            text_draw(ren, gp_bit_name(gp_bits[k]), (int)(bx + 8), (int)(by + 5), 12, 0,
                      waiting ? (SDL_Color){ 8, 20, 30, 255 } : UI_INK, 0);
            text_draw(ren, waiting ? "press..." : slot >= 0 ? gp_input_name(slot) : "-",
                      (int)(bx + bw - 8), (int)(by + 5), 12, 1,
                      waiting ? (SDL_Color){ 8, 20, 30, 255 } : UI_DIM, 2);
            gp_hit(bx, by, bw, rh, H_GP_REMAP, gp_bits[k]);
        }
        y += ((GP_BITS + 1) / 2) * 30.f + 6.f;
    }
    ui_button(ren, x + cw, 0, y, "Reset the mapping", UI_SURF2, UI_INK, H_GP_MAP_RESET, 0, 1);
    y += 38.f;

    ve_section(ren, x, &y, "RIGHT STICK AXES");
    text_draw(ren, "Auto suits most gamepads. Try the others if it moves the wrong way.",
              (int)x, (int)y, 12, 0, UI_DIM, 0);
    y += 20.f;
    ui_seg(ren, x + cw, 0, y, gp_right_names, 3, g_gp_right, H_GP_RIGHT, 12, cw);
    y += 38.f;

    ve_section(ren, x, &y, "VIBRATION");
    {
        const float rh = 30.f;
        ui_rrect(ren, x, y, cw, rh, 8.f, UI_SURF2);
        text_draw(ren, "Gamepad vibration", (int)(x + 10), (int)(y + 7), 13, 0, UI_INK, 0);
        ui_toggle(ren, x + cw - 10, y + 3, g_pad_rumble_on, H_GP_RUMBLE, 1);
        gp_hit(x, y, cw, rh, H_GP_RUMBLE, !g_pad_rumble_on);
        y += rh + 6.f;
        ui_button(ren, x + cw, 0, y, "Test vibration", UI_SURF3, UI_INK, H_GP_RUMBLE_TEST, 0, g_ctrl != NULL);
        y += 36.f;
    }
    (void)ph;
    SDL_SetRenderDrawBlendMode(ren, old);
}

/* The page takes the pointer and the keys while it is open. */
static int gp_page_event(SDL_Renderer *ren, const SDL_Event *e)
{
    if (!g_gp_page) return 0;
    if (e->type == SDL_KEYDOWN && e->key.keysym.sym == SDLK_ESCAPE) { gp_page_close(); return 1; }
    if (e->type != SDL_MOUSEBUTTONDOWN || e->button.button != SDL_BUTTON_LEFT)
        return e->type == SDL_KEYDOWN || e->type == SDL_MOUSEMOTION || e->type == SDL_MOUSEWHEEL;
    float lx = (float)e->button.x, ly = (float)e->button.y;
    if (ren) SDL_RenderWindowToLogical(ren, e->button.x, e->button.y, &lx, &ly);
    for (int i = g_gp_nhits - 1; i >= 0; i--) {
        const gp_hit_t *h = &g_gp_hits[i];
        if (!(lx >= h->r.x && lx < h->r.x + h->r.w && ly >= h->r.y && ly < h->r.y + h->r.h)) continue;
        switch (h->id) {
        case H_GP_CLOSE: gp_page_close(); break;
        case H_GP_REMAP: g_gp_remap_bit = (g_gp_remap_bit == h->arg) ? -1 : h->arg; break;
        case H_GP_RIGHT: g_gp_right = h->arg; prefs_save(); break;
        case H_GP_RUMBLE: g_pad_rumble_on = !g_pad_rumble_on; prefs_save(); break;
        case H_GP_RUMBLE_TEST: gp_rumble_test(); break;
        case H_GP_MAP_RESET: gp_map_defaults(); prefs_save(); break;
        default: break;
        }
        return 1;
    }
    return 1;                                   /* the page eats the click either way */
}
