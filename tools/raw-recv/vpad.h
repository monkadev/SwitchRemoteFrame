/* vpad.h - v1.0.2: the on-screen gamepad - a Pro Controller drawn over the
 * game, the PC port of the Android app's VirtualPad.
 *
 * vpad_layout.h says which controls there are, where and how big; this draws
 * them, takes the pointer, and reports what they hold. A PC has one pointer
 * where Android has fingers, so each mouse button is a finger of its own: the
 * left button can hold the stick while the right holds A, and the editor uses
 * the middle button to drag a control while another stays pressed.
 *
 * What misses every control is left alone, so it still reaches the picture -
 * the Switch's touch screen, with mouse/touch control on - exactly as a finger
 * between the Android controls falls through to the video.
 */
#pragma once

#include "vpad_layout.h"

/* Show the on-screen gamepad: never / when no gamepad is connected / always.
 * "Auto" is the Android app's default: useful on a touch screen, out of the
 * way when a real controller is in the hand. */
enum { VPAD_OFF = 0, VPAD_AUTO = 1, VPAD_ALWAYS = 2 };
static const char *const vpad_mode_names[3] = { "Off", "Auto", "Always" };

/* The editor's opacity choices, as the Android app has them. */
static const float vpad_opacities[4] = { 0.25f, 0.5f, 0.75f, 1.f };

static int g_vpad_mode = VPAD_AUTO;
static float g_vpad_opacity = 0.5f;
static vp_layout_t g_vpad_layout;
static int g_vpad_hidden = 0;          /* the bottom-left button hid the controls */
static int g_vpad_used = 0;            /* it held something recently: the HUD says "playing" */

/* What the controls hold right now. */
static uint32_t g_vpad_buttons = 0;
static int g_vpad_lx = 0, g_vpad_ly = 0, g_vpad_rx = 0, g_vpad_ry = 0;

/* Per-control pointer state: which mouse button is on it. A PC mouse has three
 * buttons, so three controls can be held at once (a stick, a face button and
 * the editor's drag). */
typedef struct { int mb; float px, py; int moved; } vp_touch_t;
static vp_touch_t g_vpad_touch[VP_N];

/* The corner button, and where the last paint put every control.
 * v1.0.3: the editor's own pencil is gone from the pad - the layout is edited
 * from Settings - so the pad's only corner button is show/hide. */
static SDL_FRect g_vpad_box[VP_N];
static SDL_FRect g_vpad_toggle_r;
static int g_vpad_placed = 0;

static void vpad_release(void);

/* ---- the picture the controls are drawn on ------------------------------- */

static void vpad_view(SDL_Renderer *ren, float *w, float *h)
{
    int lw = 0, lh = 0;
    if (ren) SDL_RenderGetLogicalSize(ren, &lw, &lh);
    if (lw <= 0) lw = 1280;
    if (lh <= 0) lh = 720;
    *w = (float)lw;
    *h = (float)lh;
}

/* "Auto" only shows it while no controller is plugged in - the Android app's
 * rule, with the PC's SDL controller in place of its Bluetooth gamepads. */
static int vpad_shown(int pad_connected)
{
    if (g_vpad_mode == VPAD_OFF || g_vpad_hidden) return 0;
    if (g_vpad_mode == VPAD_AUTO && pad_connected) return 0;
    return 1;
}

/* The pad is wanted on the screen - its Show/Hide button belongs there whether
 * or not the controls are currently hidden. The Android app's rule: Off hides
 * everything, Auto with a real gamepad near hides everything, and the user's
 * own Hide hides only the controls, never the way back. */
static int vpad_wanted(int pad_connected)
{
    if (g_vpad_mode == VPAD_OFF) return 0;
    if (g_vpad_mode == VPAD_AUTO && pad_connected) return 0;
    return 1;
}

/* ---- what a control looks like ------------------------------------------- */

/* the corner radius / geometry of one control, in picture units */
static void vpad_box(const vp_item_t *it, float w, float h, SDL_FRect *out)
{
    const float cw = vp_width_dp(it->id) * it->scale;
    const float ch = vp_height_dp(it->id) * it->scale;
    float x = it->x * w - cw * 0.5f;
    float y = it->y * h - ch * 0.5f;
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    if (x > w - cw) x = w - cw;
    if (y > h - ch) y = h - ch;
    out->x = x; out->y = y; out->w = cw; out->h = ch;
}

static void vp_disc(SDL_Renderer *ren, float cx, float cy, float r, SDL_Color c)
{
    if (r <= 0) return;
    ui_disc_init(ren);
    if (!g_ui_disc) return;
    SDL_SetTextureColorMod(g_ui_disc, c.r, c.g, c.b);
    SDL_SetTextureAlphaMod(g_ui_disc, c.a);
    const SDL_FRect d = { cx - r, cy - r, r * 2, r * 2 };
    SDL_RenderCopyF(ren, g_ui_disc, NULL, &d);
}

/* a ring: the disc texture cannot do one, so draw it as short chords */
static void vp_ring(SDL_Renderer *ren, float cx, float cy, float r, float thick, SDL_Color c)
{
    if (r <= 0 || thick <= 0) return;
    const int steps = 72;
    SDL_SetRenderDrawColor(ren, c.r, c.g, c.b, c.a);
    for (int i = 0; i < steps; i++) {
        const float a0 = (float)i / steps * 6.2831853f, a1 = (float)(i + 1) / steps * 6.2831853f;
        const float mid = (a0 + a1) * 0.5f;
        SDL_FRect s = { cx + cosf(mid) * (r - thick * 0.5f) - thick * 0.6f,
                        cy + sinf(mid) * (r - thick * 0.5f) - thick * 0.6f,
                        thick * 1.2f, thick * 1.2f };
        SDL_RenderFillRectF(ren, &s);
    }
}

static SDL_Color vp_ink(float a) { return (SDL_Color){ UI_INK.r, UI_INK.g, UI_INK.b, (Uint8)(255 * a) }; }
static SDL_Color vp_fill(float a, int held)
{
    /* held: the Switch's own blue, so it is obvious what reached the console */
    if (held) return (SDL_Color){ UI_ACC.r, UI_ACC.g, UI_ACC.b, (Uint8)(235 * a) };
    return (SDL_Color){ 0, 0, 0, (Uint8)(115 * a) };
}

/* ---- drawing ------------------------------------------------------------- */

static void vpad_draw_control(SDL_Renderer *ren, int i, float alpha)
{
    const vp_item_t *it = &g_vpad_layout.items[i];
    if (!it->shown) return;
    const SDL_FRect b = g_vpad_box[i];
    const float cx = b.x + b.w * 0.5f, cy = b.y + b.h * 0.5f;
    const vp_touch_t *t = &g_vpad_touch[i];
    const int held = t->mb > 0;
    const int bit = vp_button(it->id);
    const int lit = held || (bit >= 0 && (g_vpad_buttons & (1u << bit)));

    switch (vp_kind(it->id)) {
    case VP_STICK: {
        const float r = b.w * 0.5f;
        vp_disc(ren, cx, cy, r, vp_fill(alpha, held));
        vp_ring(ren, cx, cy, r - 2.f, 2.f, (SDL_Color){ 255, 255, 255, (Uint8)(70 * alpha) });
        /* the knob follows the pointer, as the Android control's does */
        float kx = cx, ky = cy;
        if (held) {
            float dx = t->px - cx, dy = t->py - cy;
            const float len = sqrtf(dx * dx + dy * dy), lim = r * 0.62f;
            if (len > lim && len > 0) { dx = dx / len * lim; dy = dy / len * lim; }
            kx = cx + dx; ky = cy + dy;
        }
        vp_disc(ren, kx, ky, r * 0.44f, held ? (SDL_Color){ UI_ACC.r, UI_ACC.g, UI_ACC.b, (Uint8)(245 * alpha) }
                                            : (SDL_Color){ UI_DIM.r, UI_DIM.g, UI_DIM.b, (Uint8)(210 * alpha) });
        text_draw(ren, vp_label(it->id), (int)cx, (int)(b.y - 16.f), 12, 0,
                  (SDL_Color){ UI_INK.r, UI_INK.g, UI_INK.b, (Uint8)(150 * alpha) }, 1);
        break;
    }
    case VP_DPAD: {
        const float r = b.w * 0.5f;
        const uint32_t d = g_vpad_buttons & ((1u << VPB_UP) | (1u << VPB_DOWN) | (1u << VPB_LEFT) | (1u << VPB_RIGHT));
        /* four blades; the held ones light */
        const int up = (d & (1u << VPB_UP)) != 0, dn = (d & (1u << VPB_DOWN)) != 0,
                  lf = (d & (1u << VPB_LEFT)) != 0, rt = (d & (1u << VPB_RIGHT)) != 0;
        const float arm = r, th = r * 0.42f;
        ui_rrect(ren, cx - th * 0.5f, cy - arm, th, arm * 2, th * 0.3f,
                 (SDL_Color){ 0, 0, 0, (Uint8)(115 * alpha) });
        ui_rrect(ren, cx - arm, cy - th * 0.5f, arm * 2, th, th * 0.3f,
                 (SDL_Color){ 0, 0, 0, (Uint8)(115 * alpha) });
        if (up) ui_rrect(ren, cx - th * 0.5f, cy - arm, th, arm, th * 0.3f, (SDL_Color){ UI_ACC.r, UI_ACC.g, UI_ACC.b, (Uint8)(235 * alpha) });
        if (dn) ui_rrect(ren, cx - th * 0.5f, cy, th, arm, th * 0.3f, (SDL_Color){ UI_ACC.r, UI_ACC.g, UI_ACC.b, (Uint8)(235 * alpha) });
        if (lf) ui_rrect(ren, cx - arm, cy - th * 0.5f, arm, th, th * 0.3f, (SDL_Color){ UI_ACC.r, UI_ACC.g, UI_ACC.b, (Uint8)(235 * alpha) });
        if (rt) ui_rrect(ren, cx, cy - th * 0.5f, arm, th, th * 0.3f, (SDL_Color){ UI_ACC.r, UI_ACC.g, UI_ACC.b, (Uint8)(235 * alpha) });
        vp_disc(ren, cx, cy, th * 0.3f, (SDL_Color){ 255, 255, 255, (Uint8)(40 * alpha) });
        break;
    }
    case VP_ROUND:
    case VP_SMALL: {
        const float r = b.w * 0.5f;
        vp_disc(ren, cx, cy, r, vp_fill(alpha, lit));
        vp_ring(ren, cx, cy, r - 1.5f, 1.5f, (SDL_Color){ 255, 255, 255, (Uint8)(60 * alpha) });
        text_draw(ren, vp_label(it->id), (int)cx, (int)(cy - (vp_kind(it->id) == VP_SMALL ? 7.f : 9.f)),
                  vp_kind(it->id) == VP_SMALL ? 13 : 17, 1, vp_ink(alpha), 1);
        break;
    }
    case VP_SHOULDER: {
        ui_rrect(ren, b.x, b.y, b.w, b.h, b.h * 0.34f, vp_fill(alpha, lit));
        text_draw(ren, vp_label(it->id), (int)cx, (int)(cy - 8.f), 15, 1, vp_ink(alpha), 1);
        break;
    }
    default: break;
    }
}

static void vpad_place(SDL_Renderer *ren)
{
    vp_layout_init(&g_vpad_layout);
    float w, h;
    vpad_view(ren, &w, &h);
    for (int i = 0; i < VP_N; i++) vpad_box(&g_vpad_layout.items[i], w, h, &g_vpad_box[i]);
    /* the one corner button, bottom-left where the Android app has it - wide
     * enough for the arrow and the word beside it */
    const float bw = 78.f, bh = 34.f;
    g_vpad_toggle_r.x = 8.f; g_vpad_toggle_r.y = h - bh - 8.f;
    g_vpad_toggle_r.w = bw; g_vpad_toggle_r.h = bh;
    g_vpad_placed = 1;
}

/* The controls, over the picture. Drawn after the frame and before the chrome,
 * so the gear and the settings drawer stay on top. */
/* The one corner button: an arrow, and the word for what it does, so it does
 * not have to be guessed (the Android app's chevron alone reads as nothing).
 *
 * Drawn on its own rather than as part of vpad_draw, because hiding the pad is
 * the one state where this button is the only thing on the screen - a pad you
 * hid with a button you cannot see again has no way back. */
/* The pad's corner button: a little controller glyph + the word. Returns 1
 * when it painted anything. v1.0.5: the chevron is now a controller icon
 * (the same glyph as the Android pill), and while the pad is away the Show
 * button fades out 2 s after the mouse stops - any movement brings it back.
 * The pad itself is never touched, only the button that summons it. */
static int vpad_toggle_button(SDL_Renderer *ren, SDL_BlendMode restore)
{
    const SDL_FRect b = g_vpad_toggle_r;
    const char *word = g_vpad_hidden ? "Show" : "Hide";
    /* Semi-transparent either way, and more so while the pad is away: the button
     * is there to be found, not to be looked at. */
    SDL_Color ink = g_vpad_hidden ? (SDL_Color){ UI_FAINT.r, UI_FAINT.g, UI_FAINT.b, 185 } : vp_ink(0.9f);
    int bg_a = g_vpad_hidden ? 74 : 130;
    if (g_vpad_hidden) {
        const float fade = ui_idle_alpha();
        ink.a = (Uint8)(ink.a * fade);
        bg_a = (int)(bg_a * fade + 0.5f);
        if (ink.a == 0 && bg_a == 0) return 0;      /* fully faded: nothing to paint */
    }
    SDL_SetRenderDrawBlendMode(ren, SDL_BLENDMODE_BLEND);
    ui_rrect(ren, b.x, b.y, b.w, b.h, 10.f, (SDL_Color){ 0, 0, 0, (Uint8)bg_a });
    const float cy = b.y + b.h * 0.5f, ax = b.x + 16.f;
    /* the controller glyph: rounded body, d-pad cross, two face buttons */
    SDL_SetRenderDrawColor(ren, ink.r, ink.g, ink.b, (Uint8)(ink.a * 45 / 100));
    { const SDL_FRect body = { ax - 8.f, cy - 5.5f, 16.f, 11.f }; SDL_RenderFillRectF(ren, &body); }
    SDL_SetRenderDrawColor(ren, ink.r, ink.g, ink.b, ink.a);
    { const SDL_FRect h = { ax - 6.5f, cy - 0.75f, 5.f, 1.5f }; SDL_RenderFillRectF(ren, &h); }
    { const SDL_FRect v = { ax - 4.75f, cy - 2.5f, 1.5f, 5.f }; SDL_RenderFillRectF(ren, &v); }
    { const SDL_FRect d1 = { ax + 1.75f, cy + 0.5f, 1.5f, 1.5f }; SDL_RenderFillRectF(ren, &d1); }
    { const SDL_FRect d2 = { ax + 4.25f, cy - 2.f, 1.5f, 1.5f }; SDL_RenderFillRectF(ren, &d2); }
    text_draw(ren, word, (int)(ax + 14.f), (int)(cy - 7.f), 13, 1, ink, 0);
    SDL_SetRenderDrawBlendMode(ren, restore);
    return 1;
}

static void vpad_draw(SDL_Renderer *ren)
{
    if (!g_vpad_placed) vpad_place(ren);
    text_init(ren);
    SDL_BlendMode old;
    SDL_GetRenderDrawBlendMode(ren, &old);
    SDL_SetRenderDrawBlendMode(ren, SDL_BLENDMODE_BLEND);
    const float a = g_vpad_opacity;
    for (int i = 0; i < VP_N; i++) vpad_draw_control(ren, i, a);
    vpad_toggle_button(ren, old);
    SDL_SetRenderDrawBlendMode(ren, old);
}

/* ---- what the controls hold ---------------------------------------------- */

static void vpad_recompute(void)
{
    uint32_t b = 0;
    int lx = 0, ly = 0, rx = 0, ry = 0;
    if (!g_vpad_placed) return;         /* nothing has been drawn yet: nothing to hold */
    for (int i = 0; i < VP_N; i++) {
        const vp_item_t *it = &g_vpad_layout.items[i];
        const vp_touch_t *t = &g_vpad_touch[i];
        if (!it->shown || t->mb <= 0) continue;
        const SDL_FRect bx = g_vpad_box[i];
        const float cx = bx.x + bx.w * 0.5f, cy = bx.y + bx.h * 0.5f;
        switch (vp_kind(it->id)) {
        case VP_STICK: {
            int sx = 0, sy = 0;
            vp_stick(t->px - cx, t->py - cy, bx.w * 0.5f, &sx, &sy);
            if (!strcmp(it->id, "ls")) { lx = sx; ly = sy; }
            else { rx = sx; ry = sy; }
            break;
        }
        case VP_DPAD:
            b |= vp_dpad(t->px - cx, t->py - cy, bx.w * 0.5f);
            break;
        default: {
            const int bit = vp_button(it->id);
            if (bit >= 0) b |= 1u << bit;
            break;
        }
        }
    }
    g_vpad_buttons = b;
    g_vpad_lx = lx; g_vpad_ly = ly; g_vpad_rx = rx; g_vpad_ry = ry;
    if (b || lx || ly || rx || ry) g_vpad_used = 1;
}

/* let go of everything (the controls hide, Settings opens, the game ends) */
static void vpad_release(void)
{
    for (int i = 0; i < VP_N; i++) g_vpad_touch[i].mb = 0;
    g_vpad_buttons = 0;
    g_vpad_lx = g_vpad_ly = g_vpad_rx = g_vpad_ry = 0;
}

/* ---- the pointer ---------------------------------------------------------- */

/* window pixels -> the picture's own units, so the controls answer wherever the
 * window is (fullscreen, letterboxed, HiDPI) */
static void vpad_to_view(SDL_Renderer *ren, int wx, int wy, float *vx, float *vy)
{
    float lx = (float)wx, ly = (float)wy;
    /* SDL maps the window's pixels into the logical space the picture is drawn
     * in (letterboxing, fullscreen and HiDPI included), which is the space the
     * controls were placed in. */
    if (ren) SDL_RenderWindowToLogical(ren, wx, wy, &lx, &ly);
    *vx = lx;
    *vy = ly;
}

static int vpad_in(const SDL_FRect *r, float x, float y)
{
    return x >= r->x && x < r->x + r->w && y >= r->y && y < r->y + r->h;
}

/* which control a point is on (-1: none). The sticks and the D-pad take their
 * whole box; the round ones their disc, so the corners between them still reach
 * the picture. */
static int vpad_hit(float vx, float vy)
{
    for (int i = VP_N - 1; i >= 0; i--) {
        const vp_item_t *it = &g_vpad_layout.items[i];
        if (!it->shown) continue;
        const SDL_FRect *b = &g_vpad_box[i];
        if (!vpad_in(b, vx, vy)) continue;
        if (vp_kind(it->id) == VP_ROUND || vp_kind(it->id) == VP_SMALL) {
            const float cx = b->x + b->w * 0.5f, cy = b->y + b->h * 0.5f;
            const float dx = vx - cx, dy = vy - cy, r = b->w * 0.5f;
            if (dx * dx + dy * dy > r * r) continue;
        }
        return i;
    }
    return -1;
}

/* The controls take a mouse button; 0 means they did not, so the caller goes
 * on to the picture (the Switch's touch screen) or the window. */
static int vpad_event(SDL_Renderer *ren, const SDL_Event *e)
{
    if (g_vpad_mode == VPAD_OFF) return 0;
    if (!g_vpad_placed) vpad_place(ren);
    if (e->type == SDL_MOUSEBUTTONDOWN || e->type == SDL_MOUSEBUTTONUP || e->type == SDL_MOUSEMOTION) {
        int wx = 0, wy = 0;
        if (e->type == SDL_MOUSEMOTION) { wx = e->motion.x; wy = e->motion.y; }
        else { wx = e->button.x; wy = e->button.y; }
        float vx, vy;
        vpad_to_view(ren, wx, wy, &vx, &vy);
        if (e->type == SDL_MOUSEBUTTONDOWN) {
            const int mb = (int)e->button.button;
            if (mb < SDL_BUTTON_LEFT || mb > SDL_BUTTON_MIDDLE) return 0;
            if (vpad_in(&g_vpad_toggle_r, vx, vy)) {
                g_vpad_hidden = !g_vpad_hidden;
                vpad_release();
                return 1;
            }
            if (g_vpad_hidden) return 0;
            const int i = vpad_hit(vx, vy);
            if (i < 0) return 0;                       /* between the controls: the picture's */
            for (int k = 0; k < VP_N; k++) if (g_vpad_touch[k].mb == mb) g_vpad_touch[k].mb = 0;
            g_vpad_touch[i].mb = mb;
            g_vpad_touch[i].px = vx;
            g_vpad_touch[i].py = vy;
            vpad_recompute();
            return 1;
        }
        if (e->type == SDL_MOUSEBUTTONUP) {
            const int mb = (int)e->button.button;
            int any = 0;
            for (int k = 0; k < VP_N; k++) if (g_vpad_touch[k].mb == mb) { g_vpad_touch[k].mb = 0; any = 1; }
            if (!any) return 0;
            vpad_recompute();
            return 1;
        }
        /* motion: only the buttons already down on a control matter */
        const Uint32 mask = e->motion.state & (SDL_BUTTON_LMASK | SDL_BUTTON_RMASK | SDL_BUTTON_MMASK);
        if (!mask) return 0;
        int any = 0;
        for (int k = 0; k < VP_N; k++) {
            if (g_vpad_touch[k].mb <= 0) continue;
            g_vpad_touch[k].px = vx;
            g_vpad_touch[k].py = vy;
            g_vpad_touch[k].moved = 1;
            any = 1;
        }
        if (!any) return 0;
        vpad_recompute();
        return 1;
    }
    return 0;
}
