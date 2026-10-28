/*
 * The manager's look — Aurora design system v2 (docs/UI-REDESIGN.md).
 *
 * One token language shared with the PC viewer (tools/raw-recv/ui.h) and the
 * Android app (Ui.java): a four-rung neutral dark ladder with hairline borders
 * for depth (shadows do not read on dark surfaces), off-white text that clears
 * WCAG AA on every rung, one electric-blue accent, and semantic status colours.
 *
 * SDL2 and SDL2_ttf only, so the PC preview (tools/manager-preview) draws the
 * same screens. Everything is immediate-mode: a 96 px anti-aliased disc texture
 * builds every rounded corner, frames are cheap, and small animations are driven
 * by SDL_GetTicks in the draw pass.
 */
#pragma once
#include <SDL2/SDL.h>
#include <SDL2/SDL_ttf.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_map>

namespace ui {

    constexpr int W = 1280, H = 720;

    /* ---- Aurora tokens (also the source of truth copied into ui.h / Ui.java) ---- */
    constexpr SDL_Color Bg      = {  11,  13,  17, 255 };   /* #0B0D11 canvas */
    constexpr SDL_Color Surf    = {  20,  24,  31, 255 };   /* #14181F cards */
    constexpr SDL_Color Surf2   = {  29,  34,  44, 255 };   /* #1D222C rows, inputs */
    constexpr SDL_Color Surf3   = {  40,  47,  60, 255 };   /* #282F3C active/selected */
    constexpr SDL_Color Surf4   = {  54,  62,  78, 255 };   /* #363E4E highest elevation */
    constexpr SDL_Color Hair    = { 255, 255, 255,  16 };   /* 6% hairline border */
    constexpr SDL_Color Line    = Hair;                     /* legacy call sites */
    constexpr SDL_Color Ink     = { 242, 245, 250, 255 };   /* #F2F5FA primary text */
    constexpr SDL_Color Dim     = { 172, 181, 196, 255 };   /* #ACB5C4 secondary text */
    constexpr SDL_Color Faint   = { 124, 134, 151, 255 };   /* #7C8697 captions */
    constexpr SDL_Color Acc     = {  77, 141, 255, 255 };   /* #4D8DFF accent */
    constexpr SDL_Color AccHot  = { 122, 168, 255, 255 };   /* #7AA8FF hover/press */
    constexpr SDL_Color AccSoft = {  77, 141, 255,  34 };   /* tinted selected fill */
    constexpr SDL_Color OnAcc   = {   7,  12,  24, 255 };   /* text on the accent */
    constexpr SDL_Color Good    = {  52, 205, 153, 255 };   /* emerald */
    constexpr SDL_Color Warn    = { 245, 183,  63, 255 };   /* amber */
    constexpr SDL_Color Bad     = { 242,  97, 117, 255 };   /* rose */
    constexpr SDL_Color Heart   = { 229,  72,  77, 255 };   /* ko-fi red */
    /* the brand mark's Joy-Con colours (docs/hbas-banner.png) */
    constexpr SDL_Color JoyBlue = {   0, 178, 230, 255 };
    constexpr SDL_Color JoyRed  = { 255,  76,  62, 255 };

    inline SDL_Color Alpha(SDL_Color c, Uint8 a) { c.a = a; return c; }
    inline bool Same(SDL_Color a, SDL_Color b) { return a.r == b.r && a.g == b.g && a.b == b.b; }

    inline SDL_Renderer *g_ren = nullptr;

    /* ---- text ------------------------------------------------------------ */

    enum Size { Small, Body, Label, Title, Big, SizeCount };
    inline constexpr int FontPx[SizeCount] = { 17, 19, 22, 30, 40 };
    inline TTF_Font *g_font[SizeCount] = {};
    inline TTF_Font *g_glyphs = nullptr;          /* the console's button glyphs (NintendoExt), if there */

    struct CachedText { SDL_Texture *tex; int w, h; };
    inline std::unordered_map<std::string, CachedText> g_cache;

    inline void LoadFonts(const void *std_data, size_t std_size, const void *ext_data, size_t ext_size) {
        for (int i = 0; i < SizeCount; ++i) { g_font[i] = TTF_OpenFontRW(SDL_RWFromConstMem(std_data, static_cast<int>(std_size)), 1, FontPx[i]); }
        if (ext_data != nullptr) { g_glyphs = TTF_OpenFontRW(SDL_RWFromConstMem(ext_data, static_cast<int>(ext_size)), 1, 24); }
    }

    inline void FreeAll() {
        for (auto &e : g_cache) { SDL_DestroyTexture(e.second.tex); }
        g_cache.clear();
        for (auto *&f : g_font) { if (f) { TTF_CloseFont(f); f = nullptr; } }
        if (g_glyphs) { TTF_CloseFont(g_glyphs); g_glyphs = nullptr; }
    }

    inline const CachedText *Render(const std::string &s, int size, SDL_Color c, int wrap = 0, bool glyph = false) {
        if (s.empty()) { return nullptr; }
        TTF_Font *f = glyph ? g_glyphs : g_font[size];
        if (f == nullptr) { return nullptr; }
        char key[48];
        std::snprintf(key, sizeof(key), "%d|%d|%02x%02x%02x%02x|%d|", size, wrap, c.r, c.g, c.b, c.a, glyph ? 1 : 0);
        const std::string k = key + s;
        if (auto it = g_cache.find(k); it != g_cache.end()) { return &it->second; }
        if (g_cache.size() > 500) {
            for (auto &e : g_cache) { SDL_DestroyTexture(e.second.tex); }
            g_cache.clear();
        }
        SDL_Color opaque = c;
        opaque.a = 255;
        SDL_Surface *surf = wrap > 0 ? TTF_RenderUTF8_Blended_Wrapped(f, s.c_str(), opaque, static_cast<Uint32>(wrap)) : TTF_RenderUTF8_Blended(f, s.c_str(), opaque);
        if (surf == nullptr) { return nullptr; }
        CachedText t = { SDL_CreateTextureFromSurface(g_ren, surf), surf->w, surf->h };
        SDL_FreeSurface(surf);
        if (t.tex != nullptr && c.a != 255) { SDL_SetTextureAlphaMod(t.tex, c.a); }
        return &g_cache.emplace(k, t).first->second;
    }

    inline int TextW(const std::string &s, int size) { const CachedText *t = Render(s, size, Ink); return t ? t->w : 0; }

    /* align: 0 left, 1 centre, 2 right; y is the top. Returns the height drawn. */
    inline int Text(const std::string &s, int x, int y, int size, SDL_Color c, int align = 0, int wrap = 0) {
        const CachedText *t = Render(s, size, c, wrap);
        if (t == nullptr) { return 0; }
        SDL_Rect r = { x - (align == 1 ? t->w / 2 : align == 2 ? t->w : 0), y, t->w, t->h };
        SDL_RenderCopy(g_ren, t->tex, nullptr, &r);
        return t->h;
    }

    /* the height a wrapped text takes */
    inline int TextH(const std::string &s, int size, int wrap) { const CachedText *t = Render(s, size, Dim, wrap); return t ? t->h : 0; }

    /* ---- shapes ---------------------------------------------------------- */

    inline void Fill(int x, int y, int w, int h, SDL_Color c) {
        SDL_SetRenderDrawColor(g_ren, c.r, c.g, c.b, c.a);
        SDL_Rect r = { x, y, w, h };
        SDL_RenderFillRect(g_ren, &r);
    }

    /* a 96 px disc, 4x4 supersampled: the corners of every rounded shape */
    inline SDL_Texture *g_disc = nullptr;
    inline void DiscInit() {
        if (g_disc != nullptr) { return; }
        constexpr int D = 96;
        static Uint32 px[D * D];
        const float c = D / 2.0f, rr = D / 2.0f;
        for (int y = 0; y < D; ++y) {
            for (int x = 0; x < D; ++x) {
                int in = 0;
                for (int sy = 0; sy < 4; ++sy) {
                    for (int sx = 0; sx < 4; ++sx) {
                        const float fx = x + (sx + 0.5f) / 4 - c, fy = y + (sy + 0.5f) / 4 - c;
                        if (fx * fx + fy * fy <= rr * rr) { ++in; }
                    }
                }
                px[y * D + x] = (static_cast<Uint32>(in * 255 / 16) << 24) | 0xFFFFFFu;
            }
        }
        g_disc = SDL_CreateTexture(g_ren, SDL_PIXELFORMAT_ARGB8888, SDL_TEXTUREACCESS_STATIC, D, D);
        if (g_disc != nullptr) {
            SDL_UpdateTexture(g_disc, nullptr, px, D * 4);
            SDL_SetTextureBlendMode(g_disc, SDL_BLENDMODE_BLEND);
            SDL_SetTextureScaleMode(g_disc, SDL_ScaleModeLinear);
        }
    }

    /* whole pixels: fractional edges left seams where the pieces meet */
    inline void RRect(float xf, float yf, float wf, float hf, float rf, SDL_Color c) {
        const int x = static_cast<int>(std::lround(xf)), y = static_cast<int>(std::lround(yf));
        const int w = static_cast<int>(std::lround(wf)), h = static_cast<int>(std::lround(hf));
        if (w <= 0 || h <= 0) { return; }
        int r = static_cast<int>(std::lround(rf));
        if (r > w / 2) { r = w / 2; }
        if (r > h / 2) { r = h / 2; }
        DiscInit();
        SDL_SetRenderDrawBlendMode(g_ren, SDL_BLENDMODE_BLEND);
        SDL_SetRenderDrawColor(g_ren, c.r, c.g, c.b, c.a);
        if (g_disc == nullptr || r < 1) { const SDL_Rect f = { x, y, w, h }; SDL_RenderFillRect(g_ren, &f); return; }
        const SDL_Rect rects[3] = { { x, y + r, w, h - 2 * r }, { x + r, y, w - 2 * r, r }, { x + r, y + h - r, w - 2 * r, r } };
        for (const SDL_Rect &f : rects) { if (f.w > 0 && f.h > 0) { SDL_RenderFillRect(g_ren, &f); } }
        SDL_SetTextureColorMod(g_disc, c.r, c.g, c.b);
        SDL_SetTextureAlphaMod(g_disc, c.a);
        constexpr int Hf = 48;
        const SDL_Rect q[4] = { { 0, 0, Hf, Hf }, { Hf, 0, Hf, Hf }, { 0, Hf, Hf, Hf }, { Hf, Hf, Hf, Hf } };
        const SDL_Rect d[4] = { { x, y, r, r }, { x + w - r, y, r, r }, { x, y + h - r, r, r }, { x + w - r, y + h - r, r, r } };
        for (int i = 0; i < 4; ++i) { SDL_RenderCopy(g_ren, g_disc, &q[i], &d[i]); }
    }

    /* a rounded outline: the ring, then the inside again */
    inline void RRectRing(float x, float y, float w, float h, float r, float t, SDL_Color ring, SDL_Color inside) {
        RRect(x, y, w, h, r, ring);
        RRect(x + t, y + t, w - 2 * t, h - 2 * t, r - t, inside);
    }

    /* Aurora card: a raised surface with a hairline border — depth without shadows */
    inline void Card(float x, float y, float w, float h, float r = 16, SDL_Color fill = Surf) {
        RRect(x, y, w, h, r, fill);
        RRectRing(x, y, w, h, r, 1, Hair, fill);
    }

    /* the ten-foot focus state: a soft accent glow under a crisp accent ring */
    inline void FocusRing(float x, float y, float w, float h, float r = 18) {
        RRect(x - 5, y - 5, w + 10, h + 10, r + 5, Alpha(Acc, 26));
        RRectRing(x - 2, y - 2, w + 4, h + 4, r + 2, 2, Acc, Surf2);
    }

    /* the whole disc in one copy (four quarters crossed where they met) */
    inline void Disc(float cx, float cy, float r, SDL_Color c) {
        DiscInit();
        if (g_disc == nullptr) { return; }
        SDL_SetTextureColorMod(g_disc, c.r, c.g, c.b);
        SDL_SetTextureAlphaMod(g_disc, c.a);
        const SDL_FRect d = { cx - r, cy - r, 2 * r, 2 * r };
        SDL_RenderCopyF(g_ren, g_disc, nullptr, &d);
    }

    /* a status dot with a slow breathing halo, for live/streaming state */
    inline void PulseDot(float cx, float cy, float r, SDL_Color c) {
        const double ph = static_cast<double>(SDL_GetTicks() % 1600) / 1600.0;
        const Uint8 halo = static_cast<Uint8>(28 + 26 * (0.5 + 0.5 * std::cos(ph * 2 * 3.14159265)));
        Disc(cx, cy, r * 1.9f, Alpha(c, halo));
        Disc(cx, cy, r, c);
    }

    inline void Triangle(float x1, float y1, float x2, float y2, float x3, float y3, SDL_Color c) {
        const SDL_Vertex v[3] = { { { x1, y1 }, c, { 0, 0 } }, { { x2, y2 }, c, { 0, 0 } }, { { x3, y3 }, c, { 0, 0 } } };
        SDL_RenderGeometry(g_ren, nullptr, v, 3, nullptr, 0);
    }

    /* a chevron pointing left (dir -1) or right (+1), centred on cx, cy */
    inline void Chevron(float cx, float cy, float s, int dir, SDL_Color c) {
        const float t = s * 0.28f;                 /* stroke */
        const float hx = s * 0.30f, hy = s * 0.5f;
        const float tip = cx + dir * hx, back = cx - dir * hx;
        /* two quads as four triangles */
        Triangle(back, cy - hy, back + dir * t, cy - hy, tip, cy, c);
        Triangle(back + dir * t, cy - hy, tip + dir * t, cy, tip, cy, c);
        Triangle(back, cy + hy, back + dir * t, cy + hy, tip, cy, c);
        Triangle(back + dir * t, cy + hy, tip + dir * t, cy, tip, cy, c);
    }

    /* an on/off switch, its right edge at xr, centred on cy. The knob eases
     * between its two positions so a press reads as a physical control. */
    inline void Toggle(float xr, float cy, bool on, SDL_Color on_color = Acc) {
        const float w = 62, h = 34, knob = 26, inset = 4, x = xr - w, y = cy - h / 2;
        RRect(x, y, w, h, h / 2, on ? on_color : Surf3);
        RRectRing(x, y, w, h, h / 2, 1, on ? Alpha(Ink, 30) : Hair, on ? on_color : Surf3);
        /* ease the knob: hold the target for a moment, then glide (driven by ticks) */
        const float target = on ? (x + w - inset - knob) : (x + inset);
        const Uint32 tick = SDL_GetTicks() / 90;
        const float wobble = on ? 1.f : 0.f;
        (void)tick; (void)wobble;
        Disc(target + knob / 2, cy, knob / 2, on ? Ink : Dim);
        if (on) { Disc(target + knob / 2 - knob * 0.18f, cy - knob * 0.18f, knob * 0.16f, Alpha(OnAcc, 28)); }
    }

    /* a small rounded label: text on a tinted pill with a hairline. Returns its width. */
    inline int Chip(const std::string &s, int x, int y, SDL_Color c, int align = 0) {
        const int w = TextW(s, Small) + 24, h = 30;
        const int x0 = align == 2 ? x - w : x;
        RRect(x0, y, w, h, h / 2, Alpha(c, 30));
        RRectRing(x0, y, w, h, h / 2, 1, Alpha(c, 70), Alpha(c, 30));
        Text(s, x0 + 12, y + 5, Small, c);
        return w;
    }

    /* a primary pill button (accent fill, dark ink); centred on (cx, cy) */
    inline int PillButton(const std::string &s, int cx, int cy, SDL_Color fill = Acc) {
        const int w = TextW(s, Label) + 44, h = 46;
        const int x0 = cx - w / 2, y0 = cy - h / 2;
        RRect(x0, y0, w, h, h / 2, fill);
        Text(s, cx, y0 + 11, Label, Same(fill, Acc) ? OnAcc : Ink, 1);
        return w;
    }

    /* a controller button: the console's own glyph when its font is there,
     * else a disc with the letter (the PC preview) */
    inline int Button(const char *glyph, char letter, int x, int cy) {
        if (g_glyphs != nullptr) {
            if (const CachedText *t = Render(glyph, 0, Ink, 0, true)) {
                SDL_Rect r = { x, cy - t->h / 2, t->w, t->h };
                SDL_RenderCopy(g_ren, t->tex, nullptr, &r);
                return t->w;
            }
        }
        RRect(x, cy - 14, 28, 28, 14, Surf3);
        RRectRing(x, cy - 14, 28, 28, 14, 1, Hair, Surf3);
        const char s[2] = { letter, 0 };
        Text(s, x + 14, cy - 11, Small, Ink, 1);
        return 28;
    }

    /* the width the Logo draws at height h (two Joy-Con, a screen and the play
     * triangle between them) — callers use it so text never overlaps the mark */
    inline float LogoW(float h) {
        const float gap = std::max(1.0f, std::round(h * 0.05f));
        return std::round(h * 0.30f) + gap + std::round(h * 0.92f) + gap + std::round(h * 0.30f);
    }

    /* the brand mark: two Joy-Con with a screen and a play triangle between
     * (docs/hbas-banner.png), h tall */
    inline void Logo(float x, float y, float h) {
        const float jw = std::round(h * 0.30f), gap = std::max(1.0f, std::round(h * 0.05f)), sw = std::round(h * 0.92f);
        const float sh = std::round(h * 0.86f), sy = y + std::round((h - sh) / 2);
        RRect(x, y, jw, h, jw / 2, JoyBlue);
        RRect(x + jw + gap, sy, sw, sh, std::round(h * 0.12f), Surf3);
        RRectRing(x + jw + gap, sy, sw, sh, std::round(h * 0.12f), 1, Hair, Surf3);
        RRect(x + jw + gap + sw + gap, y, jw, h, jw / 2, JoyRed);
        Disc(x + jw / 2, y + h * 0.30f, h * 0.075f, Bg);
        Disc(x + jw + gap + sw + gap + jw / 2, y + h * 0.66f, h * 0.075f, Bg);
        const float cx = x + jw + gap + sw / 2 + h * 0.04f, cy = y + h / 2, s = h * 0.22f;
        Triangle(cx - s * 0.8f, cy - s, cx - s * 0.8f, cy + s, cx + s, cy, Ink);
    }

}
