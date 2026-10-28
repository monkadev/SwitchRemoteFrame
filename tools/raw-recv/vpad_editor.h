/* vpad_editor.h - v1.0.2: the on-screen gamepad's layout editor, the PC port of
 * the Android app's VirtualEditor.
 *
 * The controls are dragged where they should be. A panel on the right holds the
 * rest - opacity, adding a removed control back, Reset, Cancel and Done; it
 * slides away with its chevron and the pencil in the top right brings it back.
 * Clicking a control selects it: a bubble above it - one at a time - makes it
 * smaller or bigger, or removes it. Nothing is kept until Done.
 *
 * It draws in the picture's own units, like the pad, and keeps its own hit list
 * (the settings drawer's is for the drawer).
 */
#pragma once

enum { VE_NONE = 0, VE_DRAG, VE_SEL, VE_SHRINK, VE_GROW, VE_REMOVE, VE_OPACITY, VE_ADD,
       VE_RESET, VE_CANCEL, VE_DONE, VE_PANEL_HIDE, VE_PANEL_SHOW };

typedef struct { SDL_FRect r; int id, arg; } ve_hit_t;
#define VE_HITS 96
static ve_hit_t g_ve_hits[VE_HITS];
static int g_ve_nhits;

static vp_layout_t g_ve_layout;        /* a copy: Cancel throws it away */
static float g_ve_opacity = 0.5f;
static int g_ve_sel = -1;              /* the selected control, -1 for none */
static int g_ve_panel = 1;             /* the right-hand panel is out */
static float g_ve_scroll = 0;          /* the ADD list's own scroll */
static float g_ve_content_h = 0;       /* the ADD list's content height */
static float g_ve_list_h = 0;          /* the viewport the list scrolls in */
static int g_ve_drag = -1;             /* the control being dragged */
static float g_ve_drag_dx = 0, g_ve_drag_dy = 0;
static SDL_FRect g_ve_bubble;
static SDL_FRect g_ve_panel_r;

#define VE_PANEL_W 232.f

static void ve_hit(SDL_FRect r, int id, int arg)
{
    if (g_ve_nhits >= VE_HITS) return;
    g_ve_hits[g_ve_nhits].r = r;
    g_ve_hits[g_ve_nhits].id = id;
    g_ve_hits[g_ve_nhits].arg = arg;
    g_ve_nhits++;
}

static void vpad_edit_open(void)
{
    g_ve_layout = g_vpad_layout;
    g_ve_opacity = g_vpad_opacity;
    g_ve_sel = -1;
    g_ve_drag = -1;
    g_ve_panel = 1;
    g_ve_scroll = 0;
    vpad_release();
    g_ve_open = 1;
}

static void vpad_edit_close(void)
{
    g_ve_open = 0;
    g_ve_sel = -1;
    g_ve_drag = -1;
}

/* Done: the copy becomes the layout, and the pad is rebuilt from it. */
static void vpad_edit_done(void)
{
    g_vpad_layout = g_ve_layout;
    g_vpad_opacity = g_ve_opacity;
    g_vpad_hidden = 0;                 /* the pad the user just arranged is shown */
    vpad_release();
    g_vpad_placed = 0;
    vpad_edit_close();
    prefs_save();
}

static void ve_resize(int di)
{
    if (g_ve_sel < 0) return;
    vp_item_t *it = &g_ve_layout.items[g_ve_sel];
    it->scale = vp_clamp_scale(it->scale + (float)di * VP_SCALE_STEP);
}

static void ve_remove_selected(void)
{
    if (g_ve_sel < 0) return;
    g_ve_layout.items[g_ve_sel].shown = 0;
    g_ve_sel = -1;
}

/* ---- drawing -------------------------------------------------------------- */

static void ve_section(SDL_Renderer *ren, float x, float *y, const char *name)
{
    *y += 14.f;
    text_draw(ren, name, (int)x, (int)*y, 12, 1, UI_FAINT, 0);
    *y += 24.f;
}

static void ve_button(SDL_Renderer *ren, float x, float y, float w, float h, const char *label,
                      SDL_Color fillc, SDL_Color ink, int id, int arg)
{
    ui_rrect(ren, x, y, w, h, h * 0.28f, fillc);
    text_draw(ren, label, (int)(x + w * 0.5f), (int)(y + h * 0.5f - 7.f), 13, 1, ink, 1);
    SDL_FRect r = { x, y, w, h };
    ve_hit(r, id, arg);
}

/* the editor's controls, drawn exactly as the pad will draw them */
static void ve_draw_controls(SDL_Renderer *ren)
{
    /* Draw with the pad's own code, against the editor's copy. The layout and
     * the boxes are left pointing at the copy while the editor is open, so the
     * bubble and the selection ring agree with what was just drawn; the pad is
     * rebuilt from the real layout when the editor closes. */
    const int saved_hidden = g_vpad_hidden, saved_mode = g_vpad_mode;
    const float saved_op = g_vpad_opacity;
    g_vpad_layout = g_ve_layout;
    g_vpad_opacity = g_ve_opacity;
    g_vpad_hidden = 0;
    g_vpad_mode = VPAD_ALWAYS;
    vpad_place(ren);
    for (int i = 0; i < VP_N; i++) {
        if (!g_ve_layout.items[i].shown) continue;
        vpad_draw_control(ren, i, g_ve_opacity);
        /* the selection ring */
        if (i == g_ve_sel) {
            const SDL_FRect *b = &g_vpad_box[i];
            SDL_SetRenderDrawColor(ren, UI_ACC.r, UI_ACC.g, UI_ACC.b, 220);
            const SDL_FRect o = { b->x - 4, b->y - 4, b->w + 8, b->h + 8 };
            SDL_RenderDrawRectF(ren, &o);
        }
        SDL_FRect r = g_vpad_box[i];
        /* v1.0.5: every control takes a drag from the first press - like the
         * Android editor: down selects and grabs (keeping its offset), the
         * pointer moves it, up lets go. A plain click is just a select. */
        ve_hit(r, VE_DRAG, i);
    }
    g_vpad_hidden = saved_hidden;
    g_vpad_mode = saved_mode;
    g_vpad_opacity = saved_op;
}

/* the bubble over the selected control: smaller, bigger, remove */
static void ve_draw_bubble(SDL_Renderer *ren)
{
    if (g_ve_sel < 0) return;
    const SDL_FRect *b = &g_vpad_box[g_ve_sel];
    const float bw = 132.f, bh = 40.f;
    float x = b->x + b->w * 0.5f - bw * 0.5f;
    float y = b->y - bh - 12.f;
    if (y < 4.f) y = b->y + b->h + 12.f;      /* no room above: below */
    if (x < 4.f) x = 4.f;
    ui_rrect(ren, x, y, bw, bh, 10.f, UI_SURF2);
    g_ve_bubble.x = x; g_ve_bubble.y = y; g_ve_bubble.w = bw; g_ve_bubble.h = bh;
    const float cw = bw / 3.f;
    ve_button(ren, x + 4, y + 5, cw - 6, bh - 10, "-", UI_SURF3, UI_INK, VE_SHRINK, 0);
    ve_button(ren, x + cw + 2, y + 5, cw - 6, bh - 10, "+", UI_SURF3, UI_INK, VE_GROW, 0);
    ve_button(ren, x + cw * 2, y + 5, cw - 6, bh - 10, "X", UI_SURF3, UI_BAD, VE_REMOVE, 0);
}

/* The panel on the right.
 *
 * v1.0.4's shape: the actions are fixed at the bottom - Reset, then Cancel and
 * Done, then Hide in the panel's bottom-right corner - and the ADD list takes
 * whatever height is left between the opacity buttons and them, scrolling
 * inside its own viewport. Nothing you came here to press can scroll out of
 * reach, and the list cannot push the buttons off the panel however many
 * controls are missing. */
static void ve_draw_panel(SDL_Renderer *ren, float w, float h)
{
    if (!g_ve_panel) {
        /* the button that brings the panel back, in the screen's bottom-right
         * corner - the mirror of the pad's own Show/Hide at the bottom left,
         * with the word on it because an arrow alone does not say what it is for */
        const float bw = 78.f, bh = 34.f;
        const SDL_FRect r = { w - bw - 8.f, h - bh - 8.f, bw, bh };
        ui_rrect(ren, r.x, r.y, r.w, r.h, 10.f, UI_SURF2);
        const float cy = r.y + bh * 0.5f, ax = r.x + bw - 14.f;
        SDL_SetRenderDrawColor(ren, UI_INK.r, UI_INK.g, UI_INK.b, UI_INK.a);
        for (int i = 0; i < 6; i++) SDL_RenderDrawPointF(ren, ax - 3.f + (float)i, cy + 3.f - (float)i);
        text_draw(ren, "Show", (int)(r.x + 12.f), (int)(cy - 7.f), 13, 1, UI_INK, 0);
        ve_hit(r, VE_PANEL_SHOW, 0);
        return;
    }
    const float pw = VE_PANEL_W;
    g_ve_panel_r.x = w - pw; g_ve_panel_r.y = 0; g_ve_panel_r.w = pw; g_ve_panel_r.h = h;
    ui_rrect(ren, w - pw, 0, pw, h, 0, (SDL_Color){ UI_SURF.r, UI_SURF.g, UI_SURF.b, 248 });
    SDL_SetRenderDrawColor(ren, UI_LINE.r, UI_LINE.g, UI_LINE.b, UI_LINE.a);
    SDL_RenderDrawLineF(ren, w - pw, 0, w - pw, h);

    const float x = w - pw + 14.f, cw = pw - 28.f;

    /* ---- the bottom block, from the corner up: Hide, Cancel|Done, Reset ---- */
    const float hide_w = 68.f, hide_h = 26.f;
    const float hide_y = h - 10.f - hide_h;
    const float act_y = hide_y - 6.f - 34.f;         /* Cancel | Done */
    const float reset_y = act_y - 6.f - 34.f;        /* Reset layout */

    /* ---- the fixed top: title, hints, OPACITY ---- */
    float y = 12.f;
    text_draw(ren, "Customize controls", (int)x, (int)y, 17, 1, UI_INK, 0);
    y += 27.f;
    text_draw(ren, "Drag to move · select for options", (int)x, (int)y, 12, 0, UI_DIM, 0);
    y += 24.f;

    ve_section(ren, x, &y, "OPACITY");
    {
        const float bw = (cw - 3 * 4.f) / 4.f;
        char t[8];
        for (int i = 0; i < 4; i++) {
            const int sel = (int)(g_ve_opacity * 100.f + 0.5f) == (int)(vpad_opacities[i] * 100.f + 0.5f);
            snprintf(t, sizeof(t), "%d%%", (int)(vpad_opacities[i] * 100.f + 0.5f));
            ve_button(ren, x + i * (bw + 4.f), y, bw, 30.f, t, sel ? UI_ACC : UI_SURF2,
                      sel ? (SDL_Color){ 8, 20, 30, 255 } : UI_INK, VE_OPACITY, i);
        }
        y += 38.f;
    }

    /* ---- ADD: the list scrolls in whatever height is left ---- */
    ve_section(ren, x, &y, "ADD");
    {
        const float list_top = y;
        float list_h = reset_y - 10.f - list_top;
        if (list_h < 40.f) list_h = 40.f;            /* a stub of a window: still a list */
        int any = 0;
        float content = 0;
        for (int i = 0; i < VP_N; i++) if (!g_ve_layout.items[i].shown) { any = 1; content += 36.f; }
        if (!any) content = 20.f;
        const float max = content > list_h ? content - list_h : 0;
        if (g_ve_scroll < 0) g_ve_scroll = 0;
        if (g_ve_scroll > max) g_ve_scroll = max;
        g_ve_content_h = content;
        g_ve_list_h = list_h;

        const SDL_Rect clip = { (int)x, (int)list_top, (int)cw, (int)list_h };
        SDL_RenderSetClipRect(ren, &clip);
        float iy = list_top - g_ve_scroll;
        if (!any) {
            text_draw(ren, "Every control is on the screen.", (int)x, (int)iy, 12, 0, UI_FAINT, 0);
        }
        for (int i = 0; i < VP_N; i++) {
            if (g_ve_layout.items[i].shown) continue;
            /* only a row inside the viewport is drawn and clickable - the rest
             * is scrolled away, and a hit there would be a press the user never
             * saw the button for */
            if (iy >= list_top && iy + 32.f <= list_top + list_h)
                ve_button(ren, x, iy, cw, 32.f, vp_name(g_ve_layout.items[i].id), UI_SURF2, UI_INK, VE_ADD, i);
            iy += 36.f;
        }
        SDL_RenderSetClipRect(ren, NULL);
    }

    /* ---- the fixed bottom block, painted last so it is over everything ---- */
    ve_button(ren, x, reset_y, cw, 34.f, "Reset layout", UI_SURF2, UI_INK, VE_RESET, 0);
    {
        const float bw = (cw - 6.f) * 0.5f;
        ve_button(ren, x, act_y, bw, 34.f, "Cancel", UI_SURF2, UI_INK, VE_CANCEL, 0);
        ve_button(ren, x + bw + 6.f, act_y, bw, 34.f, "Done", UI_ACC, (SDL_Color){ 8, 20, 30, 255 }, VE_DONE, 0);
    }
    {
        /* "Hide" beside the arrow, in the panel's bottom-right corner, so the
         * button says what it does and stays out of the actions' way */
        const SDL_FRect hide = { w - 10.f - hide_w, hide_y, hide_w, hide_h };
        ui_rrect(ren, hide.x, hide.y, hide.w, hide.h, 8.f, UI_SURF3);
        const float hcy = hide.y + hide_h * 0.5f, hax = hide.x + hide_w - 13.f;
        SDL_SetRenderDrawColor(ren, UI_INK.r, UI_INK.g, UI_INK.b, UI_INK.a);
        for (int i = 0; i < 6; i++) SDL_RenderDrawPointF(ren, hax + 3.f - (float)i, hcy - 3.f + (float)i);
        text_draw(ren, "Hide", (int)(hide.x + 10.f), (int)(hcy - 7.f), 12, 1, UI_INK, 0);
        ve_hit(hide, VE_PANEL_HIDE, 0);
    }
}

static void vpad_editor_draw(SDL_Renderer *ren)
{
    float w, h;
    vpad_view(ren, &w, &h);
    g_ve_nhits = 0;
    text_init(ren);
    SDL_BlendMode old;
    SDL_GetRenderDrawBlendMode(ren, &old);
    SDL_SetRenderDrawBlendMode(ren, SDL_BLENDMODE_BLEND);
    /* the game stays visible behind */
    ui_rrect(ren, 0, 0, w, h, 0, (SDL_Color){ 0, 0, 0, 90 });
    /* v1.0.3: say so, in the middle - a click here moves a control rather than
     * reaching the Switch, and nothing else on the screen told the user that */
    text_draw(ren, "EDIT MODE", (int)(w * 0.5f), (int)(h * 0.5f - 14.f), 30, 1,
              (SDL_Color){ UI_INK.r, UI_INK.g, UI_INK.b, 66 }, 1);
    ve_draw_controls(ren);
    ve_draw_bubble(ren);
    ve_draw_panel(ren, w, h);
    SDL_SetRenderDrawBlendMode(ren, old);
}

/* the panel's scroll wheel */
static int vpad_editor_wheel(SDL_Renderer *ren, float dy)
{
    if (!g_ve_open || !g_ve_panel) return 0;
    float w, h;
    vpad_view(ren, &w, &h);
    const float max = g_ve_content_h - g_ve_list_h;   /* the list's own scroll */
    if (max <= 0) return 1;
    g_ve_scroll += dy;
    if (g_ve_scroll < 0) g_ve_scroll = 0;
    if (g_ve_scroll > max) g_ve_scroll = max;
    return 1;
}

/* The editor takes every mouse event while it is open. Returns 1 when it did. */
static int vpad_editor_event(SDL_Renderer *ren, const SDL_Event *e)
{
    if (!g_ve_open) return 0;
    if (e->type == SDL_MOUSEWHEEL) return vpad_editor_wheel(ren, -(float)e->wheel.y * 40.f);

    if (e->type != SDL_MOUSEBUTTONDOWN && e->type != SDL_MOUSEBUTTONUP && e->type != SDL_MOUSEMOTION) {
        /* the editor eats keys too: no shortcuts over it. v1.0.3: Back here is
         * the panel's own chevron - it shows or hides the layout menu, it does
         * not throw the edit away. Done and Cancel are the two ways out. */
        if (e->type == SDL_KEYDOWN && !e->key.repeat &&
            (e->key.keysym.sym == SDLK_ESCAPE || e->key.keysym.sym == SDLK_AC_BACK)) {
            g_ve_panel = !g_ve_panel;
            g_ve_sel = -1;
            return 1;
        }
        return e->type == SDL_KEYDOWN;
    }

    int wx = 0, wy = 0;
    if (e->type == SDL_MOUSEMOTION) { wx = e->motion.x; wy = e->motion.y; }
    else { wx = e->button.x; wy = e->button.y; }
    float vx, vy;
    vpad_to_view(ren, wx, wy, &vx, &vy);

    if (e->type == SDL_MOUSEMOTION) {
        if (g_ve_drag < 0) return 0;
        float w, h;
        vpad_view(ren, &w, &h);
        vp_item_t *it = &g_ve_layout.items[g_ve_drag];
        it->x = vp_clamp01((vx - g_ve_drag_dx) / (w > 0 ? w : 1));
        it->y = vp_clamp01((vy - g_ve_drag_dy) / (h > 0 ? h : 1));
        return 1;
    }

    if (e->type == SDL_MOUSEBUTTONUP) {
        if (g_ve_drag >= 0) { g_ve_drag = -1; return 1; }
        return 0;
    }

    /* a press: the panel and the bubble first, then the controls */
    for (int i = g_ve_nhits - 1; i >= 0; i--) {
        const ve_hit_t *hit = &g_ve_hits[i];
        if (!(vx >= hit->r.x && vx < hit->r.x + hit->r.w && vy >= hit->r.y && vy < hit->r.y + hit->r.h)) continue;
        switch (hit->id) {
        case VE_PANEL_SHOW: g_ve_panel = 1; break;
        case VE_PANEL_HIDE: g_ve_panel = 0; break;
        case VE_OPACITY: g_ve_opacity = vpad_opacities[hit->arg]; break;
        case VE_ADD: g_ve_layout.items[hit->arg].shown = 1; break;
        case VE_RESET: vp_defaults(&g_ve_layout, 1280, 720); g_ve_sel = -1; break;
        case VE_CANCEL: vpad_edit_close(); break;
        case VE_DONE: vpad_edit_done(); break;
        case VE_SHRINK: ve_resize(-1); break;
        case VE_GROW: ve_resize(1); break;
        case VE_REMOVE: ve_remove_selected(); break;
        case VE_DRAG: {
            /* the grab keeps its offset, so the control does not jump */
            float w, h;
            vpad_view(ren, &w, &h);
            g_ve_drag = hit->arg;
            g_ve_sel = hit->arg;
            g_ve_drag_dx = vx - g_ve_layout.items[hit->arg].x * w;
            g_ve_drag_dy = vy - g_ve_layout.items[hit->arg].y * h;
            break;
        }
        default: break;
        }
        return 1;
    }
    g_ve_sel = -1;                          /* a click on nothing: nothing selected */
    return 1;
}
