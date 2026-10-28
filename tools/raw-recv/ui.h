/* ui.h - v0.7.12: the viewer's look. One home screen while there is no
 * picture, and one settings drawer (Esc, or the Settings button) with every
 * option in it, over the home screen or over the game - mouse or keys. The
 * old shortcuts (R, M, F11, Tab, I, O, V, A, B, U) still work; P opens the drawer at
 * the picture controls.
 *
 * Immediate mode: every draw lays the controls out again and remembers where
 * the clickable ones are; the next mouse event is matched against that.
 * Everything is in 720p units ("the height is 720"): the home screen is
 * 1280x720 logical, over the game it is scaled with the picture.
 */
#pragma once

/* ---- the theme -------------------------------------------------------- */
/* Aurora design system v2 (docs/UI-REDESIGN.md), shared token-for-token with
 * the Switch manager (manager/source/ui.hpp) and the Android app (Ui.java):
 *   BG      the page                       #0B0D11  (never true black)
 *   SURF    cards and the home panel       #14181F
 *   SURF2   raised rows, pills, inputs     #1D222C
 *   SURF3   hover, active, knobs           #282F3C
 *   SURF4   toasts, highest elevation      #363E4E
 * Depth is the ladder plus a 6% hairline (UI_HAIR) - shadows do not read on
 * dark surfaces. Text is off-white (#F2F5FA), not pure white: less halation,
 * WCAG AA on every rung. One electric-blue accent carries every action. */
static const SDL_Color UI_BG = { 11, 13, 17, 255 }, UI_SURF = { 20, 24, 31, 255 }, UI_SURF2 = { 29, 34, 44, 255 },
                       UI_SURF3 = { 40, 47, 60, 255 }, UI_SURF4 = { 54, 62, 78, 255 },
                       UI_HAIR = { 255, 255, 255, 16 }, UI_LINE = { 255, 255, 255, 16 },
                       UI_INK = { 242, 245, 250, 255 }, UI_DIM = { 172, 181, 196, 255 }, UI_FAINT = { 124, 134, 151, 255 },
                       UI_ACC = { 77, 141, 255, 255 }, UI_ACC_HOT = { 122, 168, 255, 255 },
                       UI_GOOD = { 52, 205, 153, 255 }, UI_WARN = { 245, 183, 63, 255 },
                       UI_BAD = { 242, 97, 117, 255 },
                       UI_ONACC = { 7, 12, 24, 255 },    /* text sitting on the accent */
                       UI_HEART = { 229, 72, 77, 255 };  /* the donate heart (ko-fi red) */

/* Aurora card: a raised surface with a hairline border, depth without
 * shadows. The border is a rounded hairline rrect with the fill inset by one
 * pixel - four straight 1-px fills used to overhang the rounded corners and
 * read as little brackets/ticks on every card. */
static void ui_card(SDL_Renderer *ren, float x, float y, float w, float h, float r, SDL_Color fill)
{
    if (w <= 2 || h <= 2) { ui_rrect(ren, x, y, w, h, r, fill); return; }
    ui_rrect(ren, x, y, w, h, r, UI_HAIR);
    ui_rrect(ren, x + 1, y + 1, w - 2, h - 2, r > 1 ? r - 1 : 0, fill);
}

/* A large surface without any border: the home panel and settings drawer are
 * nearly window-sized, so a hairline around them read as a thin white box on
 * the window's edge. Their fill alone separates them from the backdrop. */
static void ui_surface(SDL_Renderer *ren, float x, float y, float w, float h, float r, SDL_Color fill)
{
    ui_rrect(ren, x, y, w, h, r, fill);
}

static int g_ui_open = 0;                  /* the settings drawer */
static long g_ui_popout_paints = 0;        /* how many times the drawer was composited (flicker tests) */
static int g_ui_focus = 0, g_ui_kbd = 0;   /* the row the keys are on; shown after a key */
static float g_ui_scroll = 0, g_ui_content_h = 0;
static float g_home_scroll = 0, g_home_content_h = 0;
static float g_ui_mx = -1, g_ui_my = -1;   /* the mouse, in UI units (logical / g_ui_k) */
static float g_ui_wx = -1, g_ui_wy = -1;   /* the mouse, in window pixels (for fixed chrome) */
static Uint64 g_ui_mouse_t = 0;            /* last pointer activity inside the window */
static int g_ui_ptr_inside = 0;            /* mouse is over the window: show hover chrome */
static int g_ui_drag = 0, g_ui_drag_arg = 0;
static float g_ui_k = 1, g_ui_w = 1280;    /* units -> logical, and the width in units, at the last draw */
static int g_ui_scroll_to = -1;            /* bring this row into view at the next draw */
/* Active scrollbar geometry (for thumb dragging without a mouse wheel). */
static float g_sb_top = 0, g_sb_h = 0, g_sb_content = 0, g_sb_view = 0;
static float *g_sb_scroll_ptr = NULL;
static SDL_Window *g_ui_win = NULL;        /* for the fullscreen switch's state (app_events sets it) */
static int g_ui_hit_window = 0;            /* next ui_hit() is in window pixels (fixed chrome) */
static int g_ui_popout_map = 0;            /* ui_in: map window mouse → design-space card */
static float g_ui_popout_x0 = 0, g_ui_popout_y0 = 0, g_ui_popout_scale = 1;
/* Settings density: desktop 1.0 / tablet 0.85 / mobile 0.70. Applied by redrawing
 * at native pixels (smaller font px + control sizes) - stays sharp, never blit-scaled. */
static float g_ui_d = 1.f;

static float ui_d(float v) { return v * g_ui_d; }
static int ui_px(int px)
{
    int v = (int)((float)px * g_ui_d + 0.5f);
    return v < 11 ? 11 : v; /* floor: still readable on mobile */
}
static void ui_open(int open);

enum { H_NONE, H_SETTINGS, H_CLOSE, H_BACKDROP, H_CONN, H_IP, H_FULL, H_VSYNC, H_ASPECT, H_CHROMELESS, H_UPS, H_HUD, H_HWDEC,
       H_PIC, H_PIC_RESET, H_SOUND, H_REC, H_PANEL, H_FSR_SHARP, H_DONATE, H_SCROLL,
       H_WIN_MIN, H_WIN_MAX, H_WIN_CLOSE, H_UPD, H_UPD_BETA, H_UPD_AUTO,
       H_TOUCH, H_KB, H_PAD, H_RUMBLE,
       H_VPAD, H_VPAD_LAYOUT, H_VPAD_HIDDEN, H_GP_PAGE,   /* v1.0.2: the on-screen gamepad */
       H_CONNECT,                                         /* v1.0.3: Connect / Disconnect */
       H_IP_OK, H_IP_CANCEL,                              /* v1.1.3: IP editing buttons */
       H_SET_OP };                                        /* how see-through the Settings button is */

typedef struct { SDL_FRect r; int id, arg, window_space; } ui_hit_t;
static ui_hit_t g_ui_hits[96];
static int g_ui_nhits;
/* the drawer's rows in order, for the keys: (id, arg) */
static struct { int id, arg; float y, h; } g_ui_rows[24];
static int g_ui_nrows;

static void ui_hit(float x, float y, float w, float h, int id, int arg)
{
    if (g_ui_nhits < (int)(sizeof(g_ui_hits) / sizeof(g_ui_hits[0])))
        g_ui_hits[g_ui_nhits++] = (ui_hit_t){ { x, y, w, h }, id, arg, g_ui_hit_window };
}

static int ui_in(float x, float y, float w, float h)
{
    float mx = g_ui_mx, my = g_ui_my;
    if (g_ui_hit_window) {
        if (g_ui_popout_map && g_ui_popout_scale > 0.f) {
            mx = (g_ui_wx - g_ui_popout_x0) / g_ui_popout_scale;
            my = (g_ui_wy - g_ui_popout_y0) / g_ui_popout_scale;
        } else {
            mx = g_ui_wx;
            my = g_ui_wy;
        }
    }
    return mx >= x && mx < x + w && my >= y && my < y + h;
}

static void ui_row(int id, int arg, float y, float h)
{
    if (g_ui_nrows < (int)(sizeof(g_ui_rows) / sizeof(g_ui_rows[0]))) {
        g_ui_rows[g_ui_nrows].id = id; g_ui_rows[g_ui_nrows].arg = arg;
        g_ui_rows[g_ui_nrows].y = y; g_ui_rows[g_ui_nrows].h = h;
        g_ui_nrows++;
    }
}

static int ui_focused(int id, int arg)
{
    return g_ui_kbd && g_ui_focus < g_ui_nrows && g_ui_rows[g_ui_focus].id == id && g_ui_rows[g_ui_focus].arg == arg;
}

static SDL_Color ui_alpha(SDL_Color c, int a) { c.a = (Uint8)a; return c; }

/* text that does not fit: cut with "..." */
static void ui_text_fit(SDL_Renderer *ren, const char *s, int x, int y, int px, int bold, SDL_Color c, int align, int maxw)
{
    char b[256];
    snprintf(b, sizeof(b), "%s", s);
    if (text_width(b, px, bold) > maxw) {
        size_t n = strlen(b);
        while (n > 3 && text_width(b, px, bold) > maxw) { b[--n] = 0; b[n - 1] = '.'; b[n - 2] = '.'; b[n - 3] = '.'; }
    }
    text_draw(ren, b, x, y, px, bold, c, align);
}

/* lines broken at spaces to fit maxw; align 0 left (x is the left edge),
 * 1 centred on x; returns the height used */
static int ui_wrap(SDL_Renderer *ren, const char *s, int x, int y, int px, int bold, SDL_Color c, int maxw, int lh, int align)
{
    char line[256];
    int used = 0;
    while (*s) {
        size_t n = 0, last_space = 0;
        while (s[n] && n < sizeof(line) - 1) {
            memcpy(line, s, n + 1); line[n + 1] = 0;
            if (text_width(line, px, bold) > maxw && last_space) break;
            if (s[n] == ' ') last_space = n;
            n++;
        }
        size_t take = s[n] ? (last_space ? last_space : n) : n;
        memcpy(line, s, take); line[take] = 0;
        text_draw(ren, line, x, y + used, px, bold, c, align);
        used += lh;
        s += take;
        while (*s == ' ') s++;
    }
    return used;
}

/* ---- the controls ------------------------------------------------------ */

/* a toggle switch, its right edge at xr */
static void ui_toggle(SDL_Renderer *ren, float xr, float y, int on, int id, int enabled)
{
    const float w = ui_d(44), h = ui_d(24), x = xr - w;
    const float knob = ui_d(18), inset = ui_d(3);
    SDL_Color track = on ? UI_ACC : UI_SURF3;
    if (!enabled) track = ui_alpha(track, 90);
    ui_rrect(ren, x, y, w, h, h / 2, track);
    ui_rrect(ren, on ? x + w - knob - inset : x + inset, y + inset, knob, knob, knob / 2, enabled ? UI_INK : UI_DIM);
    if (enabled) ui_hit(x - 4, y - 6, w + 8, h + 12, id, !on);
}

/* segmented buttons, right edge at xr (or centred at cx when xr < 0).
 * maxw > 0 clamps the control so it never overlaps neighbouring content. */
static float ui_seg(SDL_Renderer *ren, float xr, float cx, float y, const char *const *opt, int n, int sel, int id, int px, float maxw)
{
    px = ui_px(px);
    float h = ui_d(32), pad = ui_d(16);
    float wsum = 0, wi[8];
    for (int i = 0; i < n && i < 8; i++) { wi[i] = text_width(opt[i], px, 1) + 2 * pad; wsum += wi[i]; }
    while (maxw > 0 && wsum + 6 > maxw && pad > 6.f) {
        pad -= 2.f;
        wsum = 0;
        for (int i = 0; i < n && i < 8; i++) { wi[i] = text_width(opt[i], px, 1) + 2 * pad; wsum += wi[i]; }
    }
    while (maxw > 0 && wsum + 6 > maxw && px > 11) {
        px -= 1;
        wsum = 0;
        for (int i = 0; i < n && i < 8; i++) { wi[i] = text_width(opt[i], px, 1) + 2 * pad; wsum += wi[i]; }
    }
    float x = xr >= 0 ? xr - wsum - 6 : cx - (wsum + 6) / 2;
    ui_rrect(ren, x, y, wsum + 6, h, ui_d(9), UI_SURF2);
    float sx = x + 3;
    for (int i = 0; i < n && i < 8; i++) {
        const int hov = ui_in(sx, y, wi[i], h);
        if (i == sel) ui_rrect(ren, sx, y + 3, wi[i], h - 6, ui_d(7), UI_ACC);
        else if (hov) ui_rrect(ren, sx, y + 3, wi[i], h - 6, ui_d(7), UI_SURF3);
        text_draw(ren, opt[i], (int)(sx + wi[i] / 2), (int)(y + (h - px * 1.18f) / 2), px, 1, i == sel ? UI_ONACC : UI_INK, 1);
        ui_hit(sx, y, wi[i], h, id, i);
        sx += wi[i];
    }
    return wsum + 6;
}

/* Vertical scrollbar when content overflows the view (mouse-wheel optional). */
static void ui_scrollbar(SDL_Renderer *ren, float track_x, float top, float view_h, float content_h, float *scroll)
{
    if (!scroll || content_h <= view_h + 1.f) { g_sb_scroll_ptr = NULL; return; }
    float maxs = content_h - view_h;
    if (*scroll < 0) *scroll = 0;
    if (*scroll > maxs) *scroll = maxs;
    const float tw = 8.f;
    float thumb_h = view_h * view_h / content_h;
    if (thumb_h < 28.f) thumb_h = 28.f;
    if (thumb_h > view_h) thumb_h = view_h;
    float thumb_y = top + (maxs > 0.f ? (*scroll / maxs) * (view_h - thumb_h) : 0.f);
    ui_rrect(ren, track_x, top, tw, view_h, 4.f, UI_SURF3);
    /* Wide hit pad so chromeless panel-drag does not steal the grab. */
    const int hov = ui_in(track_x - 10, top, tw + 20, view_h);
    ui_rrect(ren, track_x, thumb_y, tw, thumb_h, 4.f, hov || g_ui_drag == H_SCROLL ? UI_INK : UI_DIM);
    g_sb_top = top;
    g_sb_h = view_h;
    g_sb_content = content_h;
    g_sb_view = view_h;
    g_sb_scroll_ptr = scroll;
    ui_hit(track_x - 10, top, tw + 20, view_h, H_SCROLL, 0);
}

static void ui_scroll_drag(float my)
{
    if (!g_sb_scroll_ptr || g_sb_content <= g_sb_view + 1.f) return;
    float maxs = g_sb_content - g_sb_view;
    float thumb_h = g_sb_view * g_sb_view / g_sb_content;
    if (thumb_h < 28.f) thumb_h = 28.f;
    if (thumb_h > g_sb_h) thumb_h = g_sb_h;
    float span = g_sb_h - thumb_h;
    float t = span > 1.f ? (my - g_sb_top - thumb_h * 0.5f) / span : 0.f;
    if (t < 0.f) t = 0.f;
    if (t > 1.f) t = 1.f;
    *g_sb_scroll_ptr = t * maxs;
}

/* a pill button, right edge at xr (or centred at cx when xr < 0); returns its width */
static float ui_button(SDL_Renderer *ren, float xr, float cx, float y, const char *label, SDL_Color fill, SDL_Color ink, int id, int arg, int enabled)
{
    const int px = ui_px(15);
    const float w = text_width(label, px, 1) + ui_d(32), h = ui_d(32);
    const float x = xr >= 0 ? xr - w : cx - w / 2;
    SDL_Color f = fill;
    if (enabled && ui_in(x, y, w, h)) { f.r = (Uint8)(f.r + (255 - f.r) / 8); f.g = (Uint8)(f.g + (255 - f.g) / 8); f.b = (Uint8)(f.b + (255 - f.b) / 8); }
    ui_rrect(ren, x, y, w, h, h / 2, enabled ? f : ui_alpha(f, 80));
    text_draw(ren, label, (int)(x + w / 2), (int)(y + ui_d(7)), px, 1, enabled ? ink : UI_FAINT, 1);
    if (enabled) ui_hit(x, y, w, h, id, arg);
    return w;
}

/* v1.0.5: a small filled heart for the Donate button - the font atlas is
 * ASCII-only, so the glyph is vector-drawn like the gear and the gamepad:
 * two lobes and a tail that narrows to the point. */
static void ui_heart(SDL_Renderer *ren, float cx, float cy, float s, SDL_Color c)
{
    SDL_SetRenderDrawColor(ren, c.r, c.g, c.b, c.a);
    const float r = s * 0.27f;                       /* a lobe's radius */
    for (int lobe = 0; lobe < 2; lobe++) {
        const float lx = cx + (lobe ? r * 0.72f : -r * 0.72f), ly = cy - s * 0.16f;
        for (int i = 0; i <= (int)(r * 2); i++) {    /* scanline circles */
            const float dy = (float)i - r, dx = sqrtf(r * r - dy * dy);
            SDL_RenderDrawLineF(ren, lx - dx, ly + dy, lx + dx, ly + dy);
        }
    }
    const float top = cy - s * 0.02f, tip = cy + s * 0.42f;
    for (int i = 0; i <= (int)((tip - top) * 2); i++) {
        const float yy = top + (float)i * 0.5f;
        const float hw = s * 0.44f * (1.f - (yy - top) / (tip - top));
        SDL_RenderDrawLineF(ren, cx - hw, yy, cx + hw, yy);
    }
}

/* Chrome density from window width - same tiers as settings (sharp native redraw). */
static float ui_chrome_dens(int ow)
{
    if (ow >= 1100) return 1.00f;
    if (ow >= 800) return 0.85f;
    return 0.70f;
}

/* Expanded = desktop-fullscreen (F11 / middle / double-click) or maximized. */
static int ui_win_expanded(void)
{
    if (!g_ui_win) return 0;
    const Uint32 fl = SDL_GetWindowFlags(g_ui_win);
    return (fl & (SDL_WINDOW_FULLSCREEN_DESKTOP | SDL_WINDOW_MAXIMIZED)) != 0;
}

/* Chrome hit pad: visual button is d×d; hits are a few px larger so clicks land. */
static void ui_chrome_hit(float x, float y, float d, int id)
{
    const float pad = d * 0.14f;
    ui_hit(x - pad, y - pad, d + pad * 2, d + pad * 2, id, 0);
}

/* v1.0.5: the Settings button and the pad's Show button step away once the
 * pointer rests - solid for UI_IDLE_HIDE_MS after the last mouse movement,
 * then faded out over UI_IDLE_FADE_MS. Any movement brings them straight
 * back; both stay clickable either way (chrome clicks come from live
 * geometry, and moving to a button undoes the fade before the click
 * lands). The home screen and an open drawer are exempt. */
#define UI_IDLE_HIDE_MS 2000
#define UI_IDLE_FADE_MS 300
static float ui_idle_alpha(void)
{
    if (g_menu || g_ui_open) return 1.f;
    if (!g_ui_mouse_t) return 1.f;
    const Uint64 f = SDL_GetPerformanceFrequency();
    if (!f) return 1.f;
    const Uint64 ms = (SDL_GetPerformanceCounter() - g_ui_mouse_t) * 1000 / f;
    if (ms <= UI_IDLE_HIDE_MS) return 1.f;
    if (ms >= UI_IDLE_HIDE_MS + UI_IDLE_FADE_MS) return 0.f;
    return 1.f - (float)(ms - UI_IDLE_HIDE_MS) / (float)UI_IDLE_FADE_MS;
}

/* the settings button: a gear in a round button, "Settings" beside it on hover */
static void ui_gear_button(SDL_Renderer *ren, float xr, float y, float d, SDL_Color bg, int id)
{
    const float x = xr - d, inset = d * 0.19f;
    const int hov = ui_in(x, y, d, d);
    /* The whole button takes the setting's opacity, down to nothing at all if
     * that is what was asked for. Hovering brings it back to full, so a button
     * set to 0% is still found by moving the pointer to where it is. */
    int op = g_set_op < 0 ? 0 : g_set_op > 100 ? 100 : g_set_op;
    if (hov) op = 100;
    else op = (int)(op * ui_idle_alpha() + 0.5f);   /* v1.0.5: hides 2 s after the mouse stops */
    if (op <= 0) return;                            /* invisible: nothing to paint, clicks still land via ui_chrome_at */
    const float kf = (float)op / 100.f;
    SDL_Color b = bg;
    b.a = (Uint8)(bg.a * kf);
    if (hov) { b = UI_SURF3; b.a = bg.a > 230 ? bg.a : 230; }
    ui_rrect(ren, x, y, d, d, d / 2, b);
    ui_gear_init(ren);
    if (g_ui_gear) {
        SDL_SetTextureColorMod(g_ui_gear, UI_INK.r, UI_INK.g, UI_INK.b);
        SDL_SetTextureAlphaMod(g_ui_gear, (Uint8)(255 * kf));
        const SDL_FRect g = { x + inset, y + inset, d - inset * 2, d - inset * 2 };
        SDL_RenderCopyF(ren, g_ui_gear, NULL, &g);
    }
    if (hov) {
        const int px = d >= 38.f ? 14 : d >= 32.f ? 13 : 12;
        const float tw = text_width("Settings", px, 1) + d * 0.48f;
        const float th = d * 0.62f, ty = y + (d - th) * 0.5f;
        ui_rrect(ren, x - tw - d * 0.19f, ty, tw, th, th * 0.5f, UI_SURF3);
        text_draw(ren, "Settings", (int)(x - d * 0.19f - tw * 0.5f), (int)(ty + th * 0.18f), px, 1, UI_INK, 1);
    }
    ui_chrome_hit(x, y, d, id);
}

/* Borderless window controls (min / max / close), drawn left of the gear. */
static void ui_win_btn(SDL_Renderer *ren, float x, float y, float d, int id, int danger)
{
    const int hov = ui_in(x, y, d, d);
    SDL_Color b = UI_SURF2;
    if (hov) b = danger ? (SDL_Color){ 185, 28, 28, 230 } : UI_SURF3;
    ui_rrect(ren, x, y, d, d, d / 2, b);
    SDL_SetRenderDrawColor(ren, UI_INK.r, UI_INK.g, UI_INK.b, 255);
    const float cx = x + d * 0.5f, cy = y + d * 0.5f;
    const float arm = d * 0.17f, box = d * 0.33f;
    if (id == H_WIN_MIN) {
        SDL_RenderDrawLineF(ren, cx - arm, cy, cx + arm, cy);
        SDL_RenderDrawLineF(ren, cx - arm, cy + 1, cx + arm, cy + 1);
    } else if (id == H_WIN_MAX) {
        /* Restore glyph when fullscreen (F11/middle/double) or maximized. */
        if (ui_win_expanded()) {
            const float s = box * 0.78f, gap = box * 0.22f;
            SDL_FRect back = { cx - s * 0.35f, cy - s * 0.65f, s, s };
            SDL_FRect front = { cx - s * 0.65f, cy - s * 0.35f, s, s };
            (void)gap;
            SDL_RenderDrawRectF(ren, &back);
            SDL_SetRenderDrawColor(ren, b.r, b.g, b.b, b.a);
            SDL_RenderFillRectF(ren, &front);
            SDL_SetRenderDrawColor(ren, UI_INK.r, UI_INK.g, UI_INK.b, 255);
            SDL_RenderDrawRectF(ren, &front);
        } else {
            SDL_FRect r = { cx - box * 0.5f, cy - box * 0.5f, box, box };
            SDL_RenderDrawRectF(ren, &r);
        }
    } else { /* close */
        SDL_RenderDrawLineF(ren, cx - arm, cy - arm, cx + arm, cy + arm);
        SDL_RenderDrawLineF(ren, cx - arm, cy - arm + 1, cx + arm - 1, cy + arm);
        SDL_RenderDrawLineF(ren, cx + arm, cy - arm, cx - arm, cy + arm);
        SDL_RenderDrawLineF(ren, cx + arm - 1, cy - arm, cx - arm, cy + arm - 1);
    }
    ui_chrome_hit(x, y, d, id);
}

/* Layout of top-right chrome in window/output pixels. Returns button count. */
static int ui_chrome_layout(int ow, float *xs, int *ids, float *out_d, float *out_y)
{
    const float dens = ui_chrome_dens(ow);
    const float d = 42.f * dens, gap = 8.f * dens, y = 10.f * dens, margin = 10.f * dens;
    float right = (float)ow - margin;
    int n = 0;
    /* Keep min/max/close in fullscreen too - max shows restore and matches F11. */
    if (g_chromeless) {
        ids[n] = H_WIN_CLOSE; xs[n] = right - d; n++; right -= d + gap;
        ids[n] = H_WIN_MAX;   xs[n] = right - d; n++; right -= d + gap;
        ids[n] = H_WIN_MIN;   xs[n] = right - d; n++; right -= d + gap;
    }
    /* v1.0.4: the gear is always here. It got a bar to tuck into for a version
     * and the bar is gone again - a button that moves when you are not looking
     * is harder to hit than one that is merely faint, and its opacity setting
     * already covers getting it out of the way. */
    ids[n] = H_SETTINGS; xs[n] = right - d; n++;
    *out_d = d; *out_y = y;
    return n;
}

/* Hit-test chrome from geometry alone (does not depend on the last paint). */
static int ui_chrome_at(SDL_Renderer *ren, float wx, float wy, int *out_id)
{
    int ow = 0, oh = 0;
    SDL_GetRendererOutputSize(ren, &ow, &oh);
    if (ow < 80 || oh < 40) return 0;
    float xs[4], d = 42.f, y = 10.f;
    int ids[4];
    const int n = ui_chrome_layout(ow, xs, ids, &d, &y);
    const float pad = d * 0.14f;
    for (int i = 0; i < n; i++) {
        if (wx >= xs[i] - pad && wx < xs[i] + d + pad &&
            wy >= y - pad && wy < y + d + pad) {
            *out_id = ids[i];
            return 1;
        }
    }
    return 0;
}

static void ui_chrome_activate(SDL_Window *win, int id)
{
    switch (id) {
    case H_SETTINGS: ui_open(1); break;
    case H_WIN_MIN: if (win) SDL_MinimizeWindow(win); break;
    case H_WIN_MAX:
        /* Same expand/restore path as F11, double-click, and middle-click. */
        if (win) {
            const Uint32 fl = SDL_GetWindowFlags(win);
            if (fl & SDL_WINDOW_FULLSCREEN_DESKTOP) act_fullscreen(win);
            else if (fl & SDL_WINDOW_MAXIMIZED) SDL_RestoreWindow(win);
            else act_fullscreen(win);
        }
        break;
    case H_WIN_CLOSE: g_quit = 1; break;
    default: break;
    }
}

static void ui_top_chrome(SDL_Renderer *ren, float xr, float y, SDL_Color gear_bg, int paint)
{
    (void)xr; (void)y;
    int ow = 0, oh = 0;
    SDL_GetRendererOutputSize(ren, &ow, &oh);
    float xs[4], d = 42.f, cy = 10.f;
    int ids[4];
    const int n = ui_chrome_layout(ow, xs, ids, &d, &cy);
    for (int i = 0; i < n; i++) {
        if (ids[i] == H_SETTINGS) {
            if (paint) ui_gear_button(ren, xs[i] + d, cy, d, gear_bg, H_SETTINGS);
            else ui_chrome_hit(xs[i], cy, d, H_SETTINGS);
        } else {
            if (paint) ui_win_btn(ren, xs[i], cy, d, ids[i], ids[i] == H_WIN_CLOSE);
            else ui_chrome_hit(xs[i], cy, d, ids[i]);
        }
    }
}

/* Settings / window controls in real window pixels so they don't grow with resize.
 * paint=0 still registers hit targets so clicks stay reliable between hover frames. */
static void ui_top_chrome_fixed(SDL_Renderer *ren, SDL_Color gear_bg, int paint)
{
    int ow = 0, oh = 0, lw = 0, lh = 0;
    float sx = 1, sy = 1;
    SDL_GetRendererOutputSize(ren, &ow, &oh);
    if (ow < 80 || oh < 40) return;
    SDL_RenderGetLogicalSize(ren, &lw, &lh);
    SDL_RenderGetScale(ren, &sx, &sy);
    SDL_RenderSetLogicalSize(ren, 0, 0);
    SDL_RenderSetScale(ren, 1, 1);
    g_ui_hit_window = 1;
    ui_top_chrome(ren, (float)ow - 12.0f, 12.0f, gear_bg, paint);
    g_ui_hit_window = 0;
    if (lw > 0 && lh > 0) SDL_RenderSetLogicalSize(ren, lw, lh);
    SDL_RenderSetScale(ren, sx, sy);
}

/* a small key hint, like a key cap */
static float ui_key(SDL_Renderer *ren, float x, float y, const char *k)
{
    const int px = ui_px(13);
    const float w = text_width(k, px, 1) + ui_d(14);
    ui_rrect(ren, x, y, w, ui_d(22), ui_d(6), UI_SURF3);
    text_draw(ren, k, (int)(x + w / 2), (int)(y + ui_d(3)), px, 1, UI_INK, 1);
    return w;
}

/* ---- the settings drawer ---------------------------------------------- */

static void ui_section(SDL_Renderer *ren, float x, float *y, const char *name)
{
    *y += ui_d(14);
    text_draw(ren, name, (int)x, (int)*y, ui_px(13), 1, UI_FAINT, 0);
    *y += ui_d(26);
}

/* a row's label (and a smaller line under it); the control goes on the right.
 * Titles are width-clipped so a narrow column cannot bleed into its neighbour. */
static void ui_label(SDL_Renderer *ren, float x, float y, float h, const char *label, const char *sub, int focus, float w)
{
    if (focus) ui_rrect(ren, x - ui_d(12), y - 2, w + ui_d(24), h + 4, ui_d(10), UI_SURF2);
    const int tw = (int)(w - ui_d(56)); /* leave room for the toggle / button on the right */
    if (tw < 40) return;
    if (sub && sub[0]) {
        ui_text_fit(ren, label, (int)x, (int)(y + h / 2 - ui_d(20)), ui_px(17), 0, UI_INK, 0, tw);
        ui_text_fit(ren, sub, (int)x, (int)(y + h / 2 + ui_d(2)), ui_px(13), 0, UI_DIM, 0, tw);
    } else {
        ui_text_fit(ren, label, (int)x, (int)(y + h / 2 - ui_d(11)), ui_px(17), 0, UI_INK, 0, tw);
    }
}

static const char *const ui_hud_names[HUD_MODES] = { "Off", "FPS", "Graphs", "Full" };

/* Settings card at a fixed pixel size (x0,y0,pw,ph). Caller draws the veil.
 * Two columns only when the card is wide enough; narrower cards stay one column
 * so controls never collide. Header/footer shrink on short cards so the scroll
 * viewport never goes invalid (that was the "mush together" failure mode).
 * Layout/fonts use g_ui_d (desktop/tablet/mobile) so smaller tiers stay sharp. */
/* v1.0.2: the two pages that can be open over the game. Declared up here, above
 * the headers that own them, because raw-view.c reads them where it tracks the
 * held keys - which is before this file is included. While either is open
 * nothing reaches the console, exactly as on Android. */
static int g_ve_open = 0;      /* the on-screen gamepad's layout editor */
static int g_gp_page = 0;      /* the gamepad settings page */

#include "vpad_layout.h"   /* v1.0.2: the on-screen gamepad's layout (the Android app's format) */
#include "vpad.h"          /* v1.0.2: the on-screen gamepad itself */
#include "vpad_editor.h"   /* v1.0.2: and its layout editor */

/* v1.0.2: the gamepad page the CONTROL rows open. It is defined after the input
 * block in raw-view.c, which it needs (it takes the controller away while it is
 * open); the rows only ask for it by this name. */
/* The gamepad page: its state and its remap table, which prefs_save below
 * writes. It reads the controller through the table, so it needs the two names
 * from raw-view.c's input block - declared here, defined there. */
static int clamp_stick(int v);
static void input_release_all(void);
static SDL_GameController *g_ctrl;

#include "gamepad_page.h"    /* v1.0.2: remap, test, the right stick's axes, the vibration test */

/* The settings, written on every change. Defined here rather than above with
 * prefs_load because the v1.0.2 keys belong to vpad.h and gamepad_page.h, which
 * only exist from the includes just above this point. */
static void prefs_save(void)
{
    char path[1024];
    prefs_path(path, sizeof(path));
    FILE *f = fopen(path, "w");
    if (!f) return;
    fprintf(f, "connection=%s\nlast_ip=%s\nstats_overlay=%d\nvsync=%d\nupscaler=%s\nhwdec=%d\nlock_aspect=%d\nchromeless=%d\npicture=%d,%d,%d,%d,%d\n", g_conn == CONN_NET ? "network" : "usb", g_last_ip, g_hud, g_vsync, up_key[g_upscaler], g_hwdec, g_lock_aspect, g_chromeless,
            g_pic.bright, g_pic.contrast, g_pic.sat, g_pic.gamma10, g_pic.sharp);
    fprintf(f, "fsr_sharpness=%d\n", g_fsr_sharpness);
    fprintf(f, "update_betas=%d\nupdate_check=%d\n", g_upd_betas, g_upd_check);
    fprintf(f, "touch_control=%d\nkeyboard_control=%d\ngamepad_control=%d\nrumble=%d\n",
            g_touch_on, g_kb_on, g_pad_on, g_rumble_on);   /* v1.0.1 */
    /* v1.0.2: the on-screen gamepad and the controller's mapping. The layout is
     * the Android app's own string, so it can be copied between the two. */
    fprintf(f, "auto=%d\nset_op=%d\n", g_conn_requested, g_set_op);   /* v1.0.3 */
    fprintf(f, "vpad_mode=%d\nvpad_opacity=%.2f\nvpad_hidden=%d\n", g_vpad_mode, g_vpad_opacity, g_vpad_hidden);
    fprintf(f, "pad_rumble=%d\npad_right=%d\n", g_pad_rumble_on, g_gp_right);
    {
        char lay[700], map[220];
        vp_serialize(&g_vpad_layout, lay, sizeof(lay));
        gp_save_str(map, sizeof(map));
        fprintf(f, "vpad_layout=%s\npad_map=%s\n", lay, map);
    }
    if (g_geom_valid)
        fprintf(f, "window=%d,%d,%d,%d\nmaximized=%d\n", g_geom_x, g_geom_y, g_geom_w, g_geom_h, g_geom_maximized);
    fclose(f);
}

/* The Switch IP field used on the home connection card and in the drawer:
 * a SURF3 box with the saved address, a blinking cursor while typing, and a
 * hint when no address is set. Read-only (no hit, dimmed) when connected.
 * v1.1.3: the text is white while editing and gray when idle, and editing
 * reveals Confirm / Cancel buttons under the box. Returns the height the
 * whole block occupies (box, plus the button row while editing). */
static float ui_ip_field(SDL_Renderer *ren, float x, float y, float w, int editable)
{
    const float h = ui_d(38);
    const char *typed = ip_edit_for_menu();
    const int editing = typed != NULL;
    ui_rrect(ren, x, y, w, h, ui_d(10), (editable || editing) ? UI_SURF3 : UI_SURF2);
    char buf[40];
    const char *v;
    if (editing) {
        snprintf(buf, sizeof(buf), "%s%s", typed, ((SDL_GetTicks() / 500) & 1) ? "_" : " ");
        v = buf;
    } else if (g_last_ip[0]) {
        v = g_last_ip;
    } else {
        v = "Blank: find it on the network";
    }
    /* white while typing; a saved address sits gray until the field is opened */
    SDL_Color ink;
    if (editing) ink = UI_INK;
    else if (!editable) ink = UI_FAINT;
    else if (g_last_ip[0]) ink = UI_DIM;
    else ink = UI_FAINT;
    text_draw(ren, v, (int)(x + ui_d(12)), (int)(y + ui_d(10)), ui_px(14), 0, ink, 0);
    if (editable || editing) ui_hit(x, y, w, h, H_IP, 0);
    if (editing) {
        const float by = y + h + ui_d(8);
        const float cw = ui_button(ren, x + w, 0, by, "Confirm", UI_ACC, UI_ONACC, H_IP_OK, 0, 1);
        ui_button(ren, x + w - cw - ui_d(10), 0, by, "Cancel", UI_SURF3, UI_INK, H_IP_CANCEL, 0, 1);
        return h + ui_d(8) + ui_d(32);
    }
    return h;
}

/* ui_event is bypassed while the IP field is editing (the keys must stay
 * text-only), so the field's buttons get their own mouse path here. Returns
 * 1 when the click landed on a field control and was consumed. */
static int ui_ip_edit_event(SDL_Window *win, SDL_Renderer *ren, const SDL_Event *e)
{
    if (e->type != SDL_MOUSEBUTTONDOWN || e->button.button != SDL_BUTTON_LEFT) return 0;
    int ww = 1, wh = 1, ow = 1, oh = 1;
    if (win) SDL_GetWindowSize(win, &ww, &wh);
    SDL_GetRendererOutputSize(ren, &ow, &oh);
    if (ww < 1) ww = 1;
    if (wh < 1) wh = 1;
    const float gx = (float)e->button.x * (float)ow / (float)ww;
    const float gy = (float)e->button.y * (float)oh / (float)wh;
    float lx = gx, ly = gy;
    SDL_RenderWindowToLogical(ren, e->button.x, e->button.y, &lx, &ly);
    const float mx = lx / g_ui_k, my = ly / g_ui_k;
    for (int i = g_ui_nhits - 1; i >= 0; i--) {
        const ui_hit_t *h = &g_ui_hits[i];
        if (h->id != H_IP && h->id != H_IP_OK && h->id != H_IP_CANCEL) continue;
        const float hx = h->window_space ? gx : mx;
        const float hy = h->window_space ? gy : my;
        if (!(hx >= h->r.x && hx < h->r.x + h->r.w && hy >= h->r.y && hy < h->r.y + h->r.h)) continue;
        if (h->id == H_IP_OK) act_ip_confirm();
        else if (h->id == H_IP_CANCEL) act_ip_cancel();
        return 1;   /* H_IP itself: stay editing, swallow the click */
    }
    return 0;
}

static void ui_drawer(SDL_Renderer *ren, float x0, float y0, float pw, float ph)
{
    const float min_col = ui_d(280), two_col_w = ui_d(720);
    const int cols = pw >= two_col_w ? 2 : 1;
    const float pad = pw >= ui_d(500) ? ui_d(26) : ui_d(16), gap = ui_d(22);
    float col_w = cols == 2 ? (pw - pad * 2 - gap) * 0.5f : pw - 2 * pad;
    if (col_w < ui_d(120)) col_w = pw - 2 * pad;
    const int use_cols = (cols == 2 && col_w >= min_col) ? 2 : 1;
    if (use_cols == 1) col_w = pw - 2 * pad;
    const float cx_l = x0 + pad, xr_l = cx_l + col_w;
    const float cx_r = use_cols == 2 ? cx_l + col_w + gap : cx_l;
    const float xr_r = cx_r + col_w;
    const float cx = cx_l, xr = x0 + pw - pad;

    ui_hit(x0, y0, pw - 22.f, ph, H_PANEL, 0); /* leave scrollbar strip out of panel-drag */
    /* borderless: a hairline around this near-window-sized panel read as a
     * thin white box on the screen edge; the SURF fill already separates it */
    ui_surface(ren, x0, y0, pw, ph, ui_d(16), (SDL_Color){ UI_SURF.r, UI_SURF.g, UI_SURF.b, 250 });

    /* Chrome sizes: reclaim vertical space before the scroll clip breaks. */
    const int show_footer = ph >= ui_d(360);
    float head_h = ph >= ui_d(280) ? ui_d(76) : ui_d(52);
    float foot_h = show_footer ? ui_d(44) : ui_d(6);
    float top = y0 + head_h, bottom = y0 + ph - foot_h;
    if (bottom < top + ui_d(120)) { head_h = ui_d(48); foot_h = ui_d(4); top = y0 + head_h; bottom = y0 + ph - foot_h; }
    if (bottom <= top + ui_d(48)) {
        text_draw(ren, "Settings", (int)cx, (int)(y0 + ui_d(16)), ui_px(20), 1, UI_INK, 0);
        ui_button(ren, xr, 0, y0 + ui_d(14), "Close", UI_SURF2, UI_INK, H_CLOSE, 0, 1);
        text_draw(ren, "Resize the window larger to use settings", (int)(x0 + pw * 0.5f), (int)(y0 + ph * 0.5f), ui_px(15), 0, UI_DIM, 1);
        return;
    }

    if (g_ui_scroll_to >= 0 && g_ui_scroll_to < g_ui_nrows) {
        const float ry = g_ui_rows[g_ui_scroll_to].y + g_ui_scroll, rh = g_ui_rows[g_ui_scroll_to].h;
        if (ry - 40 < top) g_ui_scroll -= top - (ry - 40);
        if (ry + rh + 10 > bottom) g_ui_scroll += ry + rh + 10 - bottom;
    }
    g_ui_scroll_to = -1;
    const float view_h = bottom - top;
    const float maxs = g_ui_content_h - view_h;
    if (g_ui_scroll > maxs) g_ui_scroll = maxs;
    if (g_ui_scroll < 0) g_ui_scroll = 0;
    SDL_Rect clip_units = { (int)x0, (int)top, (int)pw + 1, (int)view_h };
    SDL_RenderSetClipRect(ren, &clip_units);
    const int first_hit = g_ui_nhits;
    g_ui_nrows = 0;
    /* a little air above the first scrolled row so it never touches the
     * header separator (the old flush-to-the-header bleed); the measurement
     * origin stays at the clip top, so the scroll max grows by the same pad */
    const float content_pad = ui_d(12);
    float y_l = top - g_ui_scroll + content_pad, y_r = top - g_ui_scroll + content_pad;
    const float y_start = top - g_ui_scroll;

    /* ---- left column (or full width): connection + display ---- */
    {
        float y = y_l, cxi = cx_l, cwi = col_w, xri = xr_l;
        ui_section(ren, cxi, &y, "CONNECTION");
        {
            static const char *const conn[] = { "USB", "Network" };
            const float h = ui_d(44), gap_y = ui_d(6);
            ui_row(H_CONN, 0, y, h);
            ui_label(ren, cxi, y, h, "Connection", NULL, ui_focused(H_CONN, 0), cwi);
            ui_seg(ren, xri, 0, y + gap_y, conn, 2, g_conn == CONN_NET, H_CONN, 14, cwi * 0.55f);
            y += h + gap_y;
            if (g_conn == CONN_NET) {
                /* the field itself, under its label - no separate Type IP
                 * button; clicking the box starts the typing (H_IP) */
                text_draw(ren, "Switch IP address", (int)cxi, (int)(y + ui_d(2)), ui_px(13), 0, UI_DIM, 0);
                y += ui_d(20) + ui_ip_field(ren, cxi, y + ui_d(20), cwi, act_ip_possible()) + ui_d(8);
            }
        }

        ui_section(ren, cxi, &y, "DISPLAY");
        {
            const float h = ui_d(48), gap_y = ui_d(4), tog_y = ui_d(12);
            ui_row(H_FULL, 0, y, h);
            ui_label(ren, cxi, y, h, "Fullscreen", "F11, double-click, or middle-click", ui_focused(H_FULL, 0), cwi);
            ui_toggle(ren, xri, y + tog_y, g_ui_win && (SDL_GetWindowFlags(g_ui_win) & SDL_WINDOW_FULLSCREEN_DESKTOP) != 0, H_FULL, 1);
            y += h + gap_y;
            ui_row(H_VSYNC, 0, y, h);
            ui_label(ren, cxi, y, h, "Vsync", g_vsync ? "Smooth, no tearing" : "Off: lowest latency, may tear", ui_focused(H_VSYNC, 0), cwi);
            ui_toggle(ren, xri, y + tog_y, g_vsync, H_VSYNC, 1);
            y += h + gap_y;
            ui_row(H_ASPECT, 0, y, h);
            ui_label(ren, cxi, y, h, "Lock aspect ratio", g_lock_aspect ? "Window stays 16:9 while resizing (A)" : "Free resize; picture letterboxed (A)", ui_focused(H_ASPECT, 0), cwi);
            ui_toggle(ren, xri, y + tog_y, g_lock_aspect, H_ASPECT, 1);
            y += h + gap_y;
            ui_row(H_CHROMELESS, 0, y, h);
            ui_label(ren, cxi, y, h, "Borderless window", g_chromeless ? "No title bar; drag the picture to move (B)" : "Normal window frame (B)", ui_focused(H_CHROMELESS, 0), cwi);
            ui_toggle(ren, xri, y + tog_y, g_chromeless, H_CHROMELESS, 1);
            y += h + gap_y;
            ui_row(H_HWDEC, 0, y, h);
            char dec[64];
            snprintf(dec, sizeof(dec), "Now: %s", g_dec_mode && g_dec_mode[0] ? g_dec_mode : (g_hwdec ? "the graphics card" : "the CPU"));
#if defined(__APPLE__)
            ui_label(ren, cxi, y, h, "Hardware decoding (beta)", dec, ui_focused(H_HWDEC, 0), cwi);
#else
            ui_label(ren, cxi, y, h, "Hardware decoding", dec, ui_focused(H_HWDEC, 0), cwi);
#endif
            ui_toggle(ren, xri, y + tog_y, g_hwdec, H_HWDEC, 1);
            y += h + gap_y;
            /* Dedicated blurb under the title for every mode (not the HUD "-" fallback). */
            char why[120];
            if (g_upscaler == UP_OFF)
                snprintf(why, sizeof(why), "No upscaling (U)");
            else if (g_upscaler == UP_FSR)
                snprintf(why, sizeof(why), "AMD FidelityFX Super Resolution (U)");
            else if (g_upscaler == UP_NIS)
                snprintf(why, sizeof(why), "NVIDIA Image Scaling (U)");
#ifdef _WIN32
            else if (g_upscaler == UP_RTX)
                snprintf(why, sizeof(why), "NVIDIA RTX Video Super Resolution (U)");
#endif
            else
                snprintf(why, sizeof(why), "%s (U)", up_name[g_upscaler]);
            /* When live, prefer the real status (GPU / output size / restart note). */
            if (g_upscaler != UP_OFF) {
                char live[120];
                up_describe(ren, live, sizeof(live));
                if (live[0] && strcmp(live, "-") != 0) snprintf(why, sizeof(why), "%s", live);
            }
            const float h_ups = ui_d(94);
            ui_row(H_UPS, 0, y, h_ups);
            if (ui_focused(H_UPS, 0)) ui_rrect(ren, cxi - ui_d(12), y - 2, cwi + ui_d(24), h_ups + 4, ui_d(10), UI_SURF2);
            text_draw(ren, "Upscaling", (int)cxi, (int)(y + ui_d(2)), ui_px(17), 0, UI_INK, 0);
            ui_text_fit(ren, why, (int)cxi, (int)(y + ui_d(24)), ui_px(13), 0, UI_DIM, 0, (int)cwi);
            static const char *const ups[] = { "Off", "FSR 1", "NIS", "RTX VSR" };
            ui_seg(ren, xri, 0, y + ui_d(48), ups, UP_COUNT, g_upscaler, H_UPS, 13, cwi);
            y += h_ups + gap_y;
#ifdef __APPLE__
            if (g_upscaler == UP_FSR) {
                const float sh = ui_d(52), ty = y + ui_d(34);
                ui_row(H_FSR_SHARP, 0, y, sh);
                if (ui_focused(H_FSR_SHARP, 0)) ui_rrect(ren, cxi - ui_d(12), y - 2, cwi + ui_d(24), sh + 4, ui_d(10), UI_SURF2);
                text_draw(ren, "FSR sharpness", (int)cxi, (int)(y + ui_d(4)), ui_px(16), 0, UI_INK, 0);
                char value[16]; snprintf(value, sizeof(value), "%d%%", g_fsr_sharpness);
                text_draw(ren, value, (int)xri, (int)(y + ui_d(4)), ui_px(15), 1, UI_ACC, 2);
                const float vx = cxi + cwi * g_fsr_sharpness / 100.0f;
                ui_rrect(ren, cxi, ty - 2, cwi, 4, 2, UI_SURF3);
                ui_rrect(ren, cxi, ty - 2, vx - cxi, 4, 2, UI_ACC);
                ui_rrect(ren, vx - ui_d(8), ty - ui_d(8), ui_d(16), ui_d(16), ui_d(8), UI_INK);
                ui_hit(cxi - ui_d(8), ty - ui_d(14), cwi + ui_d(16), ui_d(28), H_FSR_SHARP, 0);
                y += sh + gap_y;
            }
#endif
            const float h_hud = ui_d(74);
            ui_row(H_HUD, 0, y, h_hud);
            if (ui_focused(H_HUD, 0)) ui_rrect(ren, cxi - ui_d(12), y - 2, cwi + ui_d(24), h_hud + 4, ui_d(10), UI_SURF2);
            text_draw(ren, "Stats overlay", (int)cxi, (int)(y + ui_d(4)), ui_px(17), 0, UI_INK, 0);
            text_draw(ren, "O or F3", (int)xri, (int)(y + ui_d(7)), ui_px(13), 0, UI_DIM, 2);
            ui_seg(ren, xri, 0, y + ui_d(32), ui_hud_names, HUD_MODES, g_hud, H_HUD, 13, cwi);
            y += h_hud + gap_y;
        }
        y_l = y;
    }

    /* ---- right column when wide; else continue below left ---- */
    {
        float y = use_cols == 2 ? y_r : y_l;
        float cxi = use_cols == 2 ? cx_r : cx_l, cwi = col_w, xri = use_cols == 2 ? xr_r : xr_l;

        ui_section(ren, cxi, &y, "PICTURE");
        if (!pic_neutral()) ui_button(ren, xri, 0, y - ui_d(36), "Reset", UI_SURF3, UI_INK, H_PIC_RESET, 0, 1);
        for (int r = 0; r < PIC_N; r++) {
            const float h = ui_d(52);
            ui_row(H_PIC, r, y, h);
            if (ui_focused(H_PIC, r)) ui_rrect(ren, cxi - ui_d(12), y - 2, cwi + ui_d(24), h + 4, ui_d(10), UI_SURF2);
            text_draw(ren, pic_rows[r].name, (int)cxi, (int)(y + ui_d(4)), ui_px(16), 0, UI_INK, 0);
            char t[16];
            pic_value_text(r, t, sizeof(t));
            const int v = *pic_field(r), lo = pic_rows[r].min, hi = pic_rows[r].max, def = *(&pic_default.bright + r);
            text_draw(ren, t, (int)xri, (int)(y + ui_d(4)), ui_px(15), 1, v == def ? UI_DIM : UI_ACC, 2);
            const float tx = cxi, tw = cwi, ty = y + ui_d(34);
            ui_rrect(ren, tx, ty - 2, tw, 4, 2, UI_SURF3);
            const float fx = tx + (float)(def - lo) / (hi - lo) * tw, vx = tx + (float)(v - lo) / (hi - lo) * tw;
            ui_rrect(ren, vx < fx ? vx : fx, ty - 2, vx < fx ? fx - vx : vx - fx, 4, 2, UI_ACC);
            ui_rrect(ren, fx - 1, ty - ui_d(6), 2, ui_d(12), 1, UI_FAINT);
            const int hov = ui_in(tx - ui_d(8), ty - ui_d(14), tw + ui_d(16), ui_d(28)) || (g_ui_drag == H_PIC && g_ui_drag_arg == r);
            const float ks = hov ? ui_d(18) : ui_d(16);
            ui_rrect(ren, vx - ks * 0.5f, ty - ks * 0.5f, ks, ks, ks * 0.5f, UI_INK);
            ui_hit(tx - ui_d(8), ty - ui_d(14), tw + ui_d(16), ui_d(28), H_PIC, r);
            y += h + ui_d(2);
        }

        ui_section(ren, cxi, &y, "SOUND");
        {
            const float h = ui_d(48);
            ui_row(H_SOUND, 0, y, h);
            ui_label(ren, cxi, y, h, "Game sound", g_mute ? "Muted (M)" : "On (M mutes)", ui_focused(H_SOUND, 0), cwi);
            ui_toggle(ren, xri, y + ui_d(12), !g_mute, H_SOUND, 1);
            y += h + ui_d(4);
        }

        /* v1.0.1: the viewer's input to the console, as the Android app has */
        ui_section(ren, cxi, &y, "CONTROL");
        {
            const float h = ui_d(48), gap_y = ui_d(4), tog_y = ui_d(12);
            ui_row(H_TOUCH, 0, y, h);
            ui_label(ren, cxi, y, h, "Mouse / touch", g_touch_on ? "The mouse is the Switch's touch screen" : "The mouse only controls this viewer", ui_focused(H_TOUCH, 0), cwi);
            ui_toggle(ren, xri, y + tog_y, g_touch_on, H_TOUCH, 1);
            y += h + gap_y;
            ui_row(H_KB, 0, y, h);
            ui_label(ren, cxi, y, h, "Keyboard", g_kb_on ? "The keyboard is a Pro Controller" : "The keyboard only controls this viewer", ui_focused(H_KB, 0), cwi);
            ui_toggle(ren, xri, y + tog_y, g_kb_on, H_KB, 1);
            y += h + gap_y;
            ui_row(H_PAD, 0, y, h);
            ui_label(ren, cxi, y, h, "Gamepad", g_pad_on ? "A controller on this PC plays on the Switch" : "Controllers only control this viewer", ui_focused(H_PAD, 0), cwi);
            ui_toggle(ren, xri, y + tog_y, g_pad_on, H_PAD, 1);
            y += h + gap_y;
            ui_row(H_RUMBLE, 0, y, h);
            ui_label(ren, cxi, y, h, "Rumble", g_rumble_on ? "The game's vibration, on this PC's controller" : "No rumble asked for", ui_focused(H_RUMBLE, 0), cwi);
            ui_toggle(ren, xri, y + tog_y, g_rumble_on, H_RUMBLE, 1);
            y += h + gap_y;
            /* v1.0.2: the on-screen gamepad, as the Android app has it */
            {
                const float h2 = ui_d(74);
                ui_row(H_VPAD, 0, y, h2);
                if (ui_focused(H_VPAD, 0)) ui_rrect(ren, cxi - ui_d(12), y - 2, cwi + ui_d(24), h2 + 4, ui_d(10), UI_SURF2);
                text_draw(ren, "On-screen gamepad", (int)cxi, (int)(y + ui_d(4)), ui_px(16), 0, UI_INK, 0);
                ui_seg(ren, xri, 0, y + ui_d(32), vpad_mode_names, 3, g_vpad_mode, H_VPAD, 13, cwi);
                y += h2 + gap_y;
            }
            {
                const float h2 = ui_d(56);
                ui_row(H_VPAD_LAYOUT, 0, y, h2);
                ui_label(ren, cxi, y, h2, "On-screen gamepad layout",
                         "Move, resize, add or remove its buttons, and set how see-through they are",
                         ui_focused(H_VPAD_LAYOUT, 0), cwi);
                ui_button(ren, xri, 0, y + ui_d(12), "Edit", UI_SURF3, UI_INK, H_VPAD_LAYOUT, 0, 1);
                y += h2 + gap_y;
            }
            {
                const float h2 = ui_d(56);
                ui_row(H_GP_PAGE, 0, y, h2);
                char sub[120];
                snprintf(sub, sizeof(sub), "%s - remap and test the buttons",
                         g_ctrl ? SDL_GameControllerName(g_ctrl) : "No gamepad connected");
                ui_label(ren, cxi, y, h2, "Gamepad settings", sub, ui_focused(H_GP_PAGE, 0), cwi);
                ui_button(ren, xri, 0, y + ui_d(12), "Open", UI_SURF3, UI_INK, H_GP_PAGE, 0, 1);
                y += h2 + gap_y;
            }
        /* v1.0.5: how see-through the Settings button is - a slider from 100%
         * to 0%, dragged like the picture rows. 0% is fine: the button still
         * takes the click and still comes back at full on hover, so it is the
         * same button, just not drawn until the pointer finds it. */
        {
            const float h = ui_d(52), ty = y + ui_d(34);
            ui_row(H_SET_OP, 0, y, h);
            if (ui_focused(H_SET_OP, 0)) ui_rrect(ren, cxi - ui_d(12), y - 2, cwi + ui_d(24), h + 4, ui_d(10), UI_SURF2);
            text_draw(ren, "Settings button", (int)cxi, (int)(y + ui_d(4)), ui_px(16), 0, UI_INK, 0);
            char value[16]; snprintf(value, sizeof(value), "%d%%", g_set_op);
            text_draw(ren, value, (int)xri, (int)(y + ui_d(4)), ui_px(15), 1,
                      g_set_op == 100 ? UI_DIM : UI_ACC, 2);
            const float vx = cxi + cwi * g_set_op / 100.0f;
            ui_rrect(ren, cxi, ty - 2, cwi, 4, 2, UI_SURF3);
            ui_rrect(ren, cxi, ty - 2, vx - cxi, 4, 2, UI_ACC);
            ui_rrect(ren, vx - ui_d(8), ty - ui_d(8), ui_d(16), ui_d(16), ui_d(8), UI_INK);
            ui_hit(cxi - ui_d(8), ty - ui_d(14), cwi + ui_d(16), ui_d(28), H_SET_OP, 0);
            y += h + gap_y;
        }
            const float hm = ui_d(34);
            ui_row(H_KB, 1, y, hm);
            ui_text_fit(ren, "WASD / IJKL sticks, arrows D-pad, Z X C V = A B X Y, B N = L R, , . = ZL ZR, / ; = - +, H = HOME",
                        (int)cxi, (int)(y + ui_d(4)), ui_px(12), 0, UI_FAINT, 0, (int)cwi);
            y += hm + gap_y;
        }

        ui_section(ren, cxi, &y, "RECORDING");
        {
            const float h = ui_d(48);
            char rs[200], sub[200];
            rec_status(rs, sizeof(rs));
            snprintf(sub, sizeof(sub), "%s", rec_active() ? rs + 5 : "MP4 with sound (R)");
            ui_row(H_REC, 0, y, h);
            ui_label(ren, cxi, y, h, "Record", sub, ui_focused(H_REC, 0), cwi);
            if (rec_active()) ui_button(ren, xri, 0, y + ui_d(8), "Stop", UI_BAD, (SDL_Color){ 30, 8, 8, 255 }, H_REC, 0, 1);
            else ui_button(ren, xri, 0, y + ui_d(8), "Start", UI_SURF3, UI_INK, H_REC, 0, 1);
            y += h + ui_d(2);
            const char *dir = rec_dir_for_menu();
            if (dir[0]) {
                char line[1100];
                snprintf(line, sizeof(line), "Saved in %s", dir);
                ui_text_fit(ren, line, (int)cxi, (int)y, ui_px(13), 0, UI_FAINT, 0, (int)cwi);
                y += ui_d(24);
            }
        }
        /* v0.7.14: updates from GitHub (update.h) */
        ui_section(ren, cxi, &y, "UPDATES");
        {
            const float h = ui_d(48);
            char sb[220];
            const int us = upd_state();
            const char *btn = us == UPD_FOUND ? "Update" : us == UPD_READY ? "Restart" : (us == UPD_ERROR && g_upd.failed_update) ? "Try again" : "Check";
            const int can = us != UPD_CHECKING && us != UPD_GETTING && us != UPD_UNSUPPORTED;
            ui_row(H_UPD, 0, y, h);
            ui_label(ren, cxi, y, h, "Switch Remote Frame " SFT_VERSION, upd_status(sb, sizeof(sb)), ui_focused(H_UPD, 0), cwi);
            ui_button(ren, xri, 0, y + ui_d(8), btn, us == UPD_FOUND || us == UPD_READY ? UI_ACC : UI_SURF3,
                      us == UPD_FOUND || us == UPD_READY ? UI_ONACC : UI_INK, H_UPD, 0, can);
            y += h + ui_d(4);
            ui_row(H_UPD_BETA, 0, y, h);
            ui_label(ren, cxi, y, h, "Include betas", g_upd_betas ? "Offers test versions too" : "Only full releases", ui_focused(H_UPD_BETA, 0), cwi);
            ui_toggle(ren, xri, y + ui_d(12), g_upd_betas, H_UPD_BETA, us != UPD_UNSUPPORTED);
            y += h + ui_d(4);
            ui_row(H_UPD_AUTO, 0, y, h);
            ui_label(ren, cxi, y, h, "Check at start-up", "Looks for a new version when the viewer opens", ui_focused(H_UPD_AUTO, 0), cwi);
            ui_toggle(ren, xri, y + ui_d(12), g_upd_check, H_UPD_AUTO, us != UPD_UNSUPPORTED);
            y += h + ui_d(4);
        }

        y += ui_d(16);
        if (use_cols == 2) y_r = y; else y_l = y;
    }

    g_ui_content_h = (use_cols == 2 ? (y_l > y_r ? y_l : y_r) : y_l) - y_start;
    SDL_RenderSetClipRect(ren, NULL);
    /* hits under the header or the footer do not count */
    for (int i = first_hit; i < g_ui_nhits; i++) {
        SDL_FRect *r = &g_ui_hits[i].r;
        if (g_ui_hits[i].id == H_PANEL) continue;
        if (r->y + r->h < top || r->y > bottom) { r->w = 0; r->h = 0; continue; }
        if (r->y < top) { r->h -= top - r->y; r->y = top; }
        if (r->y + r->h > bottom) r->h = bottom - r->y;
    }

    /* the header and the footer over the scrolled rows */
    SDL_SetRenderDrawColor(ren, UI_SURF.r, UI_SURF.g, UI_SURF.b, 255);
    SDL_FRect hb = { x0 + 1, y0, pw - 1, top - y0 }, fb = { x0 + 1, bottom, pw - 1, y0 + ph - bottom };
    SDL_RenderFillRectF(ren, &hb);
    SDL_RenderFillRectF(ren, &fb);
    /* Left-aligned title and a short orientation cue make the drawer read like
     * a settings workspace, while the compact tier keeps the same hierarchy. */
    text_draw(ren, "Settings", (int)cx, (int)(y0 + (head_h >= ui_d(70) ? ui_d(15) : ui_d(14))),
              head_h >= ui_d(70) ? ui_px(23) : ui_px(20), 0, UI_INK, 0);
    if (head_h >= ui_d(70))
        text_draw(ren, "Playback, input, display and updates", (int)cx, (int)(y0 + ui_d(46)),
                  ui_px(12), 0, UI_DIM, 0);
    {
        const float by = y0 + (head_h >= ui_d(70) ? ui_d(22) : ui_d(12));
        ui_button(ren, xr, 0, by, "Close", UI_SURF2, UI_INK, H_CLOSE, 0, 1);
        /* v1.0.3: stop the stream from here, shown while looking for the Switch
         * or connected - the Android app's Disconnect in the drawer's top bar */
        if (conn_requested_for_menu()) {
            const float cw2 = text_width("Close", ui_px(14), 1) + ui_d(30);
            const float dw = text_width("Disconnect", ui_px(14), 1) + ui_d(30);
            ui_button(ren, xr - cw2 - ui_d(8), 0, by, "Disconnect", UI_SURF3, UI_WARN, H_CONNECT, 0, 1);
            (void)dw;
        }
    }
    SDL_SetRenderDrawColor(ren, UI_LINE.r, UI_LINE.g, UI_LINE.b, UI_LINE.a);
    SDL_FRect l1 = { x0 + 1, top - 1, pw - 1, 1 }, l2 = { x0 + 1, bottom, pw - 1, 1 };
    SDL_RenderFillRectF(ren, &l1);
    SDL_RenderFillRectF(ren, &l2);
    if (show_footer) {
        float kx = cx;
        kx += ui_key(ren, kx, bottom + ui_d(12), "Esc") + ui_d(6);
        text_draw(ren, "close", (int)kx, (int)(bottom + ui_d(14)), ui_px(13), 0, UI_DIM, 0);
        kx += text_width("close", ui_px(13), 0) + ui_d(14);
        kx += ui_key(ren, kx, bottom + ui_d(12), "Up/Down") + ui_d(6);
        text_draw(ren, "move", (int)kx, (int)(bottom + ui_d(14)), ui_px(13), 0, UI_DIM, 0);
        kx += text_width("move", ui_px(13), 0) + ui_d(14);
        kx += ui_key(ren, kx, bottom + ui_d(12), "Left/Right") + ui_d(6);
        text_draw(ren, "change", (int)kx, (int)(bottom + ui_d(14)), ui_px(13), 0, UI_DIM, 0);
    }
    /* Scrollbar on the right of the content view (works without a mouse wheel). */
    ui_scrollbar(ren, x0 + pw - ui_d(14), top, view_h, g_ui_content_h, &g_ui_scroll);
}

/* Settings popout: native pixels + 3 sharp density tiers (desktop / tablet / mobile).
 * Density shrinks fonts and controls by redrawing them smaller - not by scaling a bitmap. */
static void ui_settings_popout(SDL_Renderer *ren)
{
    int ow = 0, oh = 0, lw = 0, lh = 0;
    float sx = 1, sy = 1;
    SDL_GetRendererOutputSize(ren, &ow, &oh);
    if (ow < 40 || oh < 40) return;
    SDL_RenderGetLogicalSize(ren, &lw, &lh);
    SDL_RenderGetScale(ren, &sx, &sy);
    SDL_RenderSetLogicalSize(ren, 0, 0);
    SDL_RenderSetScale(ren, 1, 1);

    const float margin = 12.f;
    float avail_w = (float)ow - 2.f * margin;
    float avail_h = (float)oh - 2.f * margin;
    if (avail_w < 280.f) avail_w = (float)ow > 8 ? (float)ow - 8 : (float)ow;
    if (avail_h < 200.f) avail_h = (float)oh > 8 ? (float)oh - 8 : (float)oh;

    /* Pick density from how the desktop card (820×640) fits the window. */
    const float fit_w = avail_w / 820.f, fit_h = avail_h / 640.f;
    const float fit = fit_w < fit_h ? fit_w : fit_h;
    if (fit >= 1.0f) g_ui_d = 1.0f;        /* desktop */
    else if (fit >= 0.78f) g_ui_d = 0.85f; /* tablet */
    else g_ui_d = 0.70f;                   /* mobile */

    const float design_w = 820.f * g_ui_d, design_h = 640.f * g_ui_d;
    float pw = design_w < avail_w ? design_w : avail_w;
    float ph = design_h < avail_h ? design_h : avail_h;
    if (pw < 360.f * g_ui_d) pw = avail_w;
    if (ph < 240.f * g_ui_d) ph = avail_h;

    float x0 = ((float)ow - pw) * 0.5f;
    float y0 = ((float)oh - ph) * 0.5f;
    if (x0 < margin) x0 = margin;
    if (y0 < margin) y0 = margin;
    if (x0 + pw > (float)ow) x0 = (float)ow - pw;
    if (y0 + ph > (float)oh) y0 = (float)oh - ph;
    if (x0 < 0) x0 = 0;
    if (y0 < 0) y0 = 0;

    g_ui_k = 1;
    g_ui_w = (float)ow;
    g_ui_popout_x0 = x0;
    g_ui_popout_y0 = y0;
    g_ui_popout_scale = 1.f; /* 1:1 window pixels - density is in the draw sizes */
    g_ui_popout_map = 0;

    SDL_SetRenderDrawBlendMode(ren, SDL_BLENDMODE_BLEND);
    SDL_SetRenderDrawColor(ren, 0, 0, 0, 140);
    SDL_FRect veil = { 0, 0, (float)ow, (float)oh };
    SDL_RenderFillRectF(ren, &veil);
    g_ui_popout_paints++;
    g_ui_hit_window = 1;
    /* Backdrop is registered but does not close the panel (Esc / Close only).
     * Clicks on it fall through so chromeless drag can still move the window. */
    ui_hit(0, 0, (float)ow, (float)oh, H_BACKDROP, 0);
    ui_drawer(ren, x0, y0, pw, ph);

    g_ui_hit_window = 0;
    if (lw > 0 && lh > 0) SDL_RenderSetLogicalSize(ren, lw, lh);
    SDL_RenderSetScale(ren, sx, sy);
}

/* ---- over the game ------------------------------------------------------ */

/* v0.7.12: while recording, a "REC 1:23" pill at the top left of the picture
 * (drawn on the screen only - the recording keeps the console's frames) */
static void ui_rec_badge(SDL_Renderer *ren, int W, int H)
{
    if (!rec_active() || H <= 0 || text_init(ren) != 0) return;
    SDL_BlendMode old;
    SDL_GetRenderDrawBlendMode(ren, &old);
    SDL_SetRenderDrawBlendMode(ren, SDL_BLENDMODE_BLEND);
    float sx = 1, sy = 1;
    SDL_RenderGetScale(ren, &sx, &sy);
    const float k = (float)H / 720.0f, w720 = W / k;
    SDL_RenderSetScale(ren, sx * k, sy * k);
    const int s = (int)rec_seconds();
    char t[32];
    if (s >= 3600) snprintf(t, sizeof(t), "REC %d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    else snprintf(t, sizeof(t), "REC %d:%02d", s / 60, s % 60);
    /* top left, under the stats overlay when it is on */
    const float tw = text_width(t, 15, 1), w = tw + 46, x = 12;
    const float y = g_hud == HUD_FPS ? 58 : g_hud == HUD_GRAPHS ? 258 : g_hud == HUD_FULL ? 512 : 12;
    (void)w720;
    ui_rrect(ren, x, y, w, 32, 16, UI_SURF2);
    /* the dot breathes, once a second */
    const double ph = (double)(SDL_GetTicks() % 1000) / 1000.0;
    const int a = (int)(150 + 105 * (0.5 + 0.5 * cos(ph * 2 * 3.14159265)));
    ui_rrect(ren, x + 14, y + 11, 10, 10, 5, (SDL_Color){ UI_BAD.r, UI_BAD.g, UI_BAD.b, (Uint8)a });
    text_draw(ren, t, (int)(x + 32), (int)y + 7, 15, 1, UI_INK, 0);
    SDL_RenderSetScale(ren, sx, sy);
    SDL_SetRenderDrawBlendMode(ren, old);
}

/* Hover chrome (Settings + borderless window buttons) and the drawer, over the picture */
static void ui_overlay(SDL_Renderer *ren, int W, int H)
{
    g_ui_nhits = 0;
    if (H <= 0) return;
    if (g_ui_open) {
        if (text_init(ren) != 0) return;
        SDL_BlendMode old;
        SDL_GetRenderDrawBlendMode(ren, &old);
        SDL_SetRenderDrawBlendMode(ren, SDL_BLENDMODE_BLEND);
        (void)W;
        ui_settings_popout(ren);
        SDL_SetRenderDrawBlendMode(ren, old);
        return;
    }
    /* Hits always - paint only when the pointer is over the window. Hits do not
     * need fonts, so chrome stays clickable even if text_init is late/fails. */
    ui_top_chrome_fixed(ren, UI_SURF2, 0);
    if (!g_ui_ptr_inside || text_init(ren) != 0) return;
    SDL_BlendMode old;
    SDL_GetRenderDrawBlendMode(ren, &old);
    SDL_SetRenderDrawBlendMode(ren, SDL_BLENDMODE_BLEND);
    /* Re-register while painting so hit pads match the drawn buttons. */
    g_ui_nhits = 0;
    ui_top_chrome_fixed(ren, UI_SURF2, 1);
    SDL_SetRenderDrawBlendMode(ren, old);
}

/* ---- the home screen ---------------------------------------------------- */

/* Floating status panel (card + key legend), centered like settings - always
 * kept on-screen with desktop / tablet / mobile density. Brand + recordings
 * stay as light chrome in the corners; the black field is just backdrop. */
/* Paint the connection screen without presenting when it is serving as a
 * backdrop for an editor/gamepad overlay. The caller owns the final present. */
static void menu_draw_layer(SDL_Renderer *ren, int present)
{
    text_init(ren);
    int ow = 1280, oh = 720;
    SDL_GetRendererOutputSize(ren, &ow, &oh);
    if (ow < 40 || oh < 40) { ow = 1280; oh = 720; }
    SDL_RenderSetLogicalSize(ren, 0, 0);
    SDL_RenderSetScale(ren, 1, 1);
    SDL_SetRenderDrawBlendMode(ren, SDL_BLENDMODE_BLEND);
    SDL_SetRenderDrawColor(ren, UI_BG.r, UI_BG.g, UI_BG.b, 255);
    SDL_RenderClear(ren);

    g_ui_k = 1;
    g_ui_w = (float)ow;
    g_ui_nhits = 0;
    g_ui_hit_window = 1;

    /* what it is waiting for */
    const char *head = "", *l1 = "", *l2 = "";
    SDL_Color hc = UI_ACC;
    char errline[200];
    switch (g_menu_state) {
    case MENU_IDLE:
        head = "Not connected";
        l1 = "Select Connect to look for the Switch.";
        l2 = "Choose USB or Network above first.";
        break;
    case MENU_WAITING:
        if (conn_for_menu()) {
            head = "Looking for the Switch";
            l1 = "Set Connection to Network in the Switch Remote Frame app on the Switch too.";
            if (!ip_edit_for_menu()) { snprintf(errline, sizeof(errline), "%s", net_status_for_menu()); l2 = errline; }
        } else {
            head = "Waiting for the Switch";
            l1 = "Connect it to this PC with a USB-C cable, in handheld mode.";
            l2 = "Playing docked? Choose Network.";
        }
        break;
    case MENU_NODRIVER:
        hc = UI_WARN;
#ifdef _WIN32
        head = "The Switch needs its USB driver";
        l1 = "Install WinUSB for it once with Zadig - see README-WINDOWS.txt.";
#elif defined(__APPLE__)
        head = "The Switch cannot be opened";
        l1 = "Is another program using it (another viewer, OBS)? Close it, or plug the cable in again.";
#else
        head = "The Switch cannot be opened";
        l1 = "Install the USB rule (99-switch-remote-frame.rules) - see the README.";
#endif
        snprintf(errline, sizeof(errline), "(%s)", g_menu_err);
        l2 = errline;
        break;
    case MENU_WRONG_DEVICE:
        hc = UI_WARN;
        head = "Another USB program has the Switch";
        snprintf(errline, sizeof(errline),
                 "The PC sees \"%s\". Remove or disable it on the SD card, install Switch Remote Frame, and reboot the console.",
                 g_menu_other[0] ? g_menu_other : "SysDVR");
        l1 = errline;
        l2 = "Or set Connection to Network in the Switch Remote Frame app and use Network here (Tab).";
        break;
    case MENU_PAUSED:
        hc = UI_GOOD;
        head = "Connected - the game is not on screen";
        l1 = "Go back to the game, or start one, and the picture returns here.";
        l2 = "Nothing? Check that streaming is on in the overlay or the app on the Switch.";
        break;
    default:
        hc = UI_GOOD;
        head = net_peer_for_menu()[0] ? "Connected over the network" : "Connected";
        l1 = "Start a game on the Switch and the picture appears here.";
        l2 = "Nothing? Check that streaming is on in the overlay or the app on the Switch.";
        break;
    }
    char st_head[64], st_detail[120];
    uint32_t st_reason = 0;
    if ((g_menu_state == MENU_CONNECTED || g_menu_state == MENU_PAUSED) &&
        status_for_menu(st_head, sizeof(st_head), st_detail, sizeof(st_detail), &st_reason)) {
        head = st_head;
        l1 = st_detail;
        l2 = "";
        hc = (st_reason == 1 || st_reason == 2 || st_reason == 10) ? UI_GOOD : UI_WARN;
    }
    char title[96], rest[96], first[200];
    snprintf(title, sizeof(title), "%s", head);
    {
        char *dash = strstr(title, " - ");
        if (dash) {
            *dash = 0;
            snprintf(rest, sizeof(rest), "%s", dash + 3);
            if (rest[0] >= 'a' && rest[0] <= 'z') rest[0] = (char)(rest[0] - 32);
            snprintf(first, sizeof(first), "%s", l1);
            l2 = first;
            l1 = rest;
        }
    }

    /* Floating panel sized to content (previous frame's measure), capped by the
     * window - scrolls only when content cannot fit. */
    const float margin = 16.f, chrome_top = 52.f, chrome_bot = 36.f;
    float avail_w = (float)ow - 2.f * margin;
    float avail_h = (float)oh - chrome_top - chrome_bot;
    if (avail_w < 200.f) avail_w = (float)ow > 8 ? (float)ow - 8 : (float)ow;
    if (avail_h < 160.f) avail_h = (float)oh > 8 ? (float)oh - 8 : (float)oh;

    const float design_w = 900.f;
    /* Density from width first; height follows content rather than a fixed card. */
    const float fit_w = avail_w / design_w;
    if (fit_w >= 1.0f) g_ui_d = 1.0f;
    else if (fit_w >= 0.78f) g_ui_d = 0.85f;
    else g_ui_d = 0.70f;

    float pw = design_w * g_ui_d;
    if (pw > avail_w) pw = avail_w;

    const float pad_y = ui_d(32); /* top+bottom padding inside the panel */
    float ph;
    if (g_home_content_h > 1.f) {
        ph = g_home_content_h + pad_y;
        if (ph < ui_d(200)) ph = ui_d(200);
    } else {
        ph = ui_d(360); /* first frame before measure */
    }
    if (ph > avail_h) ph = avail_h;

    float x0 = ((float)ow - pw) * 0.5f;
    float y0 = chrome_top + (avail_h - ph) * 0.5f;
    if (x0 < margin) x0 = margin;
    if (y0 < chrome_top) y0 = chrome_top;
    if (x0 + pw > (float)ow - margin) x0 = (float)ow - margin - pw;
    if (y0 + ph > (float)oh - chrome_bot) y0 = (float)oh - chrome_bot - ph;
    if (x0 < 0) x0 = 0;
    if (y0 < 0) y0 = 0;

    const float cx = x0 + pw * 0.5f;
    const float pad = ui_d(28);
    const float top = y0 + ui_d(16), bottom = y0 + ph - ui_d(16);
    const float view_h = bottom - top;
    if (g_home_scroll > g_home_content_h - view_h) g_home_scroll = g_home_content_h - view_h;
    if (g_home_scroll < 0) g_home_scroll = 0;

    /* Panel hit excludes the scrollbar strip so drag does not steal thumb clicks. */
    const float sb_reserve = 22.f;
    ui_hit(x0, y0, pw - sb_reserve, ph, H_PANEL, 0);
    ui_surface(ren, x0, y0, pw, ph, ui_d(20), UI_SURF);   /* no border: the panel nearly fills the window */

    SDL_Rect clip = { (int)x0, (int)top, (int)pw + 1, (int)(view_h > 1.f ? view_h : 1.f) };
    SDL_RenderSetClipRect(ren, &clip);
    const int first_hit = g_ui_nhits;
    float y = top - g_home_scroll;
    const float y_start = y;

    const double t = (double)SDL_GetTicks() / 1000.0;
    const int pulse = (g_menu_state == MENU_WAITING) ? (int)(60 + 50 * (0.5 + 0.5 * sin(t * 3.0))) : 70;
    const int wide = pw >= ui_d(760);
    const float inner = pw - pad * 2.f - sb_reserve;
    if (wide) {
        const float gap = ui_d(28), left_w = (inner - gap) * 0.48f;
        const float left = x0 + pad, right = left + left_w + gap;
        const float right_w = inner - left_w - gap;
        float ly = y + ui_d(18), ry = y + ui_d(16);
        /* Brand/status column: a clear welcome hierarchy rather than a centered stack. */
        ui_rrect(ren, left, ly, ui_d(12), ui_d(12), ui_d(6), ui_alpha(hc, pulse));
        text_draw(ren, g_menu_state == MENU_WAITING ? "SEARCHING FOR SWITCH" :
                  (g_menu_state == MENU_CONNECTED ? "SWITCH CONNECTED" : "READY WHEN YOU ARE"),
                  (int)(left + ui_d(22)), (int)(ly - ui_d(2)), ui_px(12), 1, hc, 0);
        ly += ui_d(40);
        int tpx = ui_px(32);
        while (tpx > ui_px(22) && text_width(title, tpx, 1) > (int)(left_w - ui_d(8))) tpx -= 2;
        text_draw(ren, title, (int)left, (int)ly, tpx, 1, UI_INK, 0);
        ly += ui_d(48);
        /* left-aligned detail lines inside the column (the old call centred
         * them on the column's left edge, so the first characters were clipped) */
        ly += ui_wrap(ren, l1, (int)left, (int)ly, ui_px(15), 0, UI_DIM, (int)(left_w - ui_d(8)), (int)ui_d(22), 0);
        if (l2[0]) ly += ui_wrap(ren, l2, (int)left, (int)ly, ui_px(13), 0, UI_FAINT, (int)(left_w - ui_d(8)), (int)ui_d(19), 0);
        ly += ui_d(24);
        text_draw(ren, "QUICK ACCESS", (int)left, (int)ly, ui_px(11), 1, UI_FAINT, 0);
        ly += ui_d(24);
        const char *quick[2] = { "Settings", "Donate on Ko-fi" };
        for (int i = 0; i < 2; ++i) {
            float qy = ly + i * ui_d(42);
            ui_card(ren, left, qy, left_w - ui_d(8), ui_d(34), ui_d(10), UI_SURF2);
            text_draw(ren, i == 0 ? "01" : "02", (int)(left + ui_d(12)), (int)(qy + ui_d(10)), ui_px(11), 1, UI_ACC, 0);
            text_draw(ren, quick[i], (int)(left + ui_d(42)), (int)(qy + ui_d(8)), ui_px(14), 1, i == 1 ? UI_HEART : UI_INK, 0);
            ui_hit(left, qy, left_w - ui_d(8), ui_d(34), i == 0 ? H_SETTINGS : H_DONATE, 0);
        }
        ly += 2 * ui_d(42) + ui_d(8);   /* advance past both cards: the cursor used to stop above them */
        /* Connection is a distinct work area with mode and primary action grouped.
         * Editing the IP adds a Confirm/Cancel row, so the card grows with it. */
        const float conn_card_h = ui_d(254) + (conn_for_menu() && ip_edit_for_menu() ? ui_d(48) : 0);
        ui_card(ren, right, ry, right_w, conn_card_h, ui_d(18), UI_SURF2);
        text_draw(ren, "CONNECTION", (int)(right + ui_d(18)), (int)(ry + ui_d(16)), ui_px(11), 1, UI_FAINT, 0);
        text_draw(ren, "Choose how to connect", (int)(right + ui_d(18)), (int)(ry + ui_d(38)), ui_px(17), 1, UI_INK, 0);
        ry += ui_d(68);
        static const char *const conn[] = { "USB", "Network" };
        ui_seg(ren, -1, right + right_w * .5f, ry, conn, 2, conn_for_menu(), H_CONN, 14, right_w - ui_d(36));
        ry += ui_d(48);
        if (conn_for_menu()) {
            text_draw(ren, "Switch IP address", (int)(right + ui_d(18)), (int)(ry + ui_d(4)), ui_px(12), 0, UI_DIM, 0);
            ry += ui_d(22);
            ry += ui_ip_field(ren, right + ui_d(18), ry, right_w - ui_d(36), act_ip_possible()) + ui_d(8);
        }
        const int req = conn_requested_for_menu();
        const float bx = right + ui_d(18), bw = right_w - ui_d(36), bh = ui_d(42);
        ui_rrect(ren, bx, ry, bw, bh, bh * .5f, req ? UI_SURF3 : UI_ACC);
        text_draw(ren, req ? "Disconnect" : "Connect to Switch", (int)(bx + bw * .5f), (int)(ry + ui_d(12)), ui_px(15), 1, req ? UI_WARN : UI_ONACC, 1);
        ui_hit(bx, ry, bw, bh, H_CONNECT, 0);
        y = (ly > ry + bh ? ly : ry + bh) + ui_d(18);
    } else {
        /* Narrow windows deliberately switch to a vertical flow. */
        ui_rrect(ren, cx - ui_d(20), y + ui_d(5), ui_d(40), ui_d(40), ui_d(20), ui_alpha(hc, pulse));
        ui_rrect(ren, cx - ui_d(8), y + ui_d(17), ui_d(16), ui_d(16), ui_d(8), hc);
        y += ui_d(58);
        int tpx = ui_px(27);
        while (tpx > ui_px(18) && text_width(title, tpx, 1) > (int)(pw - pad * 2)) tpx -= 2;
        text_draw(ren, title, (int)cx, (int)y, tpx, 1, UI_INK, 1); y += ui_d(36);
        y += ui_wrap(ren, l1, (int)cx, (int)y, ui_px(15), 0, UI_DIM, (int)(pw - pad * 2), (int)ui_d(21), 1);
        if (l2[0]) y += ui_wrap(ren, l2, (int)cx, (int)y, ui_px(13), 0, UI_FAINT, (int)(pw - pad * 2), (int)ui_d(19), 1);
        y += ui_d(12);
        static const char *const conn[] = { "USB", "Network" };
        ui_seg(ren, -1, cx, y, conn, 2, conn_for_menu(), H_CONN, 14, pw - pad * 2); y += ui_d(40);
        if (conn_for_menu()) {
            const float fw = pw - pad * 2;
            text_draw(ren, "Switch IP address", (int)(cx - fw / 2), (int)y, ui_px(12), 0, UI_DIM, 0);
            y += ui_d(20);
            y += ui_ip_field(ren, cx - fw / 2, y, fw, act_ip_possible()) + ui_d(6);
        }
        const int req = conn_requested_for_menu();
        ui_button(ren, -1, cx, y, req ? "Disconnect" : "Connect", req ? UI_SURF3 : UI_ACC, req ? UI_WARN : UI_ONACC, H_CONNECT, 0, 1); y += ui_d(42);
        const float half = (inner - ui_d(10)) * .5f;
        ui_button(ren, cx, cx - half * .5f, y, "Settings", UI_SURF3, UI_INK, H_SETTINGS, 0, 1);
        ui_button(ren, -1, cx + half * .5f, y, "Donate on Ko-fi", UI_HEART, UI_ONACC, H_DONATE, 0, 1); y += ui_d(42);
    }

    /* v0.7.14: a newer viewer on GitHub (update.h) */
    {
        const int us = upd_state();
        if (us == UPD_FOUND || us == UPD_GETTING || us == UPD_READY || (us == UPD_ERROR && g_upd.failed_update)) {
            char lb[96];
            if (us == UPD_FOUND) snprintf(lb, sizeof(lb), "Update to %s", g_upd.tag);
            else if (us == UPD_GETTING) snprintf(lb, sizeof(lb), "Downloading %s...", g_upd.tag);
            else if (us == UPD_READY) snprintf(lb, sizeof(lb), "Restart to use %s", g_upd.tag);
            else snprintf(lb, sizeof(lb), "Update failed - try again");
            y += ui_d(4);
            ui_button(ren, -1, cx, y, lb, us == UPD_ERROR ? UI_SURF3 : UI_ACC, us == UPD_ERROR ? UI_WARN : UI_ONACC, H_UPD, 0, us != UPD_GETTING);
            y += ui_d(40);
            if (us == UPD_ERROR) {
                y += ui_wrap(ren, g_upd.msg, (int)cx, (int)y, ui_px(13), 0, UI_FAINT, (int)(pw - pad * 2), (int)ui_d(18), 1);
            }
        }
    }

    y += ui_d(12);
    {
        struct { const char *k, *what; } hints[] = {
            { "Esc", "Settings" }, { "F11", "Fullscreen" }, { "R", "Record" }, { "Tab", "USB / Network" }
        };
        float widths[4], gap = ui_d(16), total = 0;
        for (int i = 0; i < 4; i++) {
            widths[i] = text_width(hints[i].k, ui_px(12), 1) + ui_d(12) + ui_d(6) + text_width(hints[i].what, ui_px(13), 0);
            total += widths[i] + (i ? gap : 0);
        }
        const float inner = pw - pad * 2.f - sb_reserve;
        int per_row = total <= inner ? 4 : 2;
        float row_y = y;
        for (int start = 0; start < 4; start += per_row) {
            float row_w = 0;
            int n = start + per_row <= 4 ? per_row : 4 - start;
            for (int j = 0; j < n; j++) row_w += widths[start + j] + (j ? gap : 0);
            float x = cx - row_w * 0.5f;
            for (int j = 0; j < n; j++) {
                int i = start + j;
                x += ui_key(ren, x, row_y, hints[i].k) + ui_d(6);
                text_draw(ren, hints[i].what, (int)x, (int)(row_y + ui_d(2)), ui_px(13), 0, UI_DIM, 0);
                x += text_width(hints[i].what, ui_px(13), 0) + gap;
            }
            row_y += ui_d(30);
        }
        y = row_y + ui_d(6);
    }
    y += ui_d(12);
    g_home_content_h = y - y_start;
    SDL_RenderSetClipRect(ren, NULL);

    for (int i = first_hit; i < g_ui_nhits; i++) {
        SDL_FRect *r = &g_ui_hits[i].r;
        if (g_ui_hits[i].id == H_PANEL) continue;
        if (r->y + r->h < top || r->y > bottom) { r->w = 0; r->h = 0; continue; }
        if (r->y < top) { r->h -= top - r->y; r->y = top; }
        if (r->y + r->h > bottom) r->h = bottom - r->y;
    }
    ui_scrollbar(ren, x0 + pw - ui_d(16), top, view_h, g_home_content_h, &g_home_scroll);

    /* Corner chrome drawn last so it stays above the connection panel.
     * Brand uses the same density tier as the home panel. */
    {
        const float dot = ui_d(10), bx = ui_d(16), by = ui_d(16);
        ui_rrect(ren, bx, by, dot, dot, dot * 0.5f, UI_ACC);
        text_draw(ren, "Switch Remote Frame", (int)(bx + ui_d(20)), (int)(by - ui_d(4)), ui_px(18), 1, UI_INK, 0);
    }
    {
        const char *dir = rec_dir_for_menu();
        if (dir[0]) {
            char line[1100];
            snprintf(line, sizeof(line), "Recordings: %s", dir);
            ui_text_fit(ren, line, (int)ui_d(16), oh - (int)ui_d(28), ui_px(12), 0, UI_FAINT, 0, ow > 40 ? ow - 32 : 200);
        }
        if (g_mute) text_draw(ren, "Sound muted (M)", ow - (int)ui_d(16), oh - (int)ui_d(28), ui_px(12), 0, UI_WARN, 2);
        if (rec_active()) {
            char rs[200];
            rec_status(rs, sizeof(rs));
            const char *r = rs + 5;
            const float w = text_width(r, ui_px(13), 1) + ui_d(36);
            ui_rrect(ren, (ow - w) * 0.5f, ui_d(16), w, ui_d(26), ui_d(13), ui_alpha(UI_BAD, 40));
            ui_rrect(ren, (ow - w) * 0.5f + ui_d(10), ui_d(24), ui_d(8), ui_d(8), ui_d(4), UI_BAD);
            text_draw(ren, r, (int)((ow - w) * 0.5f + ui_d(24)), (int)ui_d(20), ui_px(13), 1, UI_BAD, 0);
        }
    }
    /* Home: always paint + hit-test chrome (connection screen needs it visible). */
    if (!g_ui_open) ui_top_chrome_fixed(ren, UI_SURF2, 1);

    g_ui_hit_window = 0;
    /* The composite path (present == 0, viewer_repaint) paints the popout once
     * itself via ui_overlay() right after this; drawing it here too meant the
     * veil was composited twice on event-driven frames but once on the home
     * loop's 50 ms frames, so the dark overlay flickered while scrolling. */
    if (g_ui_open && present) ui_settings_popout(ren);
    else if (!g_ui_open) g_ui_d = 1.f;
    /* tests: --shot with --seconds also saves the home screen (no picture came) */
    {
        static Uint32 t0 = 0;
        if (!t0) t0 = SDL_GetTicks();
        if (g_shot && g_run_secs > 0 && SDL_GetTicks() - t0 >= (Uint32)(g_run_secs * 1000.0)) {
            SDL_Surface *sf = SDL_CreateRGBSurfaceWithFormat(0, ow, oh, 32, SDL_PIXELFORMAT_ARGB8888);
            if (sf && SDL_RenderReadPixels(ren, NULL, SDL_PIXELFORMAT_ARGB8888, sf->pixels, sf->pitch) == 0) {
                SDL_SaveBMP(sf, g_shot);
                fprintf(stderr, "\nsaved the home screen %dx%d to %s\n", ow, oh, g_shot);
            }
            if (sf) SDL_FreeSurface(sf);
            exit(0);
        }
    }
    if (present) SDL_RenderPresent(ren);
}

static void menu_draw(SDL_Renderer *ren) { menu_draw_layer(ren, 1); }

/* ---- input -------------------------------------------------------------- */

static void ui_open(int open)
{
    g_ui_open = open;
    g_ui_drag = 0;
    if (open) { g_ui_kbd = 0; input_release_all(); }   /* v1.0.1: the keys and the mouse are the viewer's again */
    else g_ui_d = 1.f;
}

/* change the row's value: d = -1 / +1 (Left / Right), 0 = Enter */
static void ui_change(SDL_Window *win, SDL_Renderer *ren, int id, int arg, int d)
{
    switch (id) {
    case H_CONN: act_conn(d == 0 ? g_conn != CONN_NET : d > 0); break;
    case H_IP: if (d == 0) act_ip(); break;   /* the field stays inside the drawer */
    case H_IP_OK: act_ip_confirm(); break;    /* v1.1.3: the field's own buttons */
    case H_IP_CANCEL: act_ip_cancel(); break;
    case H_FULL: act_fullscreen(win); break;
    case H_VSYNC: act_vsync(ren, !g_vsync); break;
    case H_ASPECT: act_lock_aspect(win, !g_lock_aspect); break;
    case H_CHROMELESS: act_chromeless(win, !g_chromeless); break;
    case H_HWDEC: act_hwdec(!g_hwdec); break;
    case H_UPS: act_upscaler(ren, d < 0 ? (g_upscaler + UP_COUNT - 1) % UP_COUNT : (g_upscaler + 1) % UP_COUNT); break;
    case H_HUD: act_hud(d < 0 ? (g_hud + HUD_MODES - 1) % HUD_MODES : (g_hud + 1) % HUD_MODES); break;
    case H_FSR_SHARP:
        g_fsr_sharpness = d == 0 ? 70 : SDL_clamp(g_fsr_sharpness + d * 5, 0, 100);
        prefs_save();
        break;
    case H_PIC: {
        int *f = pic_field(arg);
        if (d < 0 && *f > pic_rows[arg].min) *f -= pic_rows[arg].step;
        if (d > 0 && *f < pic_rows[arg].max) *f += pic_rows[arg].step;
        if (d == 0) *f = *(&pic_default.bright + arg);
        prefs_save();
        break;
    }
    case H_SOUND: act_mute(!g_mute); break;
    case H_TOUCH: g_touch_on = !g_touch_on; prefs_save(); if (!g_touch_on) touch_release_all(); break;
    case H_KB: g_kb_on = !g_kb_on; prefs_save(); held_clear(); break;
    case H_PAD: g_pad_on = !g_pad_on; prefs_save(); break;
    case H_RUMBLE: g_rumble_on = !g_rumble_on; prefs_save(); break;
    case H_VPAD: {
        /* the keys' left / right step through Off, Auto, Always */
        int m = g_vpad_mode + (d > 0 ? 1 : d < 0 ? 2 : 1);
        g_vpad_mode = m > VPAD_ALWAYS ? m - 3 : m;
        prefs_save();
        if (g_vpad_mode == VPAD_OFF) vpad_release();
        break;
    }
    case H_VPAD_LAYOUT: if (d == 0) { ui_open(0); vpad_edit_open(); } break;
    case H_SET_OP: {
        /* v1.0.5: arrows step the slider 5% at a time; Enter/Space resets to 100% */
        int v = d == 0 ? 100 : g_set_op + d * 5;
        g_set_op = v < 0 ? 0 : v > 100 ? 100 : v;
        prefs_save();
        break;
    }
    case H_CONNECT:
        if (d == 0) {
            if (conn_requested_for_menu()) { act_disconnect_for_menu(); ui_open(0); }
            else act_connect_for_menu();
            if (g_menu) menu_draw(ren);
        }
        break;
    case H_GP_PAGE: if (d == 0) { ui_open(0); gp_page_open(); } break;
    case H_REC: if (d == 0) act_record(win); break;
    case H_UPD: if (d == 0) act_update(); break;
    case H_UPD_BETA: g_upd_betas = !g_upd_betas; prefs_save(); if (upd_state() == UPD_LATEST || upd_state() == UPD_FOUND) upd_check(); break;
    case H_UPD_AUTO: g_upd_check = !g_upd_check; prefs_save(); break;
    default: break;
    }
}

/* a slider follows the mouse */
static void ui_slide(float mx)
{
    for (int i = 0; i < g_ui_nhits; i++) {
        if (g_ui_hits[i].id != g_ui_drag || g_ui_hits[i].arg != g_ui_drag_arg) continue;
        const SDL_FRect r = g_ui_hits[i].r;
        if (g_ui_hits[i].window_space) mx = g_ui_wx;
        const float tx = r.x + ui_d(8), tw = r.w - ui_d(16);
        const int a = g_ui_drag_arg;
        const int fsr = g_ui_drag == H_FSR_SHARP;
        const int setop = g_ui_drag == H_SET_OP;            /* v1.0.5 */
        const int lo = fsr || setop ? 0 : pic_rows[a].min;
        const int hi = fsr || setop ? 100 : pic_rows[a].max;
        const int st = setop ? 5 : fsr ? 1 : pic_rows[a].step;
        float f = tw > 1.f ? (mx - tx) / tw : 0.f;
        f = f < 0 ? 0 : f > 1 ? 1 : f;
        int v = lo + (int)lrintf(f * (hi - lo) / st) * st;
        if (setop) g_set_op = v;
        else if (fsr) g_fsr_sharpness = v;
        else *pic_field(a) = v;
        return;
    }
}

/* 1 = the event was the UI's */
static int ui_event(SDL_Window *win, SDL_Renderer *ren, const SDL_Event *e)
{
    if (e->type == SDL_MOUSEMOTION || e->type == SDL_MOUSEBUTTONDOWN || e->type == SDL_MOUSEBUTTONUP || e->type == SDL_MOUSEWHEEL) {
        int wx, wy, ww = 1, wh = 1, ow = 1, oh = 1;
        /* v1.0.3: the event's own position, not SDL_GetMouseState. For a real
         * mouse they agree - and the event's is the position the click actually
         * happened at, rather than wherever the pointer got to while the event
         * waited - but the global is only updated by the window system, so an
         * event from anywhere else would be read as the top-left corner and
         * every click would miss. */
        if (e->type == SDL_MOUSEMOTION) { wx = e->motion.x; wy = e->motion.y; }
        else if (e->type == SDL_MOUSEWHEEL) { SDL_GetMouseState(&wx, &wy); }
        else { wx = e->button.x; wy = e->button.y; }
        /* Map window coords → renderer output pixels (HiDPI). Hits are in output space. */
        if (win) SDL_GetWindowSize(win, &ww, &wh);
        SDL_GetRendererOutputSize(ren, &ow, &oh);
        if (ww < 1) ww = 1;
        if (wh < 1) wh = 1;
        g_ui_wx = (float)wx * (float)ow / (float)ww;
        g_ui_wy = (float)wy * (float)oh / (float)wh;
        float lx = g_ui_wx, ly = g_ui_wy;
        SDL_RenderWindowToLogical(ren, wx, wy, &lx, &ly);
        g_ui_mx = lx / g_ui_k; g_ui_my = ly / g_ui_k;
    }
    if (e->type == SDL_MOUSEMOTION) {
        g_ui_mouse_t = SDL_GetPerformanceCounter();
        g_ui_kbd = 0;
        if (g_ui_drag == H_PIC || g_ui_drag == H_FSR_SHARP || g_ui_drag == H_SET_OP) {
            ui_slide(g_ui_mx);
            prefs_save();
            g_ui_ptr_inside = 1;
            return 1;                       /* the slider is under the pointer: redraw */
        }
        if (g_ui_drag == H_SCROLL) {
            ui_scroll_drag(g_ui_wy);
            g_ui_ptr_inside = 1;
            return 1;
        }
        /* v1.0.3: only the first move asks for a redraw - the one that shows the
         * hover chrome. Every move after it used to return 1 too, which sent the
         * event loop back to the top with a `continue` and so swallowed it before
         * the on-screen gamepad and, under it, the Switch's touch screen ever saw
         * it: the mouse could tap but never drag. While a picture streams the
         * display loop paints the hover chrome itself every frame, so a move that
         * changes nothing here needs no redraw at all. */
        if (!g_ui_ptr_inside) {
            g_ui_ptr_inside = 1;
            return 1;
        }
        return 0;                           /* the pointer is already inside: carry on */
    }
    if (e->type == SDL_MOUSEBUTTONUP) { if (g_ui_drag) { g_ui_drag = 0; return 1; } return 0; }
    if (e->type == SDL_MOUSEWHEEL) {
        if (g_ui_open) { g_ui_scroll -= e->wheel.y * (40.f * g_ui_d); return 1; }
        if (g_menu) { g_home_scroll -= e->wheel.y * (40.f * g_ui_d); return 1; }
    }
    if (e->type == SDL_MOUSEBUTTONDOWN && e->button.button == SDL_BUTTON_LEFT) {
        /* Chrome first, from live geometry - not the last paint's hit list. */
        if (!g_ui_open) {
            int cid = 0;
            if (ui_chrome_at(ren, g_ui_wx, g_ui_wy, &cid)) {
                ui_chrome_activate(win, cid);
                return 1;
            }
        }
        for (int i = g_ui_nhits - 1; i >= 0; i--) {
            const ui_hit_t *h = &g_ui_hits[i];
            const float hx = h->window_space ? g_ui_wx : g_ui_mx;
            const float hy = h->window_space ? g_ui_wy : g_ui_my;
            if (!(hx >= h->r.x && hx < h->r.x + h->r.w && hy >= h->r.y && hy < h->r.y + h->r.h)) continue;
            switch (h->id) {
            case H_SETTINGS: ui_open(1); break;
            case H_CLOSE: ui_open(0); break;
            case H_BACKDROP: return 0; /* don't close; allow window drag underneath */
            case H_WIN_MIN:
            case H_WIN_MAX:
            case H_WIN_CLOSE:
                ui_chrome_activate(win, h->id);
                break;
            case H_PANEL: return 0; /* empty panel chrome: allow chromeless window drag */
            case H_SCROLL:
                g_ui_drag = H_SCROLL;
                ui_scroll_drag(g_ui_wy); /* thumb follows pointer Y */
                break;
            case H_CONN: act_conn(h->arg); break;
            case H_IP: act_ip(); break;        /* type right there in the drawer */
            case H_IP_OK: act_ip_confirm(); break;
            case H_IP_CANCEL: act_ip_cancel(); break;
            case H_FULL: act_fullscreen(win); break;
            case H_VSYNC: act_vsync(ren, h->arg); break;
            case H_ASPECT: act_lock_aspect(win, h->arg); break;
            case H_CHROMELESS: act_chromeless(win, h->arg); break;
            case H_HWDEC: act_hwdec(h->arg); break;
            case H_UPS: act_upscaler(ren, h->arg); break;
            case H_HUD: act_hud(h->arg); break;
            case H_PIC: g_ui_drag = H_PIC; g_ui_drag_arg = h->arg; ui_slide(g_ui_mx); prefs_save(); break;
            case H_FSR_SHARP: g_ui_drag = H_FSR_SHARP; g_ui_drag_arg = 0; ui_slide(g_ui_mx); prefs_save(); break;
            case H_PIC_RESET: g_pic = pic_default; prefs_save(); break;
            case H_SOUND: act_mute(!h->arg); break;
            case H_TOUCH: g_touch_on = h->arg; prefs_save(); if (!g_touch_on) touch_release_all(); break;
            case H_KB: g_kb_on = h->arg; prefs_save(); held_clear(); break;
            case H_PAD: g_pad_on = h->arg; prefs_save(); break;
            case H_RUMBLE: g_rumble_on = h->arg; prefs_save(); break;
            case H_VPAD:
                g_vpad_mode = h->arg; prefs_save();
                if (g_vpad_mode == VPAD_OFF) vpad_release();
                break;
            case H_VPAD_LAYOUT: ui_open(0); vpad_edit_open(); break;
            /* v1.0.3: one button, both ways - Connect while idle, Disconnect
             * while it is looking or connected, as the Android app's pill */
            case H_CONNECT:
                if (conn_requested_for_menu()) { act_disconnect_for_menu(); ui_open(0); }
                else act_connect_for_menu();
                if (g_menu) menu_draw(ren);
                break;
            /* v1.0.5: a slider - pressing anywhere on the row jumps the knob
             * there and the pointer can drag it from 100% down to 0% */
            case H_SET_OP: g_ui_drag = H_SET_OP; g_ui_drag_arg = 0; ui_slide(g_ui_mx); prefs_save(); break;
            case H_GP_PAGE: ui_open(0); gp_page_open(); break;
            case H_REC: act_record(win); break;
            case H_DONATE: SDL_OpenURL("https://ko-fi.com/monkadev"); break;   /* v1.0.5 */
            case H_UPD: act_update(); break;
            case H_UPD_BETA: g_upd_betas = !g_upd_betas; prefs_save(); if (upd_state() == UPD_LATEST || upd_state() == UPD_FOUND) upd_check(); break;
            case H_UPD_AUTO: g_upd_check = !g_upd_check; prefs_save(); break;
            }
            return 1;
        }
        return g_ui_open;                   /* with the drawer open, nothing else takes clicks */
    }
    if (e->type != SDL_KEYDOWN) return 0;
    const SDL_Keycode k = e->key.keysym.sym;
    if (k == SDLK_ESCAPE) { ui_open(!g_ui_open); return 1; }
    if (k == SDLK_p && !e->key.repeat) {    /* the picture controls */
        if (g_ui_open && g_ui_kbd && g_ui_focus < g_ui_nrows && g_ui_rows[g_ui_focus].id == H_PIC) { ui_open(0); return 1; }
        ui_open(1);
        g_ui_kbd = 1;
        for (int i = 0; i < g_ui_nrows; i++) if (g_ui_rows[i].id == H_PIC) { g_ui_focus = i; g_ui_scroll_to = i + PIC_N - 1; break; }
        if (!g_ui_nrows) { g_ui_focus = 0; }
        return 1;
    }
    if (!g_ui_open) return 0;
    if (g_ui_nrows == 0) return 1;
    if (g_ui_focus >= g_ui_nrows) g_ui_focus = g_ui_nrows - 1;
    switch (k) {
    case SDLK_UP: if (g_ui_kbd) g_ui_focus = (g_ui_focus + g_ui_nrows - 1) % g_ui_nrows; g_ui_kbd = 1; g_ui_scroll_to = g_ui_focus; return 1;
    case SDLK_DOWN: if (g_ui_kbd) g_ui_focus = (g_ui_focus + 1) % g_ui_nrows; g_ui_kbd = 1; g_ui_scroll_to = g_ui_focus; return 1;
    case SDLK_LEFT: case SDLK_RIGHT: case SDLK_RETURN: case SDLK_KP_ENTER: case SDLK_SPACE:
        if (!g_ui_kbd) { g_ui_kbd = 1; return 1; }
        ui_change(win, ren, g_ui_rows[g_ui_focus].id, g_ui_rows[g_ui_focus].arg,
                  k == SDLK_LEFT ? -1 : k == SDLK_RIGHT ? 1 : 0);
        return 1;
    case SDLK_BACKSPACE: case SDLK_DELETE:
        if (g_ui_kbd && g_ui_rows[g_ui_focus].id == H_PIC) ui_change(win, ren, H_PIC, g_ui_rows[g_ui_focus].arg, 0);
        if (g_ui_kbd && g_ui_rows[g_ui_focus].id == H_FSR_SHARP) ui_change(win, ren, H_FSR_SHARP, 0, 0);
        if (g_ui_kbd && g_ui_rows[g_ui_focus].id == H_SET_OP) ui_change(win, ren, H_SET_OP, 0, 0);
        return 1;
    case SDLK_0: case SDLK_KP_0: g_pic = pic_default; prefs_save(); return 1;
    default: return 0;                      /* the shortcuts still work with the drawer open */
    }
}
