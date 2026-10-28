/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

/**
 * v1.1.4 host test for {@link ImeInsets}: the fullscreen keyboard detection
 * that keeps the IP field above the on-screen keyboard. No Android dependency,
 * run by the imeInsetTest Gradle task like ProtocolTest.
 */
final class ImeInsetTest {
    private static int failures;

    private static void check(boolean cond, String msg) {
        if (cond) {
            System.out.println("  PASS " + msg);
        } else {
            System.out.println("  FAIL " + msg);
            failures++;
        }
    }

    public static void main(String[] args) {
        final int min = 150;   // 60 dp at 2.5 density: anything below is a transient system bar

        check(ImeInsets.insetPx(800, 100, false, min) == 0,
                "no field focused: never report a keyboard, however much is hidden");
        check(ImeInsets.insetPx(800, 800, true, min) == 0,
                "the whole frame is visible: the keyboard is closed");
        check(ImeInsets.insetPx(800, 100, true, min) == 700,
                "typing with 700 px hidden: that is the keyboard height");
        check(ImeInsets.insetPx(800, 760, true, min) == 0,
                "a 40 px shrink is below the threshold: a revealed nav bar, not a keyboard");
        check(ImeInsets.insetPx(800, 650, true, min) == 150,
                "exactly the threshold counts as a keyboard");
        check(ImeInsets.insetPx(800, 820, true, min) == 0,
                "a visible frame past the root (a window that did resize) reports nothing");
        check(ImeInsets.insetPx(0, 0, true, min) == 0,
                "a zero-height root before first layout does not report a keyboard");

        if (failures != 0) {
            System.out.println("ImeInsetTest FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("ImeInsetTest OK");
    }
}
