/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.switchframetap.tv.Ui.dp;

/** 0.7.16: the on-screen gamepad's layout editor, over the game.
 *
 *  The controls are dragged where they should be. A panel on the right, over
 *  everything, holds the rest - opacity, adding a removed control back, Reset,
 *  Cancel and Done; it slides away with its Hide button (the Show button in the
 *  top right corner brings it back) and fades while a control is dragged.
 *  Tapping a control selects it: a bubble above it - one at a time - makes it
 *  smaller or bigger, or removes it. Nothing is kept until Done.
 *
 *  v1.0.3: the page says EDIT MODE across the middle while it is up, because
 *  nothing else on the screen did, and both of the panel's corner buttons are
 *  words rather than icons. Back toggles the panel instead of leaving the mode -
 *  see MainActivity. */
final class VirtualEditor {
    interface Done { void done(VirtualLayout layout, float opacity); }

    static final float[] OPACITIES = { 0.25f, 0.5f, 0.75f, 1f };
    private static final int PANEL_DP = 232;

    private final Context context;
    final FrameLayout page;
    private final FrameLayout layer;
    private final Done done;
    private final Runnable cancelled;
    private VirtualLayout layout;
    private float opacity;
    private float screenWDp, screenHDp;
    private final Map<String, VirtualPad.Control> controls = new HashMap<>();
    private VirtualPad.Control selected;
    // the bubble over the selected control
    private final LinearLayout bubble;
    private final Tail tail = new Tail();
    private final ImageButton shrink, grow;
    // the panel
    private final LinearLayout panel;   // v1.0.4: a column - the ADD list scrolls, the actions do not
    private final Button reopen;   // v1.0.3: "Show", so it says what it does
    private boolean panelOpen = true;
    private final Button[] opacityButtons = new Button[OPACITIES.length];
    private final LinearLayout addList;

    VirtualEditor(Context context, Done done, Runnable cancelled) {
        this.context = context; this.done = done; this.cancelled = cancelled;
        page = new FrameLayout(context);
        page.setBackgroundColor((Ui.BG & 0x00FFFFFF) | 0x59000000);   // the game stays visible behind
        page.setClickable(true);
        page.setVisibility(View.GONE);
        page.setOnClickListener(v -> select(null));   // a tap on nothing: nothing selected

        // v1.0.3: say so, in the middle. A tap here moves a control rather than
        // reaching the Switch, and nothing else on the screen said so. Not
        // clickable - taps fall through to the page, which deselects.
        TextView mode = Ui.text(context, "EDIT MODE", 30, Ui.INK, true);
        mode.setAlpha(0.26f);
        mode.setGravity(Gravity.CENTER);
        page.addView(mode, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        layer = new FrameLayout(context);
        page.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        layer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) { place(); placeBubble(); }
        });

        // the bubble: icons only
        bubble = new LinearLayout(context);
        bubble.setGravity(Gravity.CENTER_VERTICAL);
        bubble.setBackground(tail);
        bubble.setClickable(true);
        bubble.setElevation(dp(6));
        shrink = bubbleButton(Ui.Icon.ZOOM_OUT, "Smaller", v -> resize(-VirtualLayout.SCALE_STEP));
        grow = bubbleButton(Ui.Icon.ZOOM_IN, "Bigger", v -> resize(VirtualLayout.SCALE_STEP));
        ImageButton remove = bubbleButton(Ui.Icon.TRASH, "Remove", v -> removeSelected());
        remove.setImageDrawable(new Ui.Icon(Ui.Icon.TRASH, Ui.BAD));
        bubble.addView(shrink, bubbleLp());
        bubble.addView(grow, bubbleLp());
        bubble.addView(remove, bubbleLp());
        bubble.setVisibility(View.GONE);
        page.addView(bubble, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        // v1.0.4: the panel is a column - title, opacity and the ADD list on
        // top (the list scrolls in whatever height is left), the actions fixed
        // at the bottom where nothing can scroll them away, Hide in the corner
        // below them. It was one ScrollView, so a long ADD list pushed Reset,
        // Cancel and Done off the bottom of the screen.
        panel = new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor((Ui.SURF & 0x00FFFFFF) | 0xF2000000);
        panel.setElevation(dp(10));
        panel.setClickable(true);
        panel.setPadding(dp(16), dp(10), dp(12), dp(14));
        page.addView(panel, new FrameLayout.LayoutParams(dp(PANEL_DP), -1, Gravity.END));
        LinearLayout body = panel;

        LinearLayout head = new LinearLayout(context); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(context, "Gamepad layout", 17, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(head);
        TextView hint = Ui.text(context, "Drag a control to move it. Tap one to resize or remove it.", 13, Ui.DIM, false);
        hint.setLineSpacing(dp(2), 1); hint.setPadding(0, dp(4), dp(4), 0);
        body.addView(hint);

        body.addView(section("OPACITY"));
        LinearLayout track = new LinearLayout(context);
        track.setBackground(Ui.round(Ui.SURF2, 10)); track.setPadding(dp(3), dp(3), dp(3), dp(3));
        for (int i = 0; i < OPACITIES.length; i++) {
            final float value = OPACITIES[i];
            Button b = Ui.pill(context, Math.round(value * 100) + "%", false, v -> setOpacity(value));
            b.setPadding(0, 0, 0, 0); b.setTextSize(13);
            track.addView(b, new LinearLayout.LayoutParams(0, dp(32), 1));
            opacityButtons[i] = b;
        }
        body.addView(track);

        body.addView(section("ADD"));
        addList = new LinearLayout(context); addList.setOrientation(LinearLayout.VERTICAL);
        ScrollView addScroll = new ScrollView(context);
        addScroll.addView(addList, new ScrollView.LayoutParams(-1, -2));
        // weight 1: the list takes exactly the height between the opacity
        // buttons and the actions, and scrolls inside it
        body.addView(addScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        Button reset = Ui.pill(context, "Reset layout", false, v -> reset());
        reset.setTextSize(14);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, dp(38)); rl.topMargin = dp(18);
        body.addView(reset, rl);
        LinearLayout actions = new LinearLayout(context);
        Button cancel = Ui.pill(context, "Cancel", false, v -> cancelled.run());
        Button ok = Ui.pill(context, "Done", true, v -> done.done(layout, opacity));
        cancel.setTextSize(14); ok.setTextSize(14);
        cancel.setPadding(0, 0, 0, 0); ok.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, dp(38), 1); cl.rightMargin = dp(4);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, dp(38), 1); dl.leftMargin = dp(4);
        actions.addView(cancel, cl); actions.addView(ok, dl);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2); al.topMargin = dp(8);
        body.addView(actions, al);

        // v1.0.4: Hide moved down here from the title - the panel's bottom-right
        // corner, out of the way of the actions it sits under
        LinearLayout hideRow = new LinearLayout(context); hideRow.setGravity(Gravity.END);
        Button collapse = Ui.pill(context, "Hide", false, v -> setPanel(false));
        collapse.setTextSize(13);
        collapse.setPadding(dp(12), 0, dp(12), 0);
        collapse.setContentDescription("Hide this panel");
        hideRow.addView(collapse, new LinearLayout.LayoutParams(-2, dp(32)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2); hl.topMargin = dp(8);
        body.addView(hideRow, hl);

        // v1.0.4: Show moved to the screen's bottom-right corner - the mirror of
        // the pad's own Show/Hide button at the bottom left
        reopen = Ui.pill(context, "Show", false, v -> setPanel(true));
        reopen.setTextSize(13);
        reopen.setPadding(dp(14), 0, dp(14), 0);
        reopen.setContentDescription("Show the layout panel");
        reopen.setElevation(dp(10));
        FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(-2, dp(36), Gravity.BOTTOM | Gravity.END);
        ol.setMargins(0, 0, dp(8), dp(8));
        page.addView(reopen, ol);
        reopen.setVisibility(View.GONE);
    }

    boolean open() { return page.getVisibility() == View.VISIBLE; }

    void show(VirtualLayout current, float alpha, float wDp, float hDp) {
        layout = current.copy(); opacity = alpha; screenWDp = wDp; screenHDp = hDp;
        selected = null;
        page.setVisibility(View.VISIBLE);
        page.bringToFront();
        setPanel(true);
        rebuild();
    }
    void hide() { select(null); page.setVisibility(View.GONE); }

    /* ---- the controls ---- */

    private void rebuild() {
        String keep = selected != null ? selected.id : null;
        selected = null;
        layer.removeAllViews(); controls.clear();
        for (VirtualLayout.Item i : layout.items) {
            if (!i.shown) continue;
            VirtualPad.Control c = new VirtualPad.Control(context, i.id, null, true);
            c.opacity = opacity;   // as it will look over the game
            c.editor = editing;
            controls.put(i.id, c);
            layer.addView(c, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));
        }
        place();
        select(keep != null ? controls.get(keep) : null);
        styleOpacity();
        fillAddList();
    }

    private void place() {
        int w = layer.getWidth(), h = layer.getHeight();
        if (w == 0 || h == 0 || layout == null) return;
        for (VirtualLayout.Item i : layout.items) {
            VirtualPad.Control c = controls.get(i.id);
            if (c != null) VirtualPad.position(c, i, w, h);
        }
    }

    private final VirtualPad.Editing editing = new VirtualPad.Editing() {
        @Override public void select(VirtualPad.Control c) { VirtualEditor.this.select(c); }
        @Override public void moved(VirtualPad.Control c, float x, float y) {
            VirtualLayout.Item i = layout.get(c.id);
            int w = layer.getWidth(), h = layer.getHeight();
            if (i == null || w == 0 || h == 0) return;
            // keep the whole control on the screen
            float hw = dp(VirtualLayout.widthDp(i.id) * i.scale) / 2f, hh = dp(VirtualLayout.heightDp(i.id) * i.scale) / 2f;
            i.x = VirtualLayout.clamp01(Math.max(hw, Math.min(w - hw, x)) / w);
            i.y = VirtualLayout.clamp01(Math.max(hh, Math.min(h - hh, y)) / h);
            VirtualPad.position(c, i, w, h);
        }
        @Override public void dragging(VirtualPad.Control c, boolean value) {
            if (value) {
                bubble.setVisibility(View.GONE);
                panel.animate().alpha(0.2f).setDuration(120).start();
                reopen.animate().alpha(0.2f).setDuration(120).start();
            } else {
                panel.animate().alpha(1f).setDuration(120).start();
                reopen.animate().alpha(1f).setDuration(120).start();
                // dropped under the panel: the panel makes way
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)c.getLayoutParams();
                if (panelOpen && lp.leftMargin + lp.width > layer.getWidth() - dp(PANEL_DP)) setPanel(false);
                placeBubble();
            }
        }
    };

    private void select(VirtualPad.Control c) {
        if (selected != null) { selected.selected = false; selected.invalidate(); }
        selected = c;
        if (c != null) { c.selected = true; c.invalidate(); c.bringToFront(); }
        placeBubble();
    }

    /** The bubble centred over the selected control (under it when there is no
     *  room above), its tail pointing at the control. */
    private void placeBubble() {
        if (selected == null || layout == null) { bubble.setVisibility(View.GONE); return; }
        VirtualLayout.Item i = layout.get(selected.id);
        shrink.setEnabled(i.scale > VirtualLayout.MIN_SCALE + 0.01f); shrink.setAlpha(shrink.isEnabled() ? 1f : 0.35f);
        grow.setEnabled(i.scale < VirtualLayout.MAX_SCALE - 0.01f); grow.setAlpha(grow.isEnabled() ? 1f : 0.35f);
        int w = layer.getWidth(), h = layer.getHeight();
        if (w == 0 || h == 0) return;
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams)selected.getLayoutParams();
        int cx = cl.leftMargin + cl.width / 2, top = cl.topMargin, bottom = cl.topMargin + cl.height;
        int gap = dp(6), t = dp(Tail.TAIL_DP);
        boolean below = top - gap - dp(48) - t < dp(4);
        tail.down = !below;
        bubble.setPadding(dp(4), dp(4) + (below ? t : 0), dp(4), dp(4) + (below ? 0 : t));
        bubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int bw = bubble.getMeasuredWidth(), bh = bubble.getMeasuredHeight();
        int right = (panelOpen ? w - dp(PANEL_DP) : w) - dp(6);
        int left = Math.max(dp(6), Math.min(right - bw, cx - bw / 2));
        int y = below ? bottom + gap : top - gap - bh;
        y = Math.max(dp(4), Math.min(h - bh - dp(4), y));
        tail.x = Math.max(dp(18), Math.min(bw - dp(18), cx - left));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)bubble.getLayoutParams();
        lp.leftMargin = left; lp.topMargin = y;
        bubble.setLayoutParams(lp);
        bubble.setVisibility(View.VISIBLE);
        bubble.bringToFront();
        panel.bringToFront(); reopen.bringToFront();   // the panel stays over everything
        tail.invalidateSelf();
    }

    private void resize(float step) {
        if (selected == null) return;
        VirtualLayout.Item i = layout.get(selected.id);
        i.scale = VirtualLayout.clampScale(i.scale + step);
        VirtualPad.position(selected, i, layer.getWidth(), layer.getHeight());
        placeBubble();
    }

    private void removeSelected() {
        if (selected == null) return;
        layout.get(selected.id).shown = false;
        selected = null;
        rebuild();
    }

    /* ---- the panel ---- */

    /** v1.0.3: Back does this rather than leaving the editor. */
    void togglePanel() { setPanel(!panelOpen); }

    private void setPanel(boolean value) {
        panelOpen = value;
        panel.setVisibility(value ? View.VISIBLE : View.GONE);
        reopen.setVisibility(value ? View.GONE : View.VISIBLE);
        placeBubble();
    }

    private void fillAddList() {
        addList.removeAllViews();
        List<String> hidden = layout.hidden();
        if (hidden.isEmpty()) {
            TextView none = Ui.text(context, "Every control is on the screen. Remove one with its bubble.", 13, Ui.FAINT, false);
            none.setLineSpacing(dp(2), 1);
            addList.addView(none);
            return;
        }
        LinearLayout row = null;
        for (int k = 0; k < hidden.size(); k++) {
            if (k % 2 == 0) {
                row = new LinearLayout(context);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); if (k > 0) rl.topMargin = dp(6);
                addList.addView(row, rl);
            }
            final String id = hidden.get(k);
            Button b = Ui.pill(context, "+ " + VirtualLayout.label(id), false, v -> add(id));
            b.setTextSize(13); b.setPadding(dp(6), 0, dp(6), 0);
            b.setContentDescription("Add " + VirtualLayout.name(id));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(34), 1);
            if (k % 2 == 0) bl.rightMargin = dp(3); else bl.leftMargin = dp(3);
            row.addView(b, bl);
        }
        if (hidden.size() % 2 == 1 && row != null) row.addView(new View(context), new LinearLayout.LayoutParams(0, dp(34), 1));
    }

    private void add(String id) {
        VirtualLayout.Item i = layout.get(id);
        i.shown = true;
        VirtualLayout.Item d = VirtualLayout.defaults(screenWDp, screenHDp).get(id);   // where it starts by default
        i.x = d.x; i.y = d.y;
        selected = null;
        rebuild();
        select(controls.get(id));
    }

    private void setOpacity(float value) {
        opacity = value;
        for (VirtualPad.Control c : controls.values()) { c.opacity = value; c.invalidate(); }
        styleOpacity();
    }
    private void styleOpacity() {
        for (int j = 0; j < OPACITIES.length; j++) Ui.styleSegment(opacityButtons[j], Math.abs(OPACITIES[j] - opacity) < 0.01f);
    }

    private void reset() {
        layout = VirtualLayout.defaults(screenWDp, screenHDp);
        opacity = 0.5f;
        selected = null;
        rebuild();
    }

    /* ---- small helpers ---- */

    private TextView section(String name) {
        TextView t = Ui.text(context, name, 12, Ui.FAINT, true);
        t.setLetterSpacing(0.06f); t.setPadding(0, dp(16), 0, dp(8));
        return t;
    }
    private ImageButton bubbleButton(int icon, String name, View.OnClickListener click) {
        ImageButton b = Ui.iconButton(context, icon, 0x00000000, Ui.SURF3, click);
        b.setContentDescription(name);
        b.setPadding(dp(9), dp(9), dp(9), dp(9));
        return b;
    }
    private static LinearLayout.LayoutParams bubbleLp() { return new LinearLayout.LayoutParams(dp(40), dp(40)); }

    /** The bubble's background: a rounded box with a tail towards the control. */
    private static final class Tail extends Drawable {
        static final int TAIL_DP = 7;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF box = new RectF();
        boolean down = true;   // the tail under the box (the bubble above the control)
        int x;
        Tail() {
            fill.setColor((Ui.SURF2 & 0x00FFFFFF) | 0xF5000000);
            rim.setColor(0x26FFFFFF); rim.setStyle(Paint.Style.STROKE); rim.setStrokeWidth(Math.max(1, dp(1)));
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float t = dp(TAIL_DP), r = dp(20);
            box.set(b.left, b.top + (down ? 0 : t), b.right, b.bottom - (down ? t : 0));
            path.reset();
            path.addRoundRect(box, r, r, Path.Direction.CW);
            Path tip = new Path();
            if (down) { tip.moveTo(x - t, box.bottom - 1); tip.lineTo(x, b.bottom); tip.lineTo(x + t, box.bottom - 1); }
            else { tip.moveTo(x - t, box.top + 1); tip.lineTo(x, b.top); tip.lineTo(x + t, box.top + 1); }
            tip.close();
            path.op(tip, Path.Op.UNION);
            c.drawPath(path, fill);
            c.drawPath(path, rim);
        }
        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { fill.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
