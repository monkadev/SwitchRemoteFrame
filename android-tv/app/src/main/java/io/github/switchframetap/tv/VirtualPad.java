/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import java.util.HashMap;
import java.util.Map;

import static io.github.switchframetap.tv.Ui.dp;

/** 0.7.16: the on-screen gamepad - a Pro Controller drawn over the game
 *  (VirtualLayout says which controls, where and how big).
 *
 *  Every control is a View of its own in a layer over the picture. The layer
 *  itself takes no touches, so a finger that misses every control goes on to
 *  the picture (the Switch's touch screen, with touch control on), and
 *  Android splits the fingers: a thumb on the stick, another on A and a third
 *  on the screen all work at once. The same controls, in editing mode, are
 *  what VirtualEditor drags around. */
final class VirtualPad {
    interface Listener {
        void changed();                    // what it holds changed
        void hiddenChanged(boolean hidden); // the bottom-left button
    }

    private final Context context;
    final FrameLayout layer;
    private final FrameLayout controlsLayer;
    private final Button toggle;   // v1.0.4: "Show" / "Hide", the word for what it does
    private boolean hidden;
    private final Listener listener;
    private VirtualLayout layout;
    private float opacity = 0.5f;
    private final Map<String, Control> controls = new HashMap<>();
    // what each control holds now
    private final Map<String, Integer> held = new HashMap<>();
    private int lx, ly, rx, ry;

    VirtualPad(Context context, Listener listener) {
        this.context = context; this.listener = listener;
        layer = new FrameLayout(context);   // not clickable: touches between controls fall through
        controlsLayer = new FrameLayout(context);
        layer.addView(controlsLayer, new FrameLayout.LayoutParams(-1, -1));
        layer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol || b - t != ob - ot) place(); });
        // 0.7.16: show / hide (bottom left). v1.0.3: no edit button here any more -
        // the layout is edited from Settings, so the pad over the game carries one
        // button, not two. v1.0.4: the word on it, and it stays - semitransparent -
        // while the pad is hidden, because it is the only way back.
        toggle = Ui.pill(context, "Hide", false, v -> setHidden(!hidden, true));
        toggle.setTextSize(13);
        toggle.setPadding(dp(12), 0, dp(12), 0);
        toggle.setContentDescription("Show or hide the on-screen gamepad");
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, dp(34), Gravity.BOTTOM | Gravity.START);
        tl.setMargins(dp(8), 0, 0, dp(8));
        layer.addView(toggle, tl);
        toggle.setFocusable(false);   // a TV remote never lands on it
        styleToggle();
    }

    /** The controls hidden by the bottom-left button (that button stays). */
    void setHidden(boolean value, boolean fromButton) {
        if (value == hidden) return;
        if (value) release();
        hidden = value;
        controlsLayer.setVisibility(value ? View.GONE : View.VISIBLE);
        styleToggle();
        if (fromButton) listener.hiddenChanged(value);
    }
    boolean hidden() { return hidden; }
    private void styleToggle() {
        toggle.setText(hidden ? "Show" : "Hide");
        // v1.0.5: a small controller icon next to the word - the pad itself
        // while it is up, a crossed-out pad while it is hidden. Ui.Icon has no
        // intrinsic size, so it gets explicit bounds before it goes on.
        Ui.Icon ic = new Ui.Icon(hidden ? Ui.Icon.GAMEPAD_OFF : Ui.Icon.GAMEPAD, Ui.INK);
        ic.setBounds(0, 0, dp(16), dp(16));
        toggle.setCompoundDrawablesRelative(ic, null, null, null);
        toggle.setCompoundDrawablePadding(dp(6));
        toggle.setPadding(dp(10), 0, dp(12), 0);
        toggle.setAlpha(hidden ? 0.6f : 0.85f);
    }

    /** Build the controls for this layout (and look). */
    void set(VirtualLayout value, float alpha) {
        release();
        layout = value; opacity = alpha;
        controlsLayer.removeAllViews(); controls.clear();
        for (VirtualLayout.Item i : layout.items) {
            if (!i.shown) continue;
            Control c = new Control(context, i.id, this, false);
            c.opacity = alpha;
            controls.put(i.id, c);
            controlsLayer.addView(c, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));
        }
        place();
    }

    /** Each control where the layout puts it on this screen. */
    void place() {
        int w = layer.getWidth(), h = layer.getHeight();
        if (w == 0 || h == 0 || layout == null) return;
        for (VirtualLayout.Item i : layout.items) {
            Control c = controls.get(i.id);
            if (c != null) position(c, i, w, h);
        }
    }

    static void position(View c, VirtualLayout.Item i, int w, int h) {
        int cw = dp(VirtualLayout.widthDp(i.id) * i.scale), ch = dp(VirtualLayout.heightDp(i.id) * i.scale);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)c.getLayoutParams();
        int left = Math.max(0, Math.min(w - cw, Math.round(i.x * w - cw / 2f)));
        int top = Math.max(0, Math.min(h - ch, Math.round(i.y * h - ch / 2f)));
        if (lp.width != cw || lp.height != ch || lp.leftMargin != left || lp.topMargin != top) {
            lp.width = cw; lp.height = ch; lp.leftMargin = left; lp.topMargin = top;
            c.setLayoutParams(lp);
        }
    }

    /** The controls are on the screen (not hidden, nothing over them). */
    boolean shown() { return layer.getVisibility() == View.VISIBLE && !hidden; }
    boolean layerShown() { return layer.getVisibility() == View.VISIBLE; }
    void show(boolean value) {
        if (value == layerShown()) return;
        if (!value) release();
        layer.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    /** Let go of everything (the controls hide, Settings opens...). */
    void release() {
        boolean any = !held.isEmpty() || lx != 0 || ly != 0 || rx != 0 || ry != 0;
        held.clear(); lx = ly = rx = ry = 0;
        for (Control c : controls.values()) c.reset();
        if (any) listener.changed();
    }

    /** What the on-screen controller holds now (always connected). */
    Pad.State state() {
        int buttons = 0;
        for (int b : held.values()) buttons |= b;
        return new Pad.State(true, buttons, lx, ly, rx, ry);
    }

    void report(String id, int buttons, int sx, int sy) {
        if (buttons == 0) held.remove(id); else held.put(id, buttons);
        if (id.equals("ls")) { lx = sx; ly = sy; }
        if (id.equals("rs")) { rx = sx; ry = sy; }
        listener.changed();
    }

    /* ---- one control ---- */

    /** Something the editor is told about. */
    interface Editing {
        void select(Control c);
        void moved(Control c, float centreX, float centreY);
        void dragging(Control c, boolean value);   // a drag starts / ends
    }

    static final class Control extends View {
        final String id;
        final int kind;
        private final VirtualPad owner;
        final boolean editing;
        Editing editor;
        float opacity = 0.5f;
        boolean selected;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
                text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();
        private int pointer = -1;
        private int buttons;
        private float knobX, knobY;   // -1..1, y down
        // editing
        private float grabX, grabY, downRawX, downRawY;
        private boolean dragging;

        @SuppressLint("ClickableViewAccessibility")
        Control(Context c, String id, VirtualPad owner, boolean editing) {
            super(c);
            this.id = id; this.kind = VirtualLayout.kind(id); this.owner = owner; this.editing = editing;
            stroke.setStyle(Paint.Style.STROKE);
            text.setTextAlign(Paint.Align.CENTER); text.setTypeface(Ui.bold);
            setContentDescription(VirtualLayout.name(id));
        }

        void reset() { pointer = -1; buttons = 0; knobX = knobY = 0; invalidate(); }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (editing) return editTouch(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pointer = e.getPointerId(0);
                    track(e.getX(0), e.getY(0), true);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (pointer < 0) {
                        int i = e.getActionIndex();
                        pointer = e.getPointerId(i);
                        track(e.getX(i), e.getY(i), true);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int i = pointer < 0 ? -1 : e.findPointerIndex(pointer);
                    if (i >= 0) track(e.getX(i), e.getY(i), false);
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (e.getPointerId(e.getActionIndex()) == pointer) up();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    up();
                    return true;
                default:
                    return true;
            }
        }

        private void track(float x, float y, boolean down) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, dx = x - cx, dy = y - cy;
            int before = buttons;
            switch (kind) {
                case VirtualLayout.STICK: {
                    float radius = Math.min(getWidth(), getHeight()) / 2f * 0.62f;
                    int[] s = VirtualLayout.stick(dx, dy, radius);
                    knobX = s[0] / (float)Pad.STICK_MAX; knobY = -s[1] / (float)Pad.STICK_MAX;
                    owner.report(id, 0, s[0], s[1]);
                    invalidate();
                    return;
                }
                case VirtualLayout.DPAD:
                    buttons = VirtualLayout.dpad(dx, dy, Math.min(getWidth(), getHeight()) / 2f);
                    break;
                default:
                    buttons = 1 << VirtualLayout.button(id);
                    break;
            }
            if (buttons != before || down) {
                if (buttons != 0 && buttons != before) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                owner.report(id, buttons, 0, 0);
                invalidate();
            }
        }

        private void up() {
            pointer = -1;
            boolean was = buttons != 0 || knobX != 0 || knobY != 0;
            buttons = 0; knobX = knobY = 0;
            if (was) owner.report(id, 0, 0, 0);
            invalidate();
        }

        /* in the editor: drag to move, tap to select */
        private boolean editTouch(MotionEvent e) {
            View parent = (View)getParent();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    grabX = e.getX() - getWidth() / 2f; grabY = e.getY() - getHeight() / 2f;
                    downRawX = e.getRawX(); downRawY = e.getRawY(); dragging = false;
                    if (editor != null) editor.select(this);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float mx = e.getRawX() - downRawX, my = e.getRawY() - downRawY;
                    if (!dragging && mx * mx + my * my > sq(ViewConfiguration.get(getContext()).getScaledTouchSlop())) {
                        dragging = true;
                        if (editor != null) editor.dragging(this, true);
                    }
                    if (dragging && editor != null && parent != null) {
                        int[] at = new int[2];
                        parent.getLocationOnScreen(at);
                        editor.moved(this, e.getRawX() - at[0] - grabX, e.getRawY() - at[1] - grabY);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging && editor != null) editor.dragging(this, false);
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }
        private static float sq(float v) { return v * v; }

        /* ---- drawing: dark, see-through shapes with a light rim ---- */

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            int a = Math.round(255 * Math.max(0.15f, Math.min(1f, opacity)));
            float rim = Math.max(1.5f, dp(2) * Math.min(1.4f, s / dp(56)));
            stroke.setStrokeWidth(selected ? rim * 2f : rim);
            int ink = selected ? Ui.ACC : Ui.INK;
            stroke.setColor(ink); stroke.setAlpha(selected ? 255 : a);
            boolean pressed = buttons != 0;
            switch (kind) {
                case VirtualLayout.STICK: {
                    float cx = w / 2f, cy = h / 2f, r = s / 2f - rim;
                    shade(false, a);
                    canvas.drawCircle(cx, cy, r, fill);
                    canvas.drawCircle(cx, cy, r, stroke);
                    float travel = r * 0.62f, kr = r * 0.42f;
                    float kx = cx + knobX * travel, ky = cy + knobY * travel;
                    boolean moved = knobX != 0 || knobY != 0;
                    shade(moved, a);
                    canvas.drawCircle(kx, ky, kr, fill);
                    canvas.drawCircle(kx, ky, kr, stroke);
                    label(canvas, id.equals("ls") ? "L" : "R", kx, ky, kr * 0.8f, moved, a);
                    break;
                }
                case VirtualLayout.DPAD: {
                    float cx = w / 2f, cy = h / 2f, arm = s / 2f - rim, half = s * 0.17f;
                    int[] dirs = { Pad.UP, Pad.DOWN, Pad.LEFT, Pad.RIGHT };
                    for (int dir : dirs) {
                        switch (dir) {
                            case Pad.UP:    rect.set(cx - half, cy - arm, cx + half, cy - half * 0.3f); break;
                            case Pad.DOWN:  rect.set(cx - half, cy + half * 0.3f, cx + half, cy + arm); break;
                            case Pad.LEFT:  rect.set(cx - arm, cy - half, cx - half * 0.3f, cy + half); break;
                            default:        rect.set(cx + half * 0.3f, cy - half, cx + arm, cy + half); break;
                        }
                        boolean on = (buttons & (1 << dir)) != 0;
                        shade(on, a);
                        canvas.drawRoundRect(rect, half * 0.35f, half * 0.35f, fill);
                        canvas.drawRoundRect(rect, half * 0.35f, half * 0.35f, stroke);
                        arrow(canvas, dir, rect, on, a);
                    }
                    break;
                }
                case VirtualLayout.SHOULDER: {
                    rect.set(rim, rim, w - rim, h - rim);
                    float r = (h - 2 * rim) / 2f;
                    shade(pressed, a);
                    canvas.drawRoundRect(rect, r, r, fill);
                    canvas.drawRoundRect(rect, r, r, stroke);
                    label(canvas, VirtualLayout.label(id), w / 2f, h / 2f, h * 0.42f, pressed, a);
                    break;
                }
                default: {
                    float cx = w / 2f, cy = h / 2f, r = s / 2f - rim;
                    shade(pressed, a);
                    canvas.drawCircle(cx, cy, r, fill);
                    canvas.drawCircle(cx, cy, r, stroke);
                    if (id.equals("home")) house(canvas, cx, cy, r * 0.5f, pressed, a);
                    else if (id.equals("capture")) {
                        text.setColor(pressed ? Ui.ONACC : Ui.INK); text.setAlpha(pressed ? 255 : a);
                        canvas.drawCircle(cx, cy, r * 0.3f, text);
                    } else {
                        String t = VirtualLayout.label(id);
                        label(canvas, t, cx, cy, r * (t.length() > 1 ? 0.7f : 0.95f), pressed, a);
                    }
                    break;
                }
            }
        }

        private void shade(boolean pressed, int a) {
            fill.setColor(pressed ? Ui.INK : Ui.BG);
            fill.setAlpha(pressed ? Math.min(255, a + 40) : Math.round(a * 0.55f));
        }
        private void label(Canvas canvas, String t, float x, float y, float size, boolean pressed, int a) {
            text.setTextSize(size);
            text.setColor(pressed ? Ui.ONACC : Ui.INK);
            text.setAlpha(pressed ? 255 : a);
            Paint.FontMetrics fm = text.getFontMetrics();
            canvas.drawText(t, x, y - (fm.ascent + fm.descent) / 2f, text);
        }
        private void arrow(Canvas canvas, int dir, RectF r, boolean on, int a) {
            float cx = r.centerX(), cy = r.centerY(), k = Math.min(r.width(), r.height()) * 0.22f;
            path.reset();
            switch (dir) {
                case Pad.UP:   path.moveTo(cx, cy - k); path.lineTo(cx - k, cy + k * 0.6f); path.lineTo(cx + k, cy + k * 0.6f); break;
                case Pad.DOWN: path.moveTo(cx, cy + k); path.lineTo(cx - k, cy - k * 0.6f); path.lineTo(cx + k, cy - k * 0.6f); break;
                case Pad.LEFT: path.moveTo(cx - k, cy); path.lineTo(cx + k * 0.6f, cy - k); path.lineTo(cx + k * 0.6f, cy + k); break;
                default:       path.moveTo(cx + k, cy); path.lineTo(cx - k * 0.6f, cy - k); path.lineTo(cx - k * 0.6f, cy + k); break;
            }
            path.close();
            text.setColor(on ? Ui.ONACC : Ui.INK); text.setAlpha(on ? 255 : a);
            canvas.drawPath(path, text);
        }
        private void house(Canvas canvas, float cx, float cy, float k, boolean pressed, int a) {
            path.reset();
            path.moveTo(cx, cy - k);
            path.lineTo(cx + k, cy - k * 0.05f); path.lineTo(cx + k * 0.7f, cy - k * 0.05f);
            path.lineTo(cx + k * 0.7f, cy + k * 0.85f); path.lineTo(cx - k * 0.7f, cy + k * 0.85f);
            path.lineTo(cx - k * 0.7f, cy - k * 0.05f); path.lineTo(cx - k, cy - k * 0.05f);
            path.close();
            text.setColor(pressed ? Ui.ONACC : Ui.INK); text.setAlpha(pressed ? 255 : a);
            canvas.drawPath(path, text);
        }
    }
}
