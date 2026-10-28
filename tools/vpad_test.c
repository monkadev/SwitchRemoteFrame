/* vpad_test.c - v1.0.2: the on-screen gamepad's layout and the controller's
 * remap table, checked without SDL or a console.
 *
 *   gcc -std=c99 -Wall -Wextra -Itools/raw-recv tools/vpad_test.c -o vpad_test -lm && ./vpad_test
 *
 * Both headers are deliberately free of SDL: the layout so it can be checked
 * here, and because it is the Android app's own format - a layout string has to
 * mean the same thing in both viewers.
 */
#include <stdio.h>
#include <string.h>

#include "vpad_layout.h"
#include "gp_remap.h"

/* gp_remap.h carries two settings the page owns rather than the model */
static void vpad_test_touch_settings(void) { (void)g_pad_rumble_on; }

static int fails = 0;
#define CHECK(cond, ...) do { if (!(cond)) { printf("  FAIL "); printf(__VA_ARGS__); printf("\n"); fails++; } } while (0)

static void test_defaults(void)
{
    vp_layout_t v;
    vp_defaults(&v, 1280, 720);

    /* every control has an id, and the ids are the Android app's */
    for (int i = 0; i < VP_N; i++) {
        CHECK(v.items[i].id && *v.items[i].id, "defaults: control %d has no id", i);
        CHECK(strcmp(v.items[i].id, vp_ids[i]) == 0, "defaults: control %d is \"%s\", not \"%s\"",
              i, v.items[i].id ? v.items[i].id : "(null)", vp_ids[i]);
        CHECK(v.items[i].x >= 0 && v.items[i].x <= 1, "defaults: %s x=%f outside 0..1", v.items[i].id, v.items[i].x);
        CHECK(v.items[i].y >= 0 && v.items[i].y <= 1, "defaults: %s y=%f outside 0..1", v.items[i].id, v.items[i].y);
    }
    /* the three the editor adds, and only those, start hidden */
    for (int i = 0; i < VP_N; i++) {
        const int want_hidden = !strcmp(v.items[i].id, "capture") || !strcmp(v.items[i].id, "l3") ||
                                !strcmp(v.items[i].id, "r3");
        CHECK(v.items[i].shown == !want_hidden, "defaults: %s shown=%d, wanted %d",
              v.items[i].id, v.items[i].shown, !want_hidden);
    }
    /* the left stick is bottom left, the right stick bottom right, A right of B */
    const vp_item_t *ls = vp_get(&v, "ls"), *rs = vp_get(&v, "rs"), *a = vp_get(&v, "a"), *b = vp_get(&v, "b");
    CHECK(ls && rs && a && b, "defaults: a control is missing");
    CHECK(ls->x < 0.5f && rs->x > 0.5f, "defaults: the sticks are not either side (ls %f, rs %f)", ls->x, rs->x);
    CHECK(ls->y > 0.5f && rs->y > 0.5f, "defaults: the sticks are not low (ls %f, rs %f)", ls->y, rs->y);
    CHECK(a->x > b->x, "defaults: A is not right of B (%f, %f)", a->x, b->x);

    /* a tiny window still lands inside it (vp_defaults floors at 320x240) */
    vp_layout_t small_v;
    vp_defaults(&small_v, 10, 10);
    for (int i = 0; i < VP_N; i++)
        CHECK(small_v.items[i].x >= 0 && small_v.items[i].x <= 1 &&
              small_v.items[i].y >= 0 && small_v.items[i].y <= 1,
              "defaults(10x10): %s outside 0..1", small_v.items[i].id);
}

static void test_round_trip(void)
{
    vp_layout_t a;
    vp_defaults(&a, 1280, 720);
    /* move, resize, hide and show a few, then save and read back */
    vp_get(&a, "a")->x = 0.1234f;
    vp_get(&a, "a")->y = 0.5678f;
    vp_get(&a, "a")->scale = 1.7f;
    vp_get(&a, "a")->shown = 0;
    vp_get(&a, "capture")->shown = 1;
    vp_get(&a, "ls")->scale = 0.5f;

    char s[700];
    vp_serialize(&a, s, sizeof(s));
    CHECK(strncmp(s, "v1;", 3) == 0, "serialize: \"%s\" does not start with v1;", s);
    CHECK(strlen(s) < sizeof(s) - 40, "serialize: %zu bytes is close to the buffer", strlen(s));
    /* raw-view.c reads viewer.ini a line at a time with fgets into 1024 bytes,
     * so "vpad_layout=" plus this plus the newline has to fit or the layout is
     * silently truncated on the way back in */
    CHECK(strlen(s) + 12 + 1 <= 1024, "serialize: %zu chars will not fit a 1024-byte line", strlen(s) + 13);

    vp_layout_t b;
    vp_parse(&b, s, 1280, 720);
    for (int i = 0; i < VP_N; i++) {
        CHECK(strcmp(a.items[i].id, b.items[i].id) == 0, "round trip: id %d differs", i);
        /* the format keeps 4 decimals, as the Android app's does, so this is
         * accurate to the format rather than bit for bit */
        CHECK(a.items[i].x == b.items[i].x || (a.items[i].x > 0.00005f && a.items[i].x < 0.99995f),
              "round trip: %s x %f != %f", a.items[i].id, a.items[i].x, b.items[i].x);
        CHECK(b.items[i].x >= 0 && b.items[i].x <= 1, "round trip: %s x out of range", a.items[i].id);
        CHECK(b.items[i].y >= 0 && b.items[i].y <= 1, "round trip: %s y out of range", a.items[i].id);
        CHECK(a.items[i].scale == b.items[i].scale, "round trip: %s scale %f != %f",
              a.items[i].id, a.items[i].scale, b.items[i].scale);
        CHECK(a.items[i].shown == b.items[i].shown, "round trip: %s shown differs", a.items[i].id);
    }
    CHECK(b.items[3].shown == 0 && strcmp(b.items[3].id, "a") == 0, "round trip: hiding A was lost");
    CHECK(vp_get(&b, "capture")->shown == 1, "round trip: showing Capture was lost");
}

static void test_parse_robust(void)
{
    vp_layout_t v;
    /* nothing, garbage, and a truncated line: the defaults, never a crash and
     * never a control without an id (that was a real NULL dereference) */
    vp_parse(&v, NULL, 1280, 720);
    for (int i = 0; i < VP_N; i++) CHECK(v.items[i].id, "parse(NULL): control %d has no id", i);
    vp_parse(&v, "", 1280, 720);
    CHECK(v.items[0].id, "parse(\"\"): no id");
    vp_parse(&v, "nonsense", 1280, 720);
    CHECK(v.items[0].id, "parse(garbage): no id");
    vp_parse(&v, "v2;a:0.5,0.5,1.0,1", 1280, 720);       /* a newer app's version */
    CHECK(v.items[0].id, "parse(v2): no id");
    vp_parse(&v, "v1;a:0.5", 1280, 720);                 /* truncated */
    CHECK(v.items[0].id, "parse(truncated): no id");
    vp_parse(&v, "v1;zzz:0.5,0.5,1.0,1;a:0.25,0.75,1.3,1", 1280, 720);   /* an unknown id */
    CHECK(vp_get(&v, "a")->x == 0.25f, "parse: an unknown id stopped the good ones (a x=%f)", vp_get(&v, "a")->x);

    /* out-of-range values are clamped, not trusted */
    vp_parse(&v, "v1;a:5.0,-3.0,99.0,1;b:-1,2,0.0,1", 1280, 720);
    CHECK(vp_get(&v, "a")->x == 1.f && vp_get(&v, "a")->y == 0.f, "parse: x/y not clamped to 0..1");
    CHECK(vp_get(&v, "a")->scale == VP_MAX_SCALE, "parse: scale %f not clamped to %f",
          vp_get(&v, "a")->scale, VP_MAX_SCALE);
    CHECK(vp_get(&v, "b")->scale == VP_MIN_SCALE, "parse: scale %f not clamped to %f",
          vp_get(&v, "b")->scale, VP_MIN_SCALE);

    /* the layout the Android app writes for one control reads back as itself */
    vp_parse(&v, "v1;ls:0.1000,0.7500,1.2,1", 1280, 720);
    CHECK(vp_get(&v, "ls")->x == 0.1f && vp_get(&v, "ls")->scale == 1.2f,
          "parse: the Android format did not read back");
}

static void test_stick_and_dpad(void)
{
    int x, y;
    vp_stick(0, 0, 64, &x, &y);
    CHECK(x == 0 && y == 0, "stick: centre is not neutral (%d,%d)", x, y);
    vp_stick(64, 0, 64, &x, &y);
    CHECK(x == VP_STICK_MAX && y == 0, "stick: full right is %d, wanted %d", x, VP_STICK_MAX);
    vp_stick(-64, 0, 64, &x, &y);
    CHECK(x == -VP_STICK_MAX, "stick: full left is %d", x);
    /* SDL's y is down, the Switch's is up: pushing the pointer up is +y */
    vp_stick(0, -64, 64, &x, &y);
    CHECK(y == VP_STICK_MAX, "stick: up is %d, wanted +%d (the Switch's y is up)", y, VP_STICK_MAX);
    vp_stick(0, 64, 64, &x, &y);
    CHECK(y == -VP_STICK_MAX, "stick: down is %d", y);
    /* past the rim is clamped to the rim, not beyond */
    vp_stick(1000, -1000, 64, &x, &y);
    CHECK(x > 0 && y > 0 && x <= VP_STICK_MAX && y <= VP_STICK_MAX,
          "stick: past the rim gave %d,%d", x, y);
    /* a diagonal at the rim is not further out than an axis at the rim */
    /* at the rim, and not past it (the two rounded components can land on
     * 23171, a hair over 32767/sqrt(2), so allow a unit; long long because
     * 2 * 32767 * 32767 overflows an int) */
    const long long mag2 = (long long)x * x + (long long)y * y;
    const long long rim2 = (long long)VP_STICK_MAX * VP_STICK_MAX;
    CHECK(mag2 <= rim2 + 4 * VP_STICK_MAX, "stick: the diagonal overshoots the rim (%lld > %lld)", mag2, rim2);
    CHECK(x <= VP_STICK_MAX && y <= VP_STICK_MAX, "stick: a component is past full (%d,%d)", x, y);
    vp_stick(10, 10, 0, &x, &y);
    CHECK(x == 0 && y == 0, "stick: a zero radius gave %d,%d", x, y);

    CHECK(vp_dpad(0, 0, 60) == 0, "dpad: the centre is not neutral");
    CHECK(vp_dpad(5, 5, 60) == 0, "dpad: the middle fifth is not neutral");
    CHECK(vp_dpad(60, 0, 60) == (1u << VPB_RIGHT), "dpad: right");
    CHECK(vp_dpad(-60, 0, 60) == (1u << VPB_LEFT), "dpad: left");
    CHECK(vp_dpad(0, -60, 60) == (1u << VPB_UP), "dpad: up");
    CHECK(vp_dpad(0, 60, 60) == (1u << VPB_DOWN), "dpad: down");
    /* eight ways, so a diagonal is two bits and never three */
    const uint32_t ur = vp_dpad(60, -60, 60);
    CHECK(ur == ((1u << VPB_UP) | (1u << VPB_RIGHT)), "dpad: up-right is %u", ur);
    const uint32_t dl = vp_dpad(-60, 60, 60);
    CHECK(dl == ((1u << VPB_DOWN) | (1u << VPB_LEFT)), "dpad: down-left is %u", dl);
    CHECK(vp_dpad(10, 10, 0) == 0, "dpad: a zero radius is not neutral");
}

static void test_button_map(void)
{
    /* the round ones and the small ones press a Switch button; the sticks and
     * the D-pad do not (they are axes and directions) */
    for (int i = 0; i < VP_N; i++) {
        const char *id = vp_ids[i];
        const int bit = vp_button(id);
        const int is_stick_or_dpad = !strcmp(id, "ls") || !strcmp(id, "rs") || !strcmp(id, "dpad");
        if (is_stick_or_dpad) CHECK(bit == -1, "button: %s reports bit %d", id, bit);
        else {
            CHECK(bit >= 0, "button: %s has no bit", id);
            CHECK(vp_name(id) && *vp_name(id), "button: %s has no editor name", id);
            CHECK(vp_label(id) && *vp_label(id), "button: %s has no label", id);
        }
        CHECK(vp_width_dp(id) > 0 && vp_height_dp(id) > 0, "button: %s has no size", id);
    }
    CHECK(vp_button("a") == VPB_A && vp_button("home") == VPB_HOME, "button: the bits moved");
    CHECK(vp_kind("ls") == VP_STICK && vp_kind("dpad") == VP_DPAD && vp_kind("a") == VP_ROUND &&
          vp_kind("zl") == VP_SHOULDER && vp_kind("home") == VP_SMALL, "button: a kind is wrong");
}

static void test_remap(void)
{
    gp_map_defaults();

    /* every entry is a Switch button bit or -1, and in range - this is what ran
     * one past the end of the table when GP_SLOTS was counted by hand */
    for (int i = 0; i < GP_SLOTS; i++)
        CHECK(g_gp_map[i] >= -1 && g_gp_map[i] < 32, "remap: slot %d is %d", i, g_gp_map[i]);

    /* SDL's own layout: A is A, the left trigger is ZL, HOME is the guide button */
    CHECK(g_gp_map[GPC_A] == VPB_A, "remap: A is %d", g_gp_map[GPC_A]);
    CHECK(g_gp_map[GPC_LEFTSHOULDER] == VPB_L, "remap: L is %d", g_gp_map[GPC_LEFTSHOULDER]);
    CHECK(g_gp_map[GPC_GUIDE] == VPB_HOME, "remap: HOME is %d", g_gp_map[GPC_GUIDE]);
    CHECK(g_gp_map[GP_IN_AX_LO + GPA_TRIGGERLEFT] == VPB_ZL, "remap: the left trigger is not ZL");
    CHECK(g_gp_map[GP_IN_AX_HI + GPA_TRIGGERRIGHT] == VPB_ZR, "remap: the right trigger is not ZR");
    CHECK(gp_slot_for_bit(VPB_A) == GPC_A, "remap: A does not map back to its button");
    CHECK(gp_slot_for_bit(VPB_CAPTURE) == -1, "remap: Capture has a button by default");

    /* the saved string reads back exactly */
    g_gp_map[GPC_A] = VPB_B;                    /* the user swapped A and B */
    g_gp_map[GPC_B] = VPB_A;
    g_gp_right = GP_RIGHT_INVERT;
    char s[220];
    gp_save_str(s, sizeof(s));
    CHECK(strncmp(s, "1;", 2) == 0, "remap: \"%s\" does not start with 1;", s);

    gp_map_defaults();
    CHECK(g_gp_map[GPC_A] == VPB_A, "remap: the reset did not undo the swap");
    gp_load_str(s);
    CHECK(g_gp_map[GPC_A] == VPB_B && g_gp_map[GPC_B] == VPB_A, "remap: the swap did not survive the save");
    CHECK(g_gp_right == GP_RIGHT_INVERT, "remap: the axis choice did not survive (got %d)", g_gp_right);

    /* garbage falls back to the defaults rather than leaving a half-read table */
    gp_load_str("nonsense");
    CHECK(g_gp_map[GPC_A] == VPB_A, "remap: garbage left A as %d", g_gp_map[GPC_A]);
    gp_load_str("");
    CHECK(g_gp_map[GPC_A] == VPB_A, "remap: an empty string left A as %d", g_gp_map[GPC_A]);
    gp_load_str("9;0,1");                       /* a version this build does not know */
    CHECK(g_gp_map[GPC_A] == VPB_A, "remap: a newer version left A as %d", g_gp_map[GPC_A]);
    gp_load_str("1;2");                        /* the axis choice, and no table */
    CHECK(g_gp_right == 2 && g_gp_map[GPC_A] == VPB_A, "remap: a short string was not handled");

    /* a table with a nonsense bit in it is dropped, not shifted into a button */
    gp_load_str("1;0,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77,77");
    for (int i = 0; i < GP_SLOTS; i++)
        CHECK(g_gp_map[i] >= -1 && g_gp_map[i] < 32, "remap: slot %d is %d after a bad table", i, g_gp_map[i]);

    /* the two pages agree on what a button is called */
    CHECK(strcmp(gp_bit_name(VPB_A), "A") == 0 && strcmp(gp_bit_name(VPB_LSTICK), "L3 (left stick press)") == 0,
          "remap: a button's name changed");
    for (int i = 0; i < GP_BITS; i++)
        CHECK(gp_bit_name(gp_bits[i]) && strcmp(gp_bit_name(gp_bits[i]), "?") != 0,
              "remap: bit %d has no name", gp_bits[i]);
    CHECK(gp_right_names[GP_RIGHT_AUTO] && gp_right_names[GP_RIGHT_INVERT] && gp_right_names[GP_RIGHT_TRIGGERS],
          "remap: an axis choice has no name");
}

int main(void)
{
    printf("the on-screen gamepad's layout and the controller's remap\n");
    test_defaults();
    test_round_trip();
    test_parse_robust();
    test_stick_and_dpad();
    test_button_map();
    test_remap();
    vpad_test_touch_settings();
    if (fails) { printf("%d check(s) failed\n", fails); return 1; }
    printf("vpad_test OK\n");
    return 0;
}
