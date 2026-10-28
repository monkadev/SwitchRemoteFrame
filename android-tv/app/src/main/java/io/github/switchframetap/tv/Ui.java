/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.TextView;

/**
 * Aurora design system v2 (docs/UI-REDESIGN.md): the single look shared with the
 * Switch manager (manager/source/ui.hpp) and the PC viewer (tools/raw-recv/ui.h).
 *
 * A four-rung neutral dark ladder with hairline borders for depth (shadows do
 * not read on dark surfaces), off-white text that clears WCAG AA on every rung,
 * one electric-blue accent for actions and the TV focus state, and semantic
 * good / warning / bad colours. Everything is drawn in code: the app ships no
 * image resources, so it stays tiny and looks identical on phone, tablet and TV.
 */
final class Ui {
    // Aurora tokens - keep the RGB values in step with ui.hpp / ui.h.
    static final int BG = 0xFF0B0D11, SURF = 0xFF14181F, SURF2 = 0xFF1D222C,
            SURF3 = 0xFF282F3C, SURF4 = 0xFF363E4E,
            HAIR = 0x0FFFFFFF,                       // 6 % white, the 1 dp card border
            INK = 0xFFF2F5FA, DIM = 0xFFACB5C4, FAINT = 0xFF7C8697,
            ACC = 0xFF4D8DFF, ACC_HOT = 0xFF7AA8FF, GOOD = 0xFF34CD99,
            WARN = 0xFFF5B73F, BAD = 0xFFF26175,
            ONACC = 0xFF070C18, HEART = 0xFFE5484D,
            HUD = 0xB30B0D11;
    static Typeface regular = Typeface.DEFAULT, bold = Typeface.DEFAULT_BOLD;
    private static float density = 1;

    static void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        try {
            regular = Typeface.createFromAsset(context.getAssets(), "fonts/DejaVuSans.ttf");
            bold = Typeface.createFromAsset(context.getAssets(), "fonts/DejaVuSans-Bold.ttf");
        } catch (RuntimeException e) { regular = Typeface.DEFAULT; bold = Typeface.DEFAULT_BOLD; }
    }
    static int dp(float value) { return Math.round(value * density); }

    static TextView text(Context c, String value, float sp, int color, boolean heavy) {
        TextView t = new TextView(c);
        t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        t.setTypeface(heavy ? bold : regular); t.setIncludeFontPadding(false);
        return t;
    }
    static GradientDrawable round(int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radiusDp)); return d;
    }
    static GradientDrawable round(int fill, float radiusDp, int stroke) {
        GradientDrawable d = round(fill, radiusDp); d.setStroke(dp(1), stroke); return d;
    }
    /** An Aurora card: a raised surface plus a hairline border, depth without shadows. */
    static GradientDrawable card(int fill, float radiusDp) { return round(fill, radiusDp, HAIR); }

    /** Focus (remote) and press (touch) both show the accent ring; the resting
     *  state keeps a hairline so its shape reads on the page. */
    static StateListDrawable states(int fill, int focusFill, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(focusFill, radiusDp, ACC));
        s.addState(new int[]{android.R.attr.state_pressed}, round(focusFill, radiusDp, ACC));
        s.addState(new int[]{}, round(fill, radiusDp, HAIR));
        return s;
    }
    /** A pill button: SURF2, or the accent with dark ink for the main action. */
    static Button pill(Context c, String label, boolean primary, View.OnClickListener click) {
        Button b = new Button(c);
        b.setText(label); b.setAllCaps(false); b.setTextSize(15); b.setTypeface(bold);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(22), 0, dp(22), 0);
        b.setGravity(Gravity.CENTER); b.setIncludeFontPadding(false);
        b.setStateListAnimator(null);
        stylePill(b, primary);
        b.setOnClickListener(click);
        return b;
    }
    static void stylePill(Button b, boolean primary) {
        b.setTextColor(primary ? ONACC : INK);
        b.setBackground(primary ? states(ACC, ACC_HOT, 22) : states(SURF2, SURF3, 22));
    }
    /** One choice of a segmented control (USB / Network). */
    static void styleSegment(Button b, boolean selected) {
        b.setTextColor(selected ? ONACC : INK);
        StateListDrawable s = new StateListDrawable();
        int fill = selected ? ACC : 0x00000000;
        s.addState(new int[]{android.R.attr.state_focused}, round(selected ? ACC : SURF3, 8, INK));
        s.addState(new int[]{android.R.attr.state_pressed}, round(selected ? ACC : SURF3, 8, INK));
        s.addState(new int[]{}, round(fill, 8));
        b.setBackground(s);
    }
    /** A settings row: SURF2 with a hairline while it has the focus, as in the PC drawer. */
    static StateListDrawable rowBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(SURF2, 12, ACC));
        s.addState(new int[]{android.R.attr.state_pressed}, round(SURF2, 12, ACC));
        s.addState(new int[]{}, round(0x00000000, 12));
        return s;
    }
    /**
     * The ten-foot focus: a gentle scale lift with an overshoot, on top of the
     * accent ring the background already draws. No-op feeling on touch (it is
     * quick enough to read as a press), essential for D-pad navigation on TV.
     */
    static void focusLift(View v) {
        v.setFocusable(true);
        v.setOnFocusChangeListener((view, hasFocus) -> {
            view.animate().cancel();
            view.animate()
                .scaleX(hasFocus ? 1.03f : 1f).scaleY(hasFocus ? 1.03f : 1f)
                .setDuration(hasFocus ? 180 : 120)
                .setInterpolator(new OvershootInterpolator(hasFocus ? 1.6f : 1f))
                .start();
        });
    }
    /** A key hint: the key in a SURF3 chip, then what it does. */
    static View keyHint(Context c, String key, String what) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView k = text(c, key, 12, INK, true);
        k.setPadding(dp(7), dp(4), dp(7), dp(4)); k.setBackground(card(SURF3, 6));
        TextView w = text(c, what, 13, DIM, false); w.setPadding(dp(8), 0, dp(22), 0);
        row.addView(k); row.addView(w);
        return row;
    }

    /** The ring with a dot in the middle, the home screen's state. It breathes
     *  softly while the app is waiting, so a stalled screen still feels alive. */
    static final class Ring extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = ACC;
        private boolean pulse = true;
        Ring(Context c) { super(c); }
        void setColor(int value) { if (value != color) { color = value; invalidate(); } }
        void setPulse(boolean value) { pulse = value; }
        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            int glow = 0x40;
            if (pulse) {
                glow = (int)(0x28 + 0x28 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 350.0)));
                postInvalidateOnAnimation();   // keep the breathing halo moving
            }
            paint.setColor((color & 0x00FFFFFF) | (Math.min(glow, 90) << 24));
            canvas.drawCircle(cx, cy, r, paint);
            paint.setColor(color); canvas.drawCircle(cx, cy, r * 0.36f, paint);
        }
    }
    /** A filled circle (the title's dot). */
    static final class Dot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Dot(Context c, int color) { super(c); paint.setColor(color); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f, paint);
        }
    }
    /** The on/off switch: an accent track when on, SURF3 when off, a light knob
     *  that glides between its two positions instead of jumping. */
    static final class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private ValueAnimator anim;
        private float frac;                 // 0 off .. 1 on, animated
        private boolean on;
        Toggle(Context c) { super(c); setFocusable(false); }
        void set(boolean value) {
            if (value == on) return;
            on = value;
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(frac, value ? 1f : 0f);
            anim.setDuration(180);
            anim.addUpdateListener(a -> { frac = (float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            paint.setColor(on ? ACC : SURF3);
            track.set(0, 0, w, h);
            canvas.drawRoundRect(track, r, r, paint);
            float inset = dp(3), knob = r - inset;
            float x = inset + knob + frac * (w - 2 * (inset + knob));
            paint.setColor(INK);
            canvas.drawCircle(x, r, knob, paint);
        }
    }
    /** The settings gear, drawn as the PC viewer draws it: teeth around a ring. */
    static final class Gear extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path ring = new Path();
        private final RectF tooth = new RectF();
        Gear(int color) { paint.setColor(color); ring.setFillType(Path.FillType.EVEN_ODD); }
        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(), s = Math.min(b.width(), b.height()) / 2f;
            tooth.set(cx - s * 0.17f, cy - s, cx + s * 0.17f, cy - s * 0.5f);
            for (int i = 0; i < 8; i++) {
                canvas.save();
                canvas.rotate(i * 45, cx, cy);
                canvas.drawRoundRect(tooth, s * 0.06f, s * 0.06f, paint);
                canvas.restore();
            }
            ring.reset();
            ring.addCircle(cx, cy, s * 0.70f, Path.Direction.CW);
            ring.addCircle(cx, cy, s * 0.30f, Path.Direction.CW);
            canvas.drawPath(ring, paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** The small line icons (on-screen gamepad, its editor), drawn in code like
     *  the gear - the app ships no image resources. */
    static final class Icon extends Drawable {
        static final int ZOOM_OUT = 0, ZOOM_IN = 1, TRASH = 2, PENCIL = 3, GAMEPAD = 4, GAMEPAD_OFF = 5, CHEVRON = 6;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private final int kind;
        Icon(int kind, int color) {
            this.kind = kind;
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(), s = Math.min(b.width(), b.height());
            paint.setStrokeWidth(Math.max(1.5f, s * 0.09f));
            path.reset();
            switch (kind) {
                case ZOOM_OUT: case ZOOM_IN: {
                    float gx = cx - s * 0.08f, gy = cy - s * 0.08f, gr = s * 0.27f;
                    c.drawCircle(gx, gy, gr, paint);
                    c.drawLine(gx + gr * 0.72f, gy + gr * 0.72f, cx + s * 0.38f, cy + s * 0.38f, paint);
                    c.drawLine(gx - gr * 0.5f, gy, gx + gr * 0.5f, gy, paint);
                    if (kind == ZOOM_IN) c.drawLine(gx, gy - gr * 0.5f, gx, gy + gr * 0.5f, paint);
                    break;
                }
                case TRASH:
                    c.drawLine(cx - s * 0.34f, cy - s * 0.24f, cx + s * 0.34f, cy - s * 0.24f, paint);
                    path.moveTo(cx - s * 0.12f, cy - s * 0.24f); path.lineTo(cx - s * 0.12f, cy - s * 0.36f);
                    path.lineTo(cx + s * 0.12f, cy - s * 0.36f); path.lineTo(cx + s * 0.12f, cy - s * 0.24f);
                    path.moveTo(cx - s * 0.25f, cy - s * 0.16f); path.lineTo(cx - s * 0.2f, cy + s * 0.38f);
                    path.lineTo(cx + s * 0.2f, cy + s * 0.38f); path.lineTo(cx + s * 0.25f, cy - s * 0.16f);
                    c.drawPath(path, paint);
                    c.drawLine(cx - s * 0.07f, cy - s * 0.06f, cx - s * 0.07f, cy + s * 0.26f, paint);
                    c.drawLine(cx + s * 0.07f, cy - s * 0.06f, cx + s * 0.07f, cy + s * 0.26f, paint);
                    break;
                case PENCIL:
                    c.save();
                    c.rotate(45, cx, cy);
                    r.set(cx - s * 0.1f, cy - s * 0.42f, cx + s * 0.1f, cy + s * 0.2f);
                    c.drawRect(r, paint);
                    path.moveTo(cx - s * 0.1f, cy + s * 0.2f); path.lineTo(cx, cy + s * 0.42f); path.lineTo(cx + s * 0.1f, cy + s * 0.2f);
                    c.drawPath(path, paint);
                    c.drawLine(cx - s * 0.1f, cy - s * 0.28f, cx + s * 0.1f, cy - s * 0.28f, paint);
                    c.restore();
                    break;
                case GAMEPAD: case GAMEPAD_OFF: {
                    r.set(cx - s * 0.42f, cy - s * 0.22f, cx + s * 0.42f, cy + s * 0.24f);
                    c.drawRoundRect(r, s * 0.2f, s * 0.2f, paint);
                    float dx = cx - s * 0.2f;
                    c.drawLine(dx - s * 0.09f, cy, dx + s * 0.09f, cy, paint);
                    c.drawLine(dx, cy - s * 0.09f, dx, cy + s * 0.09f, paint);
                    Paint.Style was = paint.getStyle(); paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx + s * 0.17f, cy + s * 0.05f, s * 0.045f, paint);
                    c.drawCircle(cx + s * 0.27f, cy - s * 0.05f, s * 0.045f, paint);
                    paint.setStyle(was);
                    if (kind == GAMEPAD_OFF) c.drawLine(cx - s * 0.4f, cy - s * 0.4f, cx + s * 0.4f, cy + s * 0.4f, paint);
                    break;
                }
                default:   // CHEVRON, pointing right
                    path.moveTo(cx - s * 0.1f, cy - s * 0.25f); path.lineTo(cx + s * 0.15f, cy); path.lineTo(cx - s * 0.1f, cy + s * 0.25f);
                    c.drawPath(path, paint);
                    break;
            }
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    /** A round icon-only button (the icon a little over half its size). */
    static android.widget.ImageButton iconButton(Context c, int kind, int fill, int pressed, View.OnClickListener click) {
        android.widget.ImageButton b = new android.widget.ImageButton(c);
        b.setImageDrawable(new Icon(kind, INK));
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(9), dp(9), dp(9), dp(9));
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = new GradientDrawable(); on.setShape(GradientDrawable.OVAL);
        on.setColor(pressed); on.setStroke(dp(1), ACC);
        GradientDrawable off = new GradientDrawable(); off.setShape(GradientDrawable.OVAL);
        off.setColor(fill); off.setStroke(dp(1), HAIR);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        b.setBackground(s);
        b.setOnClickListener(click);
        return b;
    }

    private Ui() { }
}
