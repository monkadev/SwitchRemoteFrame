/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

import static io.github.switchframetap.tv.Ui.dp;

/** The viewer's two screens, as on the PC: a home screen while there is no
 *  picture (the state in a card, USB / Network, Connect), and a Settings
 *  drawer over the home screen or the game. Back (or OK, Menu, or the
 *  gamepad's shortcut) opens it; Back closes it, Exit in its top bar leaves.
 *  0.7.16: a gamepad connected to this device plays on the Switch (Pad.java),
 *  with its own Gamepad page for remapping and testing it. */
public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private FrameLayout root, home, drawer;
    private View scrim;
    private SurfaceView picture;
    private TextView hud, title, detail, note, ipLabel;
    private EditText ipDrawer;                 /* the drawer's copy of the home IP field */
    private LinearLayout homeIpRow;            /* the IP field on the home card */
    private Ui.Ring ring;
    private EditText ip;
    private Button action, settingsButton, homeUsb, homeNet, drawerUsb, drawerNet;
    private Ui.Toggle muteToggle, stableToggle, statsToggle;
    private TextView fpsView;                      // 0.7.16: "framerate only" - a small white number
    private boolean fpsOnly;
    // v1.0.1: the floating Settings gear over the game - see-through, hidden
    // 2 s after the last touch or mouse movement, and switchable altogether
    private Button overlayGear;
    private boolean overlayButton;
    private int overlayOpacity;                    // 0..100 percent
    private final Runnable overlayHide = new Runnable() {
        @Override public void run() { hideOverlayGear(); }
    };
    private boolean smoothVideo;   // 0.7.16: FramePacer
    private Updater updater;                       // v0.7.14: updates from GitHub
    private Button drawerDisconnect;   // touch-6: Settings' top bar
    private Button updateButton, updateAction;
    private TextView updateStatus;
    private LinearLayout ipRow;
    private boolean usbMode, resumed, surfaceReady, requested, tv, showingVideo;
    private boolean mute, stable, stats;
    // remote touch (0.7.16): the fingers on the picture go to the Switch's touch screen
    private boolean touchControl, touching;
    private final Touch.Fingers fingers = new Touch.Fingers();
    // remote gamepad (0.7.16): the gamepads here -> a Pro Controller on the Switch
    private static final float[] DEADZONES = { 0.05f, 0.10f, 0.15f, 0.25f };
    private static final String[] DEADZONE_NAMES = { "5%", "10%", "15%", "25%" };
    private PadInput padInput;
    private Pad.Mapping padMapping;
    private boolean gamepadControl;
    private int padDeadzone, padRight, padShortcut;
    private int padSuppress;          // buttons held when the game got the gamepad back: not sent until let go
    private long shortcutAt;          // when the Settings shortcut started being held (0: it is not)
    private FrameLayout padPanel;     // the Gamepad page
    private PadView padView;
    private TextView padStatus, padLast, padDrawerSub;
    private final TextView[] padBindings = new TextView[Pad.BUTTON_BITS];
    private Button[] padFaceChoice;
    private final java.util.List<Ui.Toggle> gamepadToggles = new java.util.ArrayList<>();
    private int remapBit = -1;
    private StreamEngine engine;
    // 0.7.16: the on-screen gamepad and the Switch's rumble
    static final int VPAD_OFF = 0, VPAD_AUTO = 1, VPAD_ALWAYS = 2;
    private int vpadMode;
    private float vpadOpacity;
    private boolean vpadHidden;        // hidden with the bottom-left button (remembered)
    private TextView rumbleTestResult;
    private VirtualPad virtualPad;
    private VirtualEditor vpadEditor;
    private VirtualLayout vpadLayout;
    private int vpadLaidW, vpadLaidH;
    private boolean vpadUsed;          // touched since this connection began: the controller stays on the Switch
    private boolean hasTouchscreen, gamepadNear;
    private Rumbler rumbler;
    private boolean padRumble, deviceRumble, rootRumble;
    private String message = "";
    private int consoleReason;
    private String consoleHead = "", consoleDetail = "";
    private long consoleAt;
    private long sampledNs, sampledFrames, sampledBytes, sampledReceived, sampledDecoded, sampledAudio;
    private AudioManager audioManager;
    private boolean focusGranted;
    private AudioManager.OnAudioFocusChangeListener audioFocus;
    private AudioFocusRequest audioFocusRequest;
    private int focusSerial;
    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!usbAction().equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    || (device != null && ((UsbManager)getSystemService(USB_SERVICE)).hasPermission(device));
            if (!granted) {
                requested = false; stopEngine();
                setMessage("USB access was not allowed. Select Connect to ask again.");
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        prefs = getSharedPreferences("viewer", MODE_PRIVATE);
        usbMode = prefs.getBoolean("usb", false);
        mute = prefs.getBoolean("mute", false);
        stable = prefs.getBoolean("stable_audio", false);
        stats = prefs.getBoolean("stats", false);
        touchControl = prefs.getBoolean("touch", true);
        fpsOnly = prefs.getBoolean("fps_only", false);
        overlayButton = prefs.getBoolean("overlay_button", true);
        overlayOpacity = Math.max(0, Math.min(100, prefs.getInt("overlay_opacity", 100)));
        smoothVideo = prefs.getBoolean("smooth_video", false);   // touch-6: off by default
        gamepadControl = prefs.getBoolean("gamepad", true);
        padDeadzone = Math.max(0, Math.min(DEADZONES.length - 1, prefs.getInt("pad_deadzone", 1)));
        padRight = Math.max(0, Math.min(2, prefs.getInt("pad_right", PadInput.RIGHT_AUTO)));
        padShortcut = Math.max(0, Math.min(2, prefs.getInt("pad_shortcut", Pad.SHORTCUT_MINUS_PLUS)));
        padMapping = Pad.Mapping.parse(prefs.getString("pad_map", ""), Pad.Mapping.defaults(true));
        vpadMode = Math.max(0, Math.min(2, prefs.getInt("vpad_mode", VPAD_AUTO)));
        vpadOpacity = Math.max(0.25f, Math.min(1f, prefs.getFloat("vpad_opacity", 0.5f)));
        vpadHidden = prefs.getBoolean("vpad_hidden", false);
        padRumble = prefs.getBoolean("pad_rumble", true);
        deviceRumble = prefs.getBoolean("device_rumble", true);
        hasTouchscreen = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        rumbler = new Rumbler(this);
        rumbler.setPad(padRumble); rumbler.setDevice(deviceRumble);
        rootRumble = prefs.getBoolean("pad_rumble_root", false);
        if (rootRumble) rumbler.setRoot(true, null);
        padInput = new PadInput(this, handler, this::padChanged);
        padInput.setRightStickMode(padRight);
        requested = prefs.getBoolean("auto", false);   // connect again if it was connected last time
        UiModeManager mode = (UiModeManager)getSystemService(UI_MODE_SERVICE);
        tv = mode != null && mode.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        root = new FrameLayout(this); root.setBackgroundColor(Ui.BG); setContentView(root);
        picture = new SurfaceView(this);
        root.addView(picture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        picture.getHolder().addCallback(this);
        picture.setOnClickListener(v -> openDrawer());   // a tap on the game: Settings (touch control off)
        picture.setOnTouchListener(this::pictureTouch);   // touch control on: the Switch's touch screen
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (r-l != or-ol || b-t != ob-ot) { fitPicture(); handler.post(this::loadVirtualLayout); }
        });
        installImeInsets();
        // 0.7.16: the on-screen gamepad, over the picture (a touch between its
        // controls goes on to the picture: the Switch's touch screen)
        virtualPad = new VirtualPad(this, new VirtualPad.Listener() {
            @Override public void changed() { vpadUsed = true; padChanged(); }
            @Override public void hiddenChanged(boolean hidden) {
                vpadHidden = hidden; prefs.edit().putBoolean("vpad_hidden", hidden).apply();
                padChanged();
            }
        });
        virtualPad.layer.setVisibility(View.GONE);
        virtualPad.setHidden(vpadHidden, false);
        root.addView(virtualPad.layer, new FrameLayout.LayoutParams(-1, -1));
        hud = Ui.text(this, "", 13, Ui.INK, false);
        hud.setPadding(dp(14), dp(10), dp(14), dp(10)); hud.setLineSpacing(dp(3), 1);
        hud.setBackground(Ui.round(Ui.HUD, 8)); hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hl.setMargins(dp(10), dp(10), 0, 0);
        root.addView(hud, hl);
        // 0.7.16: "framerate only" - just the frames shown each second, small and white
        fpsView = Ui.text(this, "", 13, Ui.INK, true);
        fpsView.setShadowLayer(dp(2), 0, dp(1), (Ui.BG & 0x00FFFFFF) | 0xE0000000);
        fpsView.setVisibility(View.GONE);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        fl.setMargins(dp(8), dp(6), 0, 0);
        root.addView(fpsView, fl);
        // v1.0.1: the floating Settings gear over the game - the PC viewer's
        // in-stream button, restored on Android. Non-focusable so it never
        // joins the D-pad chain while a game streams; Back still opens Settings.
        overlayGear = new Button(this);
        overlayGear.setContentDescription("Settings");
        overlayGear.setStateListAnimator(null);
        overlayGear.setFocusable(false);
        overlayGear.setFocusableInTouchMode(false);
        android.graphics.drawable.LayerDrawable overlayGearBg = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{ Ui.states(Ui.SURF2, Ui.SURF3, 20), new Ui.Gear(Ui.INK) });
        overlayGearBg.setLayerInset(1, dp(10), dp(10), dp(10), dp(10));
        overlayGear.setBackground(overlayGearBg);
        overlayGear.setAlpha(overlayOpacity / 100f);
        overlayGear.setVisibility(View.GONE);
        overlayGear.setOnClickListener(v -> openDrawer());
        FrameLayout.LayoutParams ogl = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        ogl.setMargins(0, dp(18), dp(24), 0);
        root.addView(overlayGear, ogl);
        // Settings over the game: Back (there is no button on the picture - with
        // touch control every tap there is the Switch's)
        updater = new Updater(this, this::updateUpdater);
        buildHome();
        buildDrawer();
        buildPadPanel();
        vpadEditor = new VirtualEditor(this, (layout, opacity) -> {
            vpadLayout = layout; vpadOpacity = opacity;
            prefs.edit().putString("vpad_layout", layout.serialize()).putFloat("vpad_opacity", opacity).apply();
            virtualPad.set(vpadLayout, vpadOpacity);
            closeVirtualEditor();
        }, this::closeVirtualEditor);
        root.addView(vpadEditor.page, new FrameLayout.LayoutParams(-1, -1));
        loadVirtualLayout();
        if (prefs.getBoolean("update_check", true)) updater.check(prefs.getBoolean("update_betas", false));

        IntentFilter filter = new IntentFilter(usbAction());
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(permissionReceiver, filter);
        updateMode(); updateHome(); handler.post(tick);
    }

    /* ---- the home screen ---------------------------------------------------- */

    private void buildHome() {
        home = new FrameLayout(this); home.setBackgroundColor(Ui.BG);
        home.setClickable(true);   // taps here are not for the picture behind
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        // a phone held sideways is ~360 dp tall: the card scrolls there, and sits centred on a TV
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        FrameLayout.LayoutParams scl = new FrameLayout.LayoutParams(-1, -1); scl.topMargin = dp(64);
        home.addView(scroll, scl);
        FrameLayout centre = new FrameLayout(this);
        scroll.addView(centre, new ScrollView.LayoutParams(-1, -1));

        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(new Ui.Dot(this, Ui.ACC), new LinearLayout.LayoutParams(dp(12), dp(12)));
        TextView name = Ui.text(this, "Switch Remote Frame", 21, Ui.INK, true); name.setPadding(dp(11), 0, 0, 0);
        brand.addView(name);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        bl.setMargins(dp(32), dp(24), 0, 0);
        home.addView(brand, bl);

        settingsButton = new Button(this);
        settingsButton.setContentDescription("Settings");
        settingsButton.setStateListAnimator(null);
        android.graphics.drawable.LayerDrawable gear = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{ Ui.states(Ui.SURF2, Ui.SURF3, 20), new Ui.Gear(Ui.INK) });
        gear.setLayerInset(1, dp(10), dp(10), dp(10), dp(10));
        settingsButton.setBackground(gear);
        settingsButton.setOnClickListener(v -> openDrawer());
        Ui.focusLift(settingsButton);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        gl.setMargins(0, dp(18), dp(32), 0);
        home.addView(settingsButton, gl);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL); column.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        cl.bottomMargin = dp(24);
        centre.addView(column, cl);

        LinearLayout card = new LinearLayout(this);
        final boolean dashboard = getResources().getConfiguration().screenWidthDp >= 760;
        card.setOrientation(dashboard ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.card(Ui.SURF, 22));
        card.setPadding(dp(dashboard ? 34 : 26), dp(26), dp(dashboard ? 34 : 26), dp(24));
        int width = Math.min(dp(dashboard ? 920 : 600), getResources().getDisplayMetrics().widthPixels - dp(48));
        column.addView(card, new LinearLayout.LayoutParams(width, -2));
        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(dashboard ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
        LinearLayout controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(dashboard ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams heroLp = dashboard ? new LinearLayout.LayoutParams(0, -2, 0.92f) : new LinearLayout.LayoutParams(-1, -2);
        LinearLayout.LayoutParams controlsLp = dashboard ? new LinearLayout.LayoutParams(0, -2, 1.08f) : new LinearLayout.LayoutParams(-1, -2);

        ring = new Ui.Ring(this);
        // v1.1.3: line the hero column's top up with the controls' mode switch
        // (its own 18 dp top margin) so the two columns start on one line.
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(36), dp(36));
        rlp.gravity = dashboard ? Gravity.START : Gravity.CENTER_HORIZONTAL;
        rlp.topMargin = dashboard ? dp(18) : 0;
        hero.addView(ring, rlp);
        title = Ui.text(this, "", 23, Ui.INK, true); title.setGravity(dashboard ? Gravity.START : Gravity.CENTER);
        title.setPadding(0, dp(14), 0, dp(8));
        hero.addView(title, new LinearLayout.LayoutParams(-1, -2));
        detail = Ui.text(this, "", 15, Ui.DIM, false); detail.setGravity(dashboard ? Gravity.START : Gravity.CENTER); detail.setLineSpacing(dp(3), 1);
        hero.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        note = Ui.text(this, "", 13, Ui.FAINT, false); note.setGravity(dashboard ? Gravity.START : Gravity.CENTER); note.setPadding(0, dp(6), 0, 0);
        hero.addView(note, new LinearLayout.LayoutParams(-1, -2));

        // v1.1.3: Donate lives under the status and info texts on the left of
        // the panel (this hero column), left aligned - no longer under the
        // connection controls, so both columns keep one clear job.
        Button homeDonate = Ui.pill(this, "\u2665  Support this project", true, v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://ko-fi.com/monkadev"))); }
            catch (android.content.ActivityNotFoundException ignored) { }
        });
        homeDonate.setBackground(Ui.states(Ui.HEART, 0xFFEC6266, 24));
        homeDonate.setTextColor(Ui.INK);
        homeDonate.setContentDescription("Support this project");
        Ui.focusLift(homeDonate);
        LinearLayout.LayoutParams dnl = new LinearLayout.LayoutParams(-2, dp(44));
        dnl.topMargin = dp(18); dnl.gravity = Gravity.START;
        hero.addView(homeDonate, dnl);

        // The USB | Network switch spans the whole control column, its two
        // segments splitting it evenly.
        LinearLayout segments = segmented(true); homeUsb = (Button)segments.getChildAt(0); homeNet = (Button)segments.getChildAt(1);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(18);
        controls.addView(segments, sl);

        // Connect and Settings share one full-width row, each taking half.
        LinearLayout connectRow = new LinearLayout(this);
        connectRow.setOrientation(LinearLayout.HORIZONTAL);
        action = Ui.pill(this, "Connect", true, v -> {
            if (requested) disconnect(); else connect();
        });
        Ui.focusLift(action);   // the ten-foot focus state on TV
        connectRow.addView(action, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button homeSettings = Ui.pill(this, "Settings", false, v -> openDrawer());
        Ui.focusLift(homeSettings);
        LinearLayout.LayoutParams hsl = new LinearLayout.LayoutParams(0, dp(48), 1f); hsl.leftMargin = dp(10);
        connectRow.addView(homeSettings, hsl);
        LinearLayout.LayoutParams crl = new LinearLayout.LayoutParams(-1, -2); crl.topMargin = dp(14);
        controls.addView(connectRow, crl);

        // The Switch IP address lives on the home card too, directly under the
        // Connect / Settings row (Network mode only - updateMode flips it).
        homeIpRow = new LinearLayout(this); homeIpRow.setOrientation(LinearLayout.VERTICAL);
        TextView homeIpLabel = Ui.text(this, "Switch IP address", 14, Ui.DIM, false);
        homeIpRow.addView(homeIpLabel);
        ip = buildIpField();
        LinearLayout.LayoutParams hil = new LinearLayout.LayoutParams(-1, -2); hil.topMargin = dp(8);
        homeIpRow.addView(ip, hil);
        LinearLayout.LayoutParams hirl = new LinearLayout.LayoutParams(-1, -2); hirl.topMargin = dp(14);
        controls.addView(homeIpRow, hirl);

        updateButton = Ui.pill(this, "", true, v -> updateClicked());   // v0.7.14: a newer app on GitHub
        Ui.focusLift(updateButton);
        updateButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-2, dp(48)); ul.topMargin = dp(10);
        controls.addView(updateButton, ul);

        card.addView(hero, heroLp);
        card.addView(controls, controlsLp);

        if (tv) {
            LinearLayout hints = new LinearLayout(this); hints.setGravity(Gravity.CENTER);
            hints.addView(Ui.keyHint(this, "OK", "Select"));
            hints.addView(Ui.keyHint(this, "Menu", "Settings"));
            hints.addView(Ui.keyHint(this, "Back", "Settings"));
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2); hl.topMargin = dp(24);
            column.addView(hints, hl);
        }
        action.requestFocus();
    }

    /** One IP field, styled the Aurora way and backed by the "ip" preference.
     *  The home card and the drawer each own an instance; both persist on
     *  focus loss and refresh from the preference when their surface opens. */
    private EditText buildIpField() {
        EditText e = new EditText(this);
        e.setTextColor(Ui.INK); e.setHintTextColor(Ui.FAINT); e.setTypeface(Ui.regular); e.setTextSize(15);
        e.setSingleLine(true);
        e.setHint("Blank: find it on the network");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setText(prefs.getString("ip", "")); e.setSelectAllOnFocus(true);
        e.setBackground(Ui.states(Ui.SURF2, Ui.SURF2, 10)); e.setPadding(dp(14), dp(9), dp(14), dp(9));
        e.setOnFocusChangeListener((v, focused) -> {
            if (!focused) {
                prefs.edit().putString("ip", e.getText().toString().trim()).apply();
            } else {
                // v1.1.4: start scrolling clear immediately; installImeInsets
                // then keeps the field above the keyboard once it has opened
                v.post(() -> v.requestRectangleOnScreen(new Rect(0, 0, v.getWidth(), v.getHeight()), true));
            }
        });
        return e;
    }

    /** USB | Network on a SURF2 track. Full width on the home card (the two
     *  segments split it evenly); wrap content in the drawer's connection row. */
    private LinearLayout segmented(boolean fullWidth) {
        LinearLayout track = new LinearLayout(this);
        track.setBackground(Ui.round(Ui.SURF2, 10)); track.setPadding(dp(3), dp(3), dp(3), dp(3));
        Button usb = Ui.pill(this, "USB", false, v -> chooseMode(true));
        Button net = Ui.pill(this, "Network", false, v -> chooseMode(false));
        for (Button b : new Button[]{usb, net}) {
            b.setPadding(dp(18), 0, dp(18), 0);
            track.addView(b, new LinearLayout.LayoutParams(fullWidth ? 0 : -2, dp(48), fullWidth ? 1f : 0f));
        }
        return track;
    }

    /* ---- the settings drawer ------------------------------------------------ */

    private void buildDrawer() {
        scrim = new View(this); scrim.setBackgroundColor((Ui.BG & 0x00FFFFFF) | 0x99000000); scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        drawer = new FrameLayout(this); drawer.setBackgroundColor(Ui.SURF); drawer.setVisibility(View.GONE);
        drawer.setClickable(true);
        View drawerEdge = new View(this); drawerEdge.setBackgroundColor(Ui.HAIR);
        FrameLayout.LayoutParams edgeLp = new FrameLayout.LayoutParams(dp(1), -1, Gravity.START);
        drawer.addView(drawerEdge, edgeLp);
        int screenDp = getResources().getConfiguration().screenWidthDp;
        int screenPx = getResources().getDisplayMetrics().widthPixels;
        // Settings is a full-page surface on phones, a focused side panel on tablets/TV.
        int w = screenDp < 600 ? screenPx : Math.min(dp(520), (int)(screenPx * 0.88f));
        root.addView(drawer, new FrameLayout.LayoutParams(w, -1, Gravity.END));

        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        drawer.addView(column, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(26), dp(20), dp(20), dp(14));
        LinearLayout headText = new LinearLayout(this); headText.setOrientation(LinearLayout.VERTICAL);
        TextView headTitle = Ui.text(this, "Settings", 25, Ui.INK, true);
        headTitle.setSingleLine(true); headTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headText.addView(headTitle);
        TextView headSub = Ui.text(this, "Connection · playback · controls", 12, Ui.DIM, false);
        headSub.setSingleLine(true); headSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headText.addView(headSub);
        head.addView(headText, new LinearLayout.LayoutParams(0, -2, 1));
        // touch-6: stop the stream from here (shown while connected or connecting)
        drawerDisconnect = Ui.pill(this, "Disconnect", false, v -> { disconnect(); closeDrawer(); });
        drawerDisconnect.setContentDescription("Disconnect from the Switch");
        drawerDisconnect.setPadding(dp(14), 0, dp(14), 0);
        Ui.focusLift(drawerDisconnect);
        drawerDisconnect.setVisibility(requested ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, dp(48)); dl.leftMargin = dp(8);
        head.addView(drawerDisconnect, dl);
        // 0.7.16: Back opens Settings everywhere, so leaving the app is a button here
        Button exit = Ui.pill(this, "Exit", false, v -> exitApp());
        exit.setContentDescription("Exit the app");
        exit.setPadding(dp(14), 0, dp(14), 0);
        Ui.focusLift(exit);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-2, dp(48)); el.leftMargin = dp(8);
        head.addView(exit, el);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, dp(48)); cl.leftMargin = dp(8);
        Button close = Ui.pill(this, "Close", false, v -> closeDrawer());
        close.setPadding(dp(14), 0, dp(14), 0);
        Ui.focusLift(close);
        head.addView(close, cl);
        column.addView(head);
        View line = new View(this); line.setBackgroundColor(Ui.HAIR);
        column.addView(line, new LinearLayout.LayoutParams(-1, 1));

        ScrollView scroll = new ScrollView(this);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(6), dp(14), dp(16));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        section(body, "CONNECTION");
        LinearLayout conn = new LinearLayout(this); conn.setGravity(Gravity.CENTER_VERTICAL);
        conn.setPadding(dp(12), dp(8), dp(12), dp(8));
        conn.addView(Ui.text(this, "Connection", 16, Ui.INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout seg = segmented(false); drawerUsb = (Button)seg.getChildAt(0); drawerNet = (Button)seg.getChildAt(1);
        conn.addView(seg);
        body.addView(conn);
        ipRow = new LinearLayout(this); ipRow.setOrientation(LinearLayout.VERTICAL);
        ipRow.setPadding(dp(12), dp(4), dp(12), dp(4));
        ipRow.addView(Ui.text(this, "Switch IP address", 16, Ui.INK, false));
        ipDrawer = buildIpField();
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2); il.topMargin = dp(8);
        ipRow.addView(ipDrawer, il);
        body.addView(ipRow);
        ipLabel = Ui.text(this, "", 13, Ui.DIM, false); ipLabel.setPadding(dp(12), dp(6), dp(12), dp(6));
        ipLabel.setLineSpacing(dp(3), 1);
        body.addView(ipLabel);
        // v1.1.3: moved out of DISPLAY to the bottom of CONNECTION - it changes
        // how the network stream is paced, and it costs input latency, so the
        // description now says so plainly.
        toggleRow(body, "Smooth video", "Play frames in order to stop stutter on bursty Wi-Fi. Increases input latency by up to two frames; off is the lowest-latency, jumpier picture", smoothVideo, value -> {
            smoothVideo = value; prefs.edit().putBoolean("smooth_video", value).apply();
            if (engine != null) { stopEngine(); startEngine(); }
        });

        section(body, "SOUND");
        muteToggle = toggleRow(body, "Mute", "This device only - the Switch keeps its own sound", mute, value -> {
            mute = value; prefs.edit().putBoolean("mute", value).apply();
            if (engine != null) engine.mute(value || !focusGranted);
        });
        stableToggle = toggleRow(body, "Stable audio", "A larger buffer (60 ms) if the sound drops out", stable, value -> {
            stable = value; prefs.edit().putBoolean("stable_audio", value).apply();
            if (engine != null) { stopEngine(); startEngine(); }
        });

        section(body, "DISPLAY");
        statsToggle = toggleRow(body, "Stats overlay", "Frame rates, bitrate, decoder and sound", stats, value -> {
            stats = value; prefs.edit().putBoolean("stats", value).apply();
            hud.setVisibility(value && engine != null ? View.VISIBLE : View.GONE);
            updateFps();
        });
        toggleRow(body, "Framerate only", "A small white number in the corner: the frames shown each second (the stats overlay replaces it while on)", fpsOnly, value -> {
            fpsOnly = value; prefs.edit().putBoolean("fps_only", value).apply();
            updateFps();
        });
        // v1.0.1: the floating Settings gear over the game - show/hide and how
        // see-through it is. The gear fades away 2 s after the last touch or
        // mouse movement; 0% keeps it completely out of the way.
        final View[] opacityViews = new View[2];
        toggleRow(body, "Settings button", "Show the floating gear over the game. It fades away 2 seconds after the last touch or mouse movement, and a tap on it opens Settings", overlayButton, value -> {
            overlayButton = value; prefs.edit().putBoolean("overlay_button", value).apply();
            for (View v : opacityViews) if (v != null) { v.setEnabled(value); v.setAlpha(value ? 1f : 0.4f); }
            updateOverlayGear();
            if (value) pokeOverlay();
        });
        LinearLayout opRow = new LinearLayout(this);
        opRow.setOrientation(LinearLayout.VERTICAL);
        opRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        opRow.setFocusable(true); opRow.setClickable(true); opRow.setBackground(Ui.rowBackground());
        LinearLayout opHead = new LinearLayout(this); opHead.setGravity(Gravity.CENTER_VERTICAL);
        opHead.addView(Ui.text(this, "Settings button opacity", 16, Ui.INK, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        final TextView opPercent = Ui.text(this, overlayOpacity + "%", 15, Ui.DIM, true);
        opHead.addView(opPercent);
        opRow.addView(opHead);
        TextView opSub = Ui.text(this, "100% is solid; 0% hides the gear completely", 13, Ui.DIM, false);
        opSub.setPadding(0, dp(5), 0, 0);
        opRow.addView(opSub);
        final android.widget.SeekBar opBar = new android.widget.SeekBar(this);
        opBar.setMax(100); opBar.setProgress(overlayOpacity);
        opBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACC));
        opBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.SURF3));
        opBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.INK));
        opBar.setPadding(0, dp(8), 0, 0);
        opBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                overlayOpacity = progress;
                opPercent.setText(progress + "%");
                prefs.edit().putInt("overlay_opacity", progress).apply();
                overlayGear.setAlpha(progress / 100f);
                if (progress == 0) updateOverlayGear();
                else if (overlayButton && overlayGear.getVisibility() == View.VISIBLE)
                    handler.removeCallbacks(overlayHide);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                if (overlayButton && overlayOpacity > 0) pokeOverlay();
                else updateOverlayGear();
            }
        });
        opRow.addView(opBar);
        body.addView(opRow);
        opacityViews[0] = opRow; opacityViews[1] = opBar;
        for (View v : opacityViews) { v.setEnabled(overlayButton); v.setAlpha(overlayButton ? 1f : 0.4f); }

        section(body, "CONTROL");
        toggleRow(body, "Touch control", "Touching the picture touches the Switch's screen (needs Switch Remote Frame on the Switch). Off: a tap opens Settings", touchControl, value -> {
            liftFingers();
            touchControl = value; prefs.edit().putBoolean("touch", value).apply();
        });
        gamepadToggles.add(toggleRow(body, "Gamepad control", "A gamepad connected to this device plays on the Switch as a Pro Controller. Hold − and + (or press Back) for Settings", gamepadControl, this::setGamepadControl));
        LinearLayout padRow = new LinearLayout(this); padRow.setGravity(Gravity.CENTER_VERTICAL);
        padRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        padRow.setFocusable(true); padRow.setClickable(true); padRow.setBackground(Ui.rowBackground());
        LinearLayout padTexts = new LinearLayout(this); padTexts.setOrientation(LinearLayout.VERTICAL);
        padTexts.addView(Ui.text(this, "Gamepad settings", 16, Ui.INK, false));
        padDrawerSub = Ui.text(this, "", 13, Ui.DIM, false); padDrawerSub.setPadding(0, dp(5), 0, 0);
        padTexts.addView(padDrawerSub);
        padRow.addView(padTexts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = Ui.text(this, "›", 24, Ui.DIM, false);
        padRow.addView(chevron);
        padRow.setOnClickListener(v -> openPadPanel());
        body.addView(padRow);
        choiceRow(body, "On-screen gamepad", "Pro Controller buttons and sticks drawn over the game. Auto: on a touch screen while no gamepad is connected", new String[]{ "Off", "Auto", "Always" }, vpadMode, index -> {
            vpadMode = index; prefs.edit().putInt("vpad_mode", index).apply();
            if (index == VPAD_OFF) vpadUsed = false;
            updateVirtualPad(); padChanged();
        });
        body.addView(chevronRow("On-screen gamepad layout", "Move, resize, add or remove its buttons, and set how see-through they are", v -> openVirtualEditor()));
        boolean canVibrate = rumbler.deviceCanVibrate();
        toggleRow(body, "Device vibration", canVibrate ? "This phone or tablet vibrates when the game rumbles the controller (needs the console build with rumble, and \"Vibration to the viewer\" on in its app)"
                : "This device cannot vibrate. A connected gamepad still rumbles: Gamepad settings > Gamepad vibration", deviceRumble, value -> {
            deviceRumble = value; prefs.edit().putBoolean("device_rumble", value).apply();
            rumbler.setDevice(value); updateWantRumble();
            if (value) rumbler.testDevice();
        });

        section(body, "UPDATES");
        LinearLayout upd = new LinearLayout(this); upd.setGravity(Gravity.CENTER_VERTICAL);
        upd.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout ut = new LinearLayout(this); ut.setOrientation(LinearLayout.VERTICAL);
        ut.addView(Ui.text(this, "Switch Remote Frame " + Updater.version(this), 16, Ui.INK, false));
        updateStatus = Ui.text(this, "", 13, Ui.DIM, false); updateStatus.setPadding(0, dp(5), 0, 0);
        ut.addView(updateStatus);
        upd.addView(ut, new LinearLayout.LayoutParams(0, -2, 1));
        updateAction = Ui.pill(this, "Check", false, v -> updateClicked());
        LinearLayout.LayoutParams ual = new LinearLayout.LayoutParams(-2, dp(48)); ual.leftMargin = dp(12);
        upd.addView(updateAction, ual);
        body.addView(upd);
        toggleRow(body, "Include betas", "Offer test versions too, not only full releases", prefs.getBoolean("update_betas", false), value -> {
            prefs.edit().putBoolean("update_betas", value).apply();
            if (updater.state == Updater.State.LATEST || updater.state == Updater.State.FOUND) updater.check(value);
        });
        toggleRow(body, "Check at start-up", "Look for a new version when the app opens", prefs.getBoolean("update_check", true), value ->
            prefs.edit().putBoolean("update_check", value).apply());

        section(body, "ABOUT");
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException ignored) { }
        TextView about = Ui.text(this, "Switch Remote Frame " + version + "\n"
                + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + "\n"
                + "GPL-2.0 · github.com/monkadev/SwitchRemoteFrame\n\n"
                + "Touching the picture touches the Switch's screen, and a gamepad connected here plays as a Pro Controller. Quality and resolution are chosen on the console.", 13, Ui.DIM, false);
        about.setLineSpacing(dp(4), 1); about.setPadding(dp(12), dp(4), dp(12), dp(8));
        about.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        about.setLinkTextColor(Ui.ACC);
        about.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        about.setLinksClickable(true);
        body.addView(about);

        if (tv) {
            View line2 = new View(this); line2.setBackgroundColor(Ui.HAIR);
            column.addView(line2, new LinearLayout.LayoutParams(-1, 1));
            LinearLayout hints = new LinearLayout(this); hints.setGravity(Gravity.CENTER_VERTICAL);
            hints.setPadding(dp(24), dp(12), dp(12), dp(14));
            hints.addView(Ui.keyHint(this, "Back", "close"));
            hints.addView(Ui.keyHint(this, "Up/Down", "move"));
            hints.addView(Ui.keyHint(this, "OK", "change"));
            column.addView(hints);
        }
    }
    private void section(LinearLayout body, String name) {
        TextView t = Ui.text(this, name, 12, Ui.FAINT, true);
        t.setLetterSpacing(0.06f); t.setPadding(dp(12), dp(18), dp(12), dp(8));
        body.addView(t);
    }
    /** A row that opens a page of its own (a chevron at the end). */
    private LinearLayout chevronRow(String label, String sub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setFocusable(true); row.setClickable(true); row.setBackground(Ui.rowBackground());
        LinearLayout texts = new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, label, 16, Ui.INK, false));
        TextView s = Ui.text(this, sub, 13, Ui.DIM, false); s.setPadding(0, dp(5), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(Ui.text(this, "›", 24, Ui.DIM, false));
        row.setOnClickListener(click);
        return row;
    }
    private interface OnToggle { void changed(boolean value); }
    private Ui.Toggle toggleRow(LinearLayout body, String label, String sub, boolean on, OnToggle changed) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setFocusable(true); row.setClickable(true); row.setBackground(Ui.rowBackground());
        LinearLayout texts = new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, label, 16, Ui.INK, false));
        TextView s = Ui.text(this, sub, 13, Ui.DIM, false); s.setPadding(0, dp(5), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.Toggle toggle = new Ui.Toggle(this); toggle.set(on);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(44), dp(24)); tl.leftMargin = dp(16);
        row.addView(toggle, tl);
        final boolean[] state = {on};
        row.setOnClickListener(v -> { state[0] = !state[0]; toggle.set(state[0]); changed.changed(state[0]); });
        body.addView(row);
        return toggle;
    }
    /* ---- 0.7.16: the Gamepad page - remap and test --------------------------- */

    private interface OnChoice { void chosen(int index); }
    /** A label over a row of choices (a segmented control); returns the choices. */
    private Button[] choiceRow(LinearLayout body, String label, String sub, String[] options, int selected, OnChoice chosen) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.addView(Ui.text(this, label, 16, Ui.INK, false));
        if (sub != null) {
            TextView t = Ui.text(this, sub, 13, Ui.DIM, false); t.setPadding(0, dp(5), 0, 0); t.setLineSpacing(dp(2), 1);
            row.addView(t);
        }
        LinearLayout track = new LinearLayout(this);
        track.setBackground(Ui.round(Ui.SURF2, 10)); track.setPadding(dp(3), dp(3), dp(3), dp(3));
        final Button[] buttons = new Button[options.length];
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            Button b = Ui.pill(this, options[i], false, v -> {
                for (int k = 0; k < buttons.length; k++) Ui.styleSegment(buttons[k], k == index);
                chosen.chosen(index);
            });
            b.setPadding(dp(14), 0, dp(14), 0); b.setTextSize(14);
            Ui.styleSegment(b, i == selected);
            track.addView(b, new LinearLayout.LayoutParams(-2, dp(48)));
            buttons[i] = b;
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2); tl.topMargin = dp(8);
        row.addView(track, tl);
        body.addView(row);
        return buttons;
    }

    private void buildPadPanel() {
        padPanel = new FrameLayout(this); padPanel.setBackgroundColor(Ui.BG); padPanel.setVisibility(View.GONE);
        padPanel.setClickable(true);
        root.addView(padPanel, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        padPanel.addView(column, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(26), dp(16), dp(20), dp(12));
        head.addView(Ui.text(this, "Gamepad", 25, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button padDone = Ui.pill(this, "Done", true, v -> closePadPanel());
        Ui.focusLift(padDone);
        head.addView(padDone, new LinearLayout.LayoutParams(-2, dp(48)));
        column.addView(head);
        View line = new View(this); line.setBackgroundColor(Ui.HAIR);
        column.addView(line, new LinearLayout.LayoutParams(-1, 1));

        final boolean widePadLayout = getResources().getConfiguration().screenWidthDp >= 700;
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(widePadLayout ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        column.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));

        // left on wide screens, upper pane on phones: live input test
        LinearLayout test = new LinearLayout(this); test.setOrientation(LinearLayout.VERTICAL);
        test.setPadding(dp(24), dp(14), dp(12), dp(14));
        split.addView(test, widePadLayout
                ? new LinearLayout.LayoutParams(0, -1, 0.9f)
                : new LinearLayout.LayoutParams(-1, 0, 0.43f));
        padStatus = Ui.text(this, "", 15, Ui.INK, true); padStatus.setLineSpacing(dp(3), 1);
        test.addView(padStatus);
        padView = new PadView(this);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, 0, 1); pl.topMargin = dp(8); pl.bottomMargin = dp(8);
        test.addView(padView, pl);
        padLast = Ui.text(this, "", 13, Ui.DIM, false);
        test.addView(padLast);
        TextView testNote = Ui.text(this, "Press anything on the gamepad: what lights up is what the Switch gets, with the mapping on the right. Nothing reaches the Switch while this page is open.", 12, Ui.FAINT, false);
        testNote.setLineSpacing(dp(3), 1); testNote.setPadding(0, dp(8), 0, 0);
        test.addView(testNote);

        // right: the settings and the mapping
        ScrollView scroll = new ScrollView(this);
        split.addView(scroll, widePadLayout
                ? new LinearLayout.LayoutParams(0, -1, 1.1f)
                : new LinearLayout.LayoutParams(-1, 0, 0.57f));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(8), dp(4), dp(18), dp(16));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        gamepadToggles.add(toggleRow(body, "Gamepad control", "Play the Switch with this gamepad while the picture shows. Needs the console build with remote input, and \"Gamepad from the viewer\" on in its app", gamepadControl, this::setGamepadControl));
        toggleRow(body, "Gamepad vibration", "The gamepad rumbles when the game rumbles the controller (gamepads with motors; needs \"Vibration to the viewer\" on in the console's app)", padRumble, value -> {
            padRumble = value; prefs.edit().putBoolean("pad_rumble", value).apply();
            rumbler.setPad(value); updateWantRumble();
        });
        LinearLayout testRow = new LinearLayout(this); testRow.setPadding(dp(12), dp(2), dp(12), dp(8));
        testRow.setGravity(Gravity.CENTER_VERTICAL);
        testRow.addView(Ui.pill(this, "Test vibration", false, v -> {
            rumbleTestResult.setText("Testing…"); rumbleTestResult.setTextColor(Ui.DIM);
            rumbler.testPads((withMotors, gamepads, root) -> handler.post(() -> showRumbleTest(withMotors, gamepads, root)));
        }), new LinearLayout.LayoutParams(-2, dp(48)));
        rumbleTestResult = Ui.text(this, "Works whether or not this device can vibrate", 13, Ui.DIM, false);
        rumbleTestResult.setPadding(dp(12), 0, 0, 0);
        testRow.addView(rumbleTestResult, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(testRow);
        // 0.7.16: Xbox controllers over Bluetooth on kernels without their rumble
        boolean su = Rumbler.rootPossible();
        toggleRow(body, "Vibration through root", su
                ? "For an Xbox controller over Bluetooth when the test finds no motor: some kernels (such as the Xiaomi Pad 5's) cannot rumble it, so the app sends the rumble to it directly. Needs root - the root manager asks once"
                : "For an Xbox controller over Bluetooth when the test finds no motor. Needs root (KernelSU, Magisk...), and none was found on this device",
                rootRumble, value -> {
            rootRumble = value; prefs.edit().putBoolean("pad_rumble_root", value).apply();
            if (value) { rumbleTestResult.setText("Asking for root…"); rumbleTestResult.setTextColor(Ui.DIM); }
            else { rumbleTestResult.setText("Vibration through root is off"); rumbleTestResult.setTextColor(Ui.DIM); }
            rumbler.setRoot(value, value ? (withMotors, gamepads, root) -> handler.post(() -> showRootScan(root)) : null);
        });
        section(body, "LAYOUT");
        padFaceChoice = choiceRow(body, "A B X Y", "Switch positions: the bottom button is B, the right one A, as on a Switch controller. Letters: the button marked A is A", new String[]{ "Switch positions", "Letters" },
                padMapping.facePreset() - 1, index -> { padMapping.face(index == 0); savePadMapping(); });
        choiceRow(body, "Stick dead zone", "Ignore this much stick travel around the centre (more if a stick drifts)", DEADZONE_NAMES, padDeadzone, index -> {
            padDeadzone = index; prefs.edit().putInt("pad_deadzone", index).apply(); padChanged();
        });
        choiceRow(body, "Right stick axes", "Auto suits most gamepads. Try the others if the right stick moves the wrong way or works as the triggers", new String[]{ "Auto", "Z / RZ", "RX / RY" }, padRight, index -> {
            padRight = index; prefs.edit().putInt("pad_right", index).apply(); padInput.setRightStickMode(index); padChanged();
        });
        choiceRow(body, "Settings from the gamepad", "Hold these together for a moment to open Settings over the game. Back always opens it too", new String[]{ "Hold − and +", "Hold both sticks", "Off" }, padShortcut, index -> {
            padShortcut = index; prefs.edit().putInt("pad_shortcut", index).apply();
        });

        section(body, "BUTTONS");
        TextView how = Ui.text(this, "Select a Switch button, then press the gamepad button (or trigger, or D-pad direction) that should be it. Long-press to clear one.", 13, Ui.DIM, false);
        how.setLineSpacing(dp(3), 1); how.setPadding(dp(12), 0, dp(12), dp(8));
        body.addView(how);
        for (final int bit : Pad.BUTTONS) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setFocusable(true); row.setClickable(true); row.setLongClickable(true); row.setBackground(Ui.rowBackground());
            row.addView(Ui.text(this, Pad.buttonName(bit), 16, Ui.INK, false), new LinearLayout.LayoutParams(0, -2, 1));
            TextView binding = Ui.text(this, "", 14, Ui.DIM, false);
            binding.setGravity(Gravity.END);
            row.addView(binding, new LinearLayout.LayoutParams(0, -2, 1.2f));
            padBindings[bit] = binding;
            row.setOnClickListener(v -> startRemap(bit));
            row.setOnLongClickListener(v -> {
                cancelRemap();
                padMapping.clear(bit); savePadMapping();
                return true;
            });
            body.addView(row);
        }
        LinearLayout resets = new LinearLayout(this); resets.setPadding(dp(12), dp(14), dp(12), dp(4));
        resets.addView(Ui.pill(this, "Reset buttons", false, v -> {
            cancelRemap();
            padMapping = Pad.Mapping.defaults(padMapping.facePreset() != 2);
            savePadMapping();
        }), new LinearLayout.LayoutParams(-2, dp(48)));
        body.addView(resets);
        refreshBindings();
    }

    private boolean padPanelOpen() { return padPanel != null && padPanel.getVisibility() == View.VISIBLE; }
    private void openPadPanel() {
        padPanel.setVisibility(View.VISIBLE);
        padPanel.bringToFront();
        refreshBindings(); updatePadStatus(); padChanged();
        View first = ((ViewGroup)((ViewGroup)padPanel.getChildAt(0)).getChildAt(0)).getChildAt(1);   // Done
        first.requestFocus();
    }
    private void closePadPanel() {
        cancelRemap();
        padPanel.setVisibility(View.GONE);
        updatePadStatus();
        padChanged();
    }

    private void setGamepadControl(boolean value) {
        gamepadControl = value; prefs.edit().putBoolean("gamepad", value).apply();
        for (Ui.Toggle t : gamepadToggles) t.set(value);
        updatePadStatus();
        padChanged();
    }
    private void savePadMapping() {
        prefs.edit().putString("pad_map", padMapping.serialize()).apply();
        refreshBindings();
        padChanged();
    }
    private void refreshBindings() {
        for (int bit : Pad.BUTTONS) {
            TextView t = padBindings[bit];
            if (t == null) continue;
            if (bit == remapBit) { t.setText("Press a button…"); t.setTextColor(Ui.ACC); continue; }
            t.setText(padMapping.describe(bit, padInput::name));
            t.setTextColor(padMapping.codes[bit].length == 0 ? Ui.FAINT : Ui.DIM);
        }
        if (padFaceChoice != null) {
            int preset = padMapping.facePreset();
            for (int k = 0; k < padFaceChoice.length; k++) Ui.styleSegment(padFaceChoice[k], k == preset - 1);
        }
    }
    private final Runnable remapTimeout = this::cancelRemap;
    private void startRemap(int bit) {
        remapBit = bit;
        padInput.startCapture(code -> {
            if (remapBit < 0) return;
            int target = remapBit;
            remapBit = -1;
            handler.removeCallbacks(remapTimeout);
            padMapping.bind(target, code);
            savePadMapping();
        });
        handler.removeCallbacks(remapTimeout);
        handler.postDelayed(remapTimeout, 8000);
        refreshBindings();
    }
    private void cancelRemap() {
        handler.removeCallbacks(remapTimeout);
        if (remapBit < 0 && !padInput.capturing()) return;
        padInput.cancelCapture();
        remapBit = -1;
        refreshBindings();
    }
    private void updatePadStatus() {
        java.util.List<String> names = padInput.names();
        String connected = names.isEmpty() ? "" : android.text.TextUtils.join(", ", names);
        if (padStatus != null) {
            padStatus.setText(names.isEmpty() ? "No gamepad connected\nPair one over Bluetooth or plug it in by USB."
                    : (names.size() == 1 ? "Connected: " : "Connected (played as one): ") + connected);
            padStatus.setTextColor(names.isEmpty() ? Ui.WARN : Ui.INK);
        }
        if (padDrawerSub != null)
            padDrawerSub.setText((names.isEmpty() ? "No gamepad connected" : connected) + " · remap and test the buttons");
    }

    /** A key, an axis or a gamepad changed: the tester, the Settings shortcut, and
     *  the controller for the Switch. */
    private void padChanged() {
        if (padInput == null) return;
        Pad.State raw = padInput.state(padMapping, DEADZONES[padDeadzone]);
        if (padPanelOpen()) {
            padView.set(raw, raw.connected || padInput.anyConnected());
            String last = padInput.lastInput();
            padLast.setText(last.isEmpty() ? "Last input: -" : "Last input: " + last);
            updatePadStatus();
        }
        StreamEngine e = engine;
        if (e == null) return;
        Pad.State out;
        // 0.7.16: the on-screen gamepad, played together with the real ones (it
        // stays connected, nothing held, once used - until the connection ends)
        Pad.State virtual = !vpadUsed || vpadMode == VPAD_OFF ? Pad.State.NONE
                : virtualPad.shown() ? virtualPad.state() : Pad.State.IDLE;
        if (!gamepadControl || !raw.connected) {
            out = Pad.State.NONE;
            shortcutAt = 0;
        } else if (!padForwarding()) {
            out = Pad.State.IDLE;       // the controller stays on the Switch, nothing held
            shortcutAt = 0;
        } else {
            padSuppress &= raw.buttons;
            int buttons = raw.buttons & ~padSuppress;
            int combo = Pad.shortcutMask(padShortcut);
            if (combo != 0 && (raw.buttons & combo) == combo) {
                buttons &= ~combo;      // the shortcut is not the game's
                if (shortcutAt == 0) {
                    shortcutAt = android.os.SystemClock.uptimeMillis();
                    handler.postDelayed(shortcutCheck, Pad.SHORTCUT_HOLD_MS);
                }
            } else {
                shortcutAt = 0;
            }
            out = new Pad.State(true, buttons, raw.lx, raw.ly, raw.rx, raw.ry);
        }
        e.pad(Pad.merge(out, virtual));
    }
    private final Runnable shortcutCheck = () -> {
        if (shortcutAt != 0 && android.os.SystemClock.uptimeMillis() - shortcutAt >= Pad.SHORTCUT_HOLD_MS - 10) {
            shortcutAt = 0;
            openDrawer();
        }
    };
    private void showRumbleTest(java.util.List<String> withMotors, int gamepads, RootRumble.Scan root) {
        if (rumbleTestResult == null) return;
        boolean rootWorks = root != null && root.error == null;
        StringBuilder t = new StringBuilder();
        if (!withMotors.isEmpty()) t.append("Vibrating: ").append(android.text.TextUtils.join(", ", withMotors));
        if (rootWorks) t.append(t.length() > 0 ? "\n" : "").append("Vibrating through root: ").append(android.text.TextUtils.join(", ", root.names));
        if (t.length() > 0) {
            rumbleTestResult.setText(t); rumbleTestResult.setTextColor(Ui.GOOD);
            return;
        }
        if (gamepads == 0) t.append("No gamepad connected");
        else {
            t.append(gamepads == 1 ? "Android finds no motor in this gamepad (this device's kernel)"
                                   : "Android finds no motor in these gamepads (this device's kernel)");
            if (root != null) t.append(". Root: ").append(root.error);
            else t.append(". Xbox controller? Try a USB cable, or Vibration through root below");
        }
        rumbleTestResult.setText(t); rumbleTestResult.setTextColor(Ui.WARN);
    }
    private void showRootScan(RootRumble.Scan root) {
        if (rumbleTestResult == null || root == null || !rootRumble) return;
        if (root.error == null) {
            rumbleTestResult.setText("Through root: " + android.text.TextUtils.join(", ", root.names) + " - press Test vibration");
            rumbleTestResult.setTextColor(Ui.GOOD);
        } else {
            rumbleTestResult.setText(root.error);
            rumbleTestResult.setTextColor(Ui.WARN);
        }
    }

    /* ---- 0.7.16: the on-screen gamepad ---------------------------------------- */

    /** The saved layout (or the default one) for this screen's size. */
    private void loadVirtualLayout() {
        if (virtualPad == null) return;
        int w = root.getWidth(), h = root.getHeight();
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        if (w == 0 || h == 0) { w = Math.max(m.widthPixels, m.heightPixels); h = Math.min(m.widthPixels, m.heightPixels); }
        if (vpadLayout != null && w == vpadLaidW && h == vpadLaidH) return;
        vpadLaidW = w; vpadLaidH = h;
        vpadLayout = VirtualLayout.parse(prefs.getString("vpad_layout", ""), w / m.density, h / m.density);
        virtualPad.set(vpadLayout, vpadOpacity);
    }
    /** Shown over the game while nothing else is: Always, or Auto on a touch
     *  screen with no gamepad connected. */
    private void updateVirtualPad() {
        if (virtualPad == null) return;
        boolean wanted = vpadMode == VPAD_ALWAYS || (vpadMode == VPAD_AUTO && hasTouchscreen && !gamepadNear);
        boolean show = wanted && engine != null && showingVideo && !drawerOpen() && !padPanelOpen()
                && (vpadEditor == null || !vpadEditor.open());
        if (show != virtualPad.shown()) { virtualPad.show(show); padChanged(); }
    }
    private void openVirtualEditor() {
        loadVirtualLayout();
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        int w = root.getWidth(), h = root.getHeight();
        vpadEditor.show(vpadLayout, vpadOpacity, w / m.density, h / m.density);
        drawer.setAlpha(0); scrim.setAlpha(0);   // the game behind, to place the controls over it
        liftFingers();
        updateVirtualPad();
        padChanged();   // the game lets go of the controller while the layout is edited
    }
    private void closeVirtualEditor() {
        vpadEditor.hide();
        drawer.setAlpha(1); scrim.setAlpha(1);
        suppressHeld();
        updateVirtualPad();
        padChanged();
        if (drawerOpen()) (usbMode ? drawerUsb : drawerNet).requestFocus();
    }
    /** Ask the console for its rumble only while something here can rumble. */
    private void updateWantRumble() {
        StreamEngine e = engine;
        if (e != null) e.wantRumble(padRumble || (deviceRumble && rumbler.deviceCanVibrate()));
        if (!padRumble && !deviceRumble) rumbler.stop();
    }

    /** The gamepad is the Switch's now: the game shows and nothing is over it. */
    private boolean padForwarding() {
        return gamepadControl && engine != null && showingVideo && !drawerOpen() && !padPanelOpen()
                && (vpadEditor == null || !vpadEditor.open());
    }
    /** What is held now stays off the Switch until it is let go (the press that
     *  closed Settings must not reach the game). */
    private void suppressHeld() {
        if (padInput == null) return;
        padSuppress = padInput.state(padMapping, DEADZONES[padDeadzone]).buttons;
    }
    private void exitApp() {
        stopEngine();
        finishAndRemoveTask();
    }
    private void updateFps() {
        if (fpsView == null) return;
        boolean show = fpsOnly && !stats && engine != null && showingVideo;
        fpsView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /* ---- v0.7.14: updates (Updater.java) ---- */
    private void updateClicked() {
        switch (updater.state) {
            case FOUND: updater.install(); break;
            case FAILED: if (!updater.url.isEmpty()) updater.install(); else updater.check(prefs.getBoolean("update_betas", false)); break;
            case CHECKING: case GETTING: case INSTALLING: break;
            default: updater.check(prefs.getBoolean("update_betas", false)); break;
        }
    }
    private void updateUpdater() {
        Updater.State st = updater.state;
        if (updateStatus != null) updateStatus.setText(updater.status(Updater.version(this)));
        if (updateAction != null) {
            updateAction.setText(st == Updater.State.FOUND ? "Update" : st == Updater.State.FAILED && !updater.url.isEmpty() ? "Try again" : "Check");
            updateAction.setEnabled(st != Updater.State.CHECKING && st != Updater.State.GETTING && st != Updater.State.INSTALLING);
            Ui.stylePill(updateAction, st == Updater.State.FOUND);
        }
        if (updateButton != null) {
            boolean show = st == Updater.State.FOUND || st == Updater.State.GETTING || st == Updater.State.INSTALLING
                    || (st == Updater.State.FAILED && !updater.url.isEmpty());
            updateButton.setVisibility(show ? View.VISIBLE : View.GONE);
            updateButton.setText(st == Updater.State.FOUND ? "Update to " + updater.tag
                    : st == Updater.State.GETTING ? updater.status("")
                    : st == Updater.State.INSTALLING ? "Confirm the install on screen"
                    : "Update failed - try again");
            updateButton.setEnabled(st == Updater.State.FOUND || st == Updater.State.FAILED);
        }
    }

    private void openDrawer() {
        if (drawer.getVisibility() == View.VISIBLE) return;
        // show whatever the home card's field (or a previous session) saved
        ipDrawer.setText(prefs.getString("ip", ""));
        liftFingers();
        scrim.setVisibility(View.VISIBLE); drawer.setVisibility(View.VISIBLE);
        updateOverlayGear();
        updatePadStatus();
        (usbMode ? drawerUsb : drawerNet).requestFocus();
        updateVirtualPad();
        padChanged();   // the game lets go of the gamepad while Settings is open
    }
    private void closeDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) return;
        if (padPanelOpen()) closePadPanel();
        scrim.setVisibility(View.GONE); drawer.setVisibility(View.GONE);
        suppressHeld(); updateVirtualPad(); padChanged();   // the game gets the gamepad back
        // the drawer's field is the one that may still hold focus; persist it and
        // mirror the value into the home card's field so the two never disagree
        prefs.edit().putString("ip", ipDrawer.getText().toString().trim()).apply();
        ip.setText(prefs.getString("ip", ""));
        InputMethodManager keyboard = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(ipDrawer.getWindowToken(), 0);
        updateHome();
        if (home.getVisibility() == View.VISIBLE) action.requestFocus();
    }
    private boolean drawerOpen() { return drawer.getVisibility() == View.VISIBLE; }

    /* ---- connecting --------------------------------------------------------- */

    private void chooseMode(boolean usb) {
        if (usb == usbMode) return;
        boolean was = requested;
        stopEngine(); usbMode = usb;
        prefs.edit().putBoolean("usb", usb).apply();
        updateMode();
        setMessage("");
        if (was) startEngine();   // keep connecting, now the other way
    }
    private void updateMode() {
        for (Button b : new Button[]{homeUsb, drawerUsb}) Ui.styleSegment(b, usbMode);
        for (Button b : new Button[]{homeNet, drawerNet}) Ui.styleSegment(b, !usbMode);
        ipRow.setVisibility(usbMode ? View.GONE : View.VISIBLE);
        if (homeIpRow != null) homeIpRow.setVisibility(usbMode ? View.GONE : View.VISIBLE);
        ipLabel.setText(usbMode ? "Set Connection to \"USB - PC viewer\" in the Switch Remote Frame app on the Switch too."
                : "Set Connection to Network in the Switch Remote Frame app on the Switch too; it shows the Switch's IP address.");
        updateHome();
    }
    private void connect() {
        String address = ip.getText().toString().trim();
        if (!usbMode && !address.isEmpty() && !SftProtocol.ipv4(address)) {
            setMessage("That is not an IPv4 address (such as 192.168.1.50). Leave it blank to find the Switch.");
            openDrawer(); ipDrawer.requestFocus(); return;
        }
        InputMethodManager keyboard = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(ip.getWindowToken(), 0);
        prefs.edit().putString("ip", address).putBoolean("auto", true).apply();
        requested = true; stopEngine(); startEngine();
    }
    private void disconnect() {
        requested = false; prefs.edit().putBoolean("auto", false).apply();
        stopEngine(); setMessage(""); updateHome();
    }
    private void startEngine() {
        if (!requested || !resumed || !surfaceReady || engine != null) return;
        final int serial = ++focusSerial;
        audioFocus = value -> {
            if (serial != focusSerial) return; // ignore delayed callbacks from a previous connection
            focusGranted = value == AudioManager.AUDIOFOCUS_GAIN;
            if (engine != null) engine.mute(mute || !focusGranted);
        };
        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setOnAudioFocusChangeListener(audioFocus, handler).setWillPauseWhenDucked(true).build();
            focusGranted = audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            focusGranted = audioManager.requestAudioFocus(audioFocus, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        final StreamEngine[] identity = new StreamEngine[1];
        StreamEngine.Listener listener = new StreamEngine.Listener() {
            @Override public void status(String value) { handler.post(() -> { if (engine == identity[0]) setMessage(value); }); }
            @Override public void consoleStatus(int reason, String head, String text) {
                handler.post(() -> {
                    if (engine != identity[0]) return;
                    consoleReason = reason; consoleHead = head; consoleDetail = text; consoleAt = System.nanoTime();
                    updateHome();
                });
            }
            @Override public void rumble(Rumble.Motors motors) { if (engine == identity[0] || engine == null) rumbler.set(motors); }
            @Override public void connectedAddress(String value) {
                handler.post(() -> { if (engine == identity[0]) prefs.edit().putString("last_ip", value).apply(); });
            }
            @Override public void usbPermission(UsbDevice device) {
                handler.post(() -> {
                    if (engine != identity[0] || !resumed) return;
                    PendingIntent result = PendingIntent.getBroadcast(MainActivity.this, 0,
                            new Intent(usbAction()).setPackage(getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    try { ((UsbManager)getSystemService(USB_SERVICE)).requestPermission(device, result); }
                    catch (RuntimeException e) { setMessage("Could not ask for USB access: " + e.getMessage()); }
                });
            }
        };
        engine = new StreamEngine(this, picture.getHolder().getSurface(), usbMode, prefs.getString("ip", ""),
                prefs.getString("last_ip", ""), stable, mute || !focusGranted, smoothVideo, refreshRate(), listener);
        identity[0] = engine; vpadUsed = false; updateWantRumble(); sampledNs = System.nanoTime();
        sampledBytes = sampledFrames = sampledReceived = sampledDecoded = sampledAudio = 0;
        consoleReason = 0; consoleAt = 0;
        setMessage(""); engine.start(); updateHome();
        padChanged();   // 0.7.16: the controller for the new connection
    }
    /** The screen's refresh rate now, for FramePacer (it also measures it). */
    @SuppressWarnings("deprecation")
    private float refreshRate() {
        try {
            android.view.Display d = Build.VERSION.SDK_INT >= 30 ? getDisplay() : getWindowManager().getDefaultDisplay();
            return d != null ? d.getRefreshRate() : 60f;
        } catch (RuntimeException e) { return 60f; }
    }
    private void stopEngine() {
        StreamEngine old = engine; engine = null;
        focusSerial++;
        if (old != null) old.stop();
        rumbler.stop(); vpadUsed = false;
        hud.setVisibility(View.GONE);
        if (fpsView != null) fpsView.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) audioManager.abandonAudioFocusRequest(audioFocusRequest);
        else if (audioFocus != null) audioManager.abandonAudioFocus(audioFocus);
        audioFocusRequest = null; audioFocus = null; focusGranted = false;
        showVideo(false);
    }

    /* ---- what the home screen says ------------------------------------------ */

    private void setMessage(String value) { message = value == null ? "" : value; updateHome(); }
    private void updateHome() {
        if (title == null) return;
        boolean fresh = consoleAt != 0 && System.nanoTime() - consoleAt < 4_000_000_000L && consoleReason != 0;
        String t, d, n = "";
        int color = Ui.ACC;
        if (!requested) {
            t = "Ready to connect";
            d = usbMode ? "Connect the Switch to this device with a USB-C data cable, in handheld mode."
                        : "The Switch and this device on the same network. Playing docked works.";
            String addr = ip.getText().toString().trim();
            n = usbMode ? "Playing docked? Choose Network."
                        : addr.isEmpty() ? "It finds the Switch by itself, or set its address in Settings." : "Connecting to " + addr + " (Settings to change it)";
        } else if (fresh) {
            t = consoleHead.isEmpty() ? "Connected" : consoleHead;
            d = consoleDetail;
            color = Ui.GOOD;
        } else if (engine != null && engine.stats.transport.length() > 0 && message.startsWith("Connected")) {
            t = "Connected"; d = "Open a game on the Switch and the picture appears here."; color = Ui.GOOD;
        } else {
            t = usbMode ? "Waiting for the Switch" : "Looking for the Switch";
            d = message.isEmpty() ? (usbMode ? "Connect it with a USB-C data cable, in handheld mode."
                                             : "Searching the network. On the Switch, set Connection to Network in the Switch Remote Frame app (then restart the Switch), or enter its IP address.") : message;
            if (message.endsWith("Reconnecting…")) color = Ui.WARN;
        }
        title.setText(t); detail.setText(d); note.setText(n);
        note.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
        ring.setColor(color);
        action.setText(requested ? "Disconnect" : "Connect");
        Ui.stylePill(action, !requested);
        if (drawerDisconnect != null) drawerDisconnect.setVisibility(requested ? View.VISIBLE : View.GONE);
    }
    private void showVideo(boolean value) {
        if (value == showingVideo) return;
        showingVideo = value;
        home.setVisibility(value ? View.GONE : View.VISIBLE);
        if (!value) liftFingers();
        if (value) suppressHeld();
        updateVirtualPad();
        padChanged();
        updateFps();
        updateOverlayGear();
        if (!value && !drawerOpen()) action.requestFocus();
    }

    /* ---- v1.0.1: the floating Settings gear over the game ------------------ */
    /** Any touch, mouse movement or key press means the user is there. */
    private void pokeOverlay() {
        showOverlayGear();
    }
    private boolean overlayBlocked() {
        return engine == null || !showingVideo || drawerOpen() || padPanelOpen()
                || (vpadEditor != null && vpadEditor.open());
    }
    private void showOverlayGear() {
        if (!overlayButton || overlayOpacity <= 0 || overlayBlocked()) return;
        handler.removeCallbacks(overlayHide);
        overlayGear.animate().cancel();
        overlayGear.setAlpha(overlayOpacity / 100f);
        overlayGear.setVisibility(View.VISIBLE);
        handler.postDelayed(overlayHide, 2000);
    }
    private void hideOverlayGear() {
        if (overlayGear.getVisibility() != View.VISIBLE) return;
        overlayGear.animate().cancel();
        overlayGear.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            overlayGear.setVisibility(View.GONE);
            overlayGear.setAlpha(overlayOpacity / 100f);
        }).start();
    }
    /** Drop the gear at once when its context goes away (drawer, pad page, no video). */
    private void updateOverlayGear() {
        if (!overlayButton || overlayOpacity <= 0 || overlayBlocked()) {
            handler.removeCallbacks(overlayHide);
            overlayGear.animate().cancel();
            overlayGear.setVisibility(View.GONE);
            overlayGear.setAlpha(overlayOpacity / 100f);
        }
    }
    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        pokeOverlay();
        return super.dispatchTouchEvent(ev);
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) pokeOverlay();
        return super.onGenericMotionEvent(event);
    }

    /* ---- remote touch (0.7.16) ---------------------------------------------- */

    /** Every finger up on the Switch (the drawer opens, the picture goes, ...). */
    private void liftFingers() {
        fingers.clear();
        if (touching && engine != null) engine.touch(Touch.Frame.NONE);
        touching = false;
    }
    /** The fingers on the picture -> the Switch's 1280x720 touch screen. The
     *  picture keeps the stream's 16:9 (fitPicture), so its corners are the
     *  screen's. False hands the event on: a tap then opens Settings. */
    @SuppressLint("ClickableViewAccessibility")
    private boolean pictureTouch(View v, MotionEvent e) {
        if (!touchControl || engine == null || (!showingVideo && !touching)) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            fingers.clear();
            touching = false;
            engine.touch(Touch.Frame.NONE);
            return true;
        }
        int lifted = action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
        int[] xs = new int[Touch.MAX_FINGERS], ys = new int[Touch.MAX_FINGERS], ids = new int[Touch.MAX_FINGERS];
        int n = 0;
        for (int i = 0; i < e.getPointerCount() && n < Touch.MAX_FINGERS; i++) {
            if (i == lifted) continue;
            int finger = fingers.finger(e.getPointerId(i));
            if (finger < 0) continue;
            xs[n] = Touch.toScreen(e.getX(i), v.getWidth(), Touch.WIDTH);
            ys[n] = Touch.toScreen(e.getY(i), v.getHeight(), Touch.HEIGHT);
            ids[n] = finger;
            n++;
        }
        if (lifted >= 0) fingers.release(e.getPointerId(lifted));
        Touch.Frame f = new Touch.Frame(n);
        System.arraycopy(xs, 0, f.x, 0, n); System.arraycopy(ys, 0, f.y, 0, n); System.arraycopy(ids, 0, f.id, 0, n);
        touching = n > 0;
        engine.touch(f);
        return true;
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (engine != null) {
                StreamStats s = engine.stats;
                long now = System.nanoTime();
                boolean active = s.lastRenderedNs > 0 && now - s.lastRenderedNs < 1_500_000_000L;
                showVideo(active);
                fitPicture();
                double seconds = (now - sampledNs) / 1_000_000_000.0;
                if (seconds >= 1) {
                    double fps = (s.rendered - sampledFrames) / seconds;
                    double mbps = (s.bytes - sampledBytes) * 8 / seconds / 1_000_000;
                    double rx = (s.received - sampledReceived) / seconds;
                    double decoded = (s.decoded - sampledDecoded) / seconds;
                    double audioKb = (s.audioBytes - sampledAudio) / seconds / 1000;
                    hud.setText(String.format(Locale.ROOT,
                            "%s · %d×%d · %.1f Mbps\nVideo RX %.1f · decoded %.1f · shown %.1f FPS\nQueue + decode %.1f ms · console age %.1f ms\nPacing %s · worst gap %.0f ms\nAudio RX %.0f kB/s · played %.0f ms · queued %.0f ms\nAudio %s · TV buffer %.0f ms · %s\nErrors %d · dropped %d/%d · adjusted PTS %d\n%s\nTouch %s · %d sent · %d failed\nGamepad %s · %d sent · %d failed\nOn-screen gamepad %s · rumble %d received",
                            s.transport, s.width, s.height, mbps, rx, decoded, fps, s.decoderMs, s.consoleMs, s.pacing, s.worstGapMs,
                            audioKb, s.audioPlayedFrames / 48.0, s.audioQueuedMs,
                            s.audioMuted ? "muted" : "active", s.audioBufferMs,
                            focusGranted ? "focus granted" : "no audio focus", s.decoderErrors, s.skipped,
                            s.videoDropped, s.unmatchedPts, s.decoder + " · " + s.audio,
                            touchControl ? (touching ? "down" : "on") : "off", s.touchSent, s.touchFailed,
                            padHud(), s.padSent, s.padFailed,
                            vpadMode == VPAD_OFF ? "off" : virtualPad.shown() ? (vpadUsed ? "playing" : "shown") : virtualPad.layerShown() ? "hidden (button)" : "hidden", s.rumblePackets));
                    fpsView.setText(String.valueOf(Math.round(fps)));
                    s.worstGapMs = 0;
                    sampledNs = now; sampledFrames = s.rendered; sampledBytes = s.bytes;
                    sampledReceived = s.received; sampledDecoded = s.decoded; sampledAudio = s.audioBytes;
                }
                hud.setVisibility(stats ? View.VISIBLE : View.GONE);
                updateFps();
                if (!active) updateHome();   // a console status ages out after 4 s
            }
            gamepadNear = padInput != null && padInput.anyConnected();
            updateVirtualPad();
            updateOverlayGear();
            handler.postDelayed(this, 250);
        }
    };
    private String padHud() {
        if (!gamepadControl) return "off";
        java.util.List<String> names = padInput.names();
        if (names.isEmpty()) return "on · none connected";
        return (padForwarding() ? "playing · " : "on · ") + android.text.TextUtils.join(", ", names);
    }
    private void fitPicture() {
        if (root.getWidth() == 0 || root.getHeight() == 0) return;
        int w = engine == null ? 16 : Math.max(16, engine.stats.width);
        int h = engine == null ? 9 : Math.max(9, engine.stats.height);
        double ratio = (double)w/h;
        int fitW = root.getWidth(), fitH = (int)(fitW / ratio);
        if (fitH > root.getHeight()) { fitH = root.getHeight(); fitW = (int)(fitH * ratio); }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)picture.getLayoutParams();
        if (lp.width != fitW || lp.height != fitH) { lp.width = fitW; lp.height = fitH; picture.setLayoutParams(lp); }
    }

    /* ---- keys and lifecycle ------------------------------------------------- */

    /** 0.7.16: a gamepad's keys and sticks are the Switch's while the game shows
     *  (or the remap's while it listens); otherwise Android's, so the gamepad
     *  moves around Settings (A selects, B goes back). */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) pokeOverlay();   // v1.0.1: a key also wakes the gear
        if (padInput != null && PadInput.isGamepad(e.getDevice())) {
            boolean capturing = padInput.capturing();
            boolean padKey = padInput.key(e, padMapping);
            if (capturing) return true;
            if (padKey && padForwarding()) return true;
        }
        return super.dispatchKeyEvent(e);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (padInput != null) {
            boolean capturing = padInput.capturing();
            if (padInput.motion(e) && (capturing || padForwarding())) return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }
    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (event.getRepeatCount() == 0 && !drawerOpen()) {
            boolean open = code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS
                    || (showingVideo && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                                         || code == KeyEvent.KEYCODE_BUTTON_A));
            if (open) { openDrawer(); return true; }
        }
        return super.onKeyDown(code, event);
    }
    /** 0.7.16: Back opens Settings (over the game or the home screen) and closes
     *  it again; Exit in Settings' top bar leaves the app.
     *  v1.0.3: in the layout editor Back shows or hides its panel instead of
     *  leaving - the editor is a mode you came here to be in, and Cancel and
     *  Done are how you leave it. */
    @Override public void onBackPressed() {
        if (vpadEditor.open()) vpadEditor.togglePanel();
        else if (remapBit >= 0 || padInput.capturing()) cancelRemap();
        else if (padPanelOpen()) closePadPanel();
        else if (drawerOpen()) closeDrawer();
        else openDrawer();
    }
    @Override protected void onResume() { super.onResume(); resumed = true; immersive(); padInput.start(); startEngine(); updatePadStatus(); }
    @Override protected void onPause() { resumed = false; cancelRemap(); stopEngine(); rumbler.stop(); virtualPad.release(); padInput.stop(); padInput.clear(); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); unregisterReceiver(permissionReceiver); updater.release(); rumbler.release(); super.onDestroy(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; startEngine(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; stopEngine(); }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
        else if (padInput != null) padInput.clear();   // the key ups go elsewhere now: nothing stays held
    }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int lastImeInset;                       /* v1.1.4: bottom padding the keyboard currently forces */
    private View lastImeFocus;                      /* the field that owned it, to scroll a new focus once */
    private final Rect visibleFrame = new Rect();

    /**
     * v1.1.4: keep a focused text field above the on-screen keyboard. The
     * activity is immersive-fullscreen, where the platform does not resize
     * for the IME, so its height is read from the window's visible frame via
     * ImeInsets and applied as bottom padding on the root. The home and
     * drawer ScrollViews then shrink, and the focused field scrolls into
     * view. If the window ever does resize itself the visible frame fills
     * the root, the inset is 0, and nothing is double-applied.
     */
    private void installImeInsets() {
        final int minKeyboardPx = dp(ImeInsets.MIN_KEYBOARD_DP);
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            final View focused = root.findFocus();
            final boolean typing = focused instanceof EditText;
            if (typing) root.getWindowVisibleDisplayFrame(visibleFrame);
            final int ime = ImeInsets.insetPx(root.getHeight(),
                    typing ? visibleFrame.bottom : root.getHeight(), typing, minKeyboardPx);
            final boolean insetChanged = ime != lastImeInset;
            if (insetChanged) {
                lastImeInset = ime;
                root.setPadding(root.getPaddingLeft(), root.getPaddingTop(),
                        root.getPaddingRight(), ime);
            }
            if (typing && ime > 0 && (insetChanged || focused != lastImeFocus)) {
                final View f = focused;
                f.post(() -> f.requestRectangleOnScreen(new Rect(0, 0, f.getWidth(), f.getHeight()), true));
            }
            lastImeFocus = focused;
        });
    }
    private String usbAction() { return getPackageName() + ".USB_PERMISSION"; }
}
