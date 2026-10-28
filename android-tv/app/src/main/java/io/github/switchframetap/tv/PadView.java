/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** The gamepad test: a Pro Controller drawn with what the Switch would get -
 *  after the mapping - lit in the accent colour, the sticks where they are. */
final class PadView extends View {
    private static final float W = 320, H = 200;   // the drawing's own units
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private Pad.State state = Pad.State.NONE;
    private boolean live;
    private float s, ox, oy;

    PadView(Context c) {
        super(c);
        text.setTypeface(Ui.bold);
        text.setTextAlign(Paint.Align.CENTER);
    }
    /** live: a gamepad is connected (otherwise the drawing is dimmed). */
    void set(Pad.State value, boolean live) {
        if (value.equals(state) && live == this.live) return;
        state = value; this.live = live;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        s = Math.min(getWidth() / W, getHeight() / H);
        ox = (getWidth() - W * s) / 2; oy = (getHeight() - H * s) / 2;
        int alpha = live ? 255 : 110;

        // shoulders first, the body over their lower edge
        button(canvas, 52, 4, 118, 20, 6, Pad.ZL, "ZL", alpha);
        button(canvas, 202, 4, 268, 20, 6, Pad.ZR, "ZR", alpha);
        button(canvas, 48, 22, 122, 40, 8, Pad.L, "L", alpha);
        button(canvas, 198, 22, 272, 40, 8, Pad.R, "R", alpha);

        fill.setColor(Ui.SURF2); fill.setAlpha(alpha);
        rect(canvas, 36, 34, 284, 160, 42);
        circle(canvas, 80, 150, 44);
        circle(canvas, 240, 150, 44);

        // left stick, D-pad
        stick(canvas, 92, 82, state.lx, state.ly, Pad.LSTICK, alpha);
        float dx = 130, dy = 136, a = 9, l = 11;
        button(canvas, dx - a, dy - a - 2 * l, dx + a, dy - a, 3, Pad.UP, "", alpha);
        button(canvas, dx - a, dy + a, dx + a, dy + a + 2 * l, 3, Pad.DOWN, "", alpha);
        button(canvas, dx - a - 2 * l, dy - a, dx - a, dy + a, 3, Pad.LEFT, "", alpha);
        button(canvas, dx + a, dy - a, dx + a + 2 * l, dy + a, 3, Pad.RIGHT, "", alpha);
        fill.setColor(Ui.SURF3); fill.setAlpha(alpha);
        rect(canvas, dx - a, dy - a, dx + a, dy + a, 0);

        // A B X Y where a Switch controller has them
        round(canvas, 230, 64, 11, Pad.X, "X", alpha);
        round(canvas, 252, 86, 11, Pad.A, "A", alpha);
        round(canvas, 230, 108, 11, Pad.B, "B", alpha);
        round(canvas, 208, 86, 11, Pad.Y, "Y", alpha);
        stick(canvas, 192, 136, state.rx, state.ry, Pad.RSTICK, alpha);

        // − + Capture HOME
        button(canvas, 132, 56, 148, 64, 3, Pad.MINUS, "", alpha);
        button(canvas, 172, 52, 188, 68, 3, Pad.PLUS, "", alpha);
        label(canvas, 140, 50, "−", 11, alpha);
        label(canvas, 180, 46, "+", 11, alpha);
        button(canvas, 133, 76, 147, 90, 3, Pad.CAPTURE, "", alpha);
        round(canvas, 180, 83, 8, Pad.HOME, "", alpha);
    }

    private void stick(Canvas c, float x, float y, int sx, int sy, int bit, int alpha) {
        fill.setColor(Ui.BG); fill.setAlpha(alpha);
        circle(c, x, y, 22);
        float px = x + sx / (float)Pad.STICK_MAX * 12, py = y - sy / (float)Pad.STICK_MAX * 12;
        boolean moved = sx != 0 || sy != 0;
        fill.setColor(state.pressed(bit) ? Ui.ACC : moved ? Ui.SURF4 : Ui.SURF3); fill.setAlpha(alpha);
        circle(c, px, py, 14);
        if (moved) { fill.setColor(Ui.ACC); fill.setAlpha(alpha); circle(c, px, py, 4); }
    }
    private void button(Canvas c, float l, float t, float rr, float b, float rad, int bit, String name, int alpha) {
        boolean on = state.pressed(bit);
        fill.setColor(on ? Ui.ACC : Ui.SURF3); fill.setAlpha(alpha);
        rect(c, l, t, rr, b, rad);
        if (!name.isEmpty()) label(c, (l + rr) / 2, (t + b) / 2 + 4, name, 10, alpha, on ? Ui.BG : Ui.INK);
    }
    private void round(Canvas c, float x, float y, float rad, int bit, String name, int alpha) {
        boolean on = state.pressed(bit);
        fill.setColor(on ? Ui.ACC : Ui.SURF3); fill.setAlpha(alpha);
        circle(c, x, y, rad);
        if (!name.isEmpty()) label(c, x, y + 4, name, 11, alpha, on ? Ui.BG : Ui.INK);
    }
    private void label(Canvas c, float x, float y, String t, float size, int alpha) { label(c, x, y, t, size, alpha, Ui.DIM); }
    private void label(Canvas c, float x, float y, String t, float size, int alpha, int color) {
        text.setColor(color); text.setAlpha(alpha); text.setTextSize(size * s);
        c.drawText(t, ox + x * s, oy + y * s, text);
    }
    private void rect(Canvas c, float l, float t, float rr, float b, float rad) {
        r.set(ox + l * s, oy + t * s, ox + rr * s, oy + b * s);
        c.drawRoundRect(r, rad * s, rad * s, fill);
    }
    private void circle(Canvas c, float x, float y, float rad) { c.drawCircle(ox + x * s, oy + y * s, rad * s, fill); }
}
