/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

/**
 * v1.1.4: how high the on-screen keyboard covers the window, for a fullscreen
 * activity. MainActivity runs immersive (legacy fullscreen flags), where the
 * platform does not resize the window for the IME - adjustResize is ignored on
 * most Android versions in fullscreen - so the keyboard used to cover the IP
 * field. The window's visible display frame still shrinks by the keyboard's
 * height, which is what {@link #insetPx} turns into a bottom inset.
 *
 * Pure JVM on purpose: the decision is host-tested by ImeInsetTest with no
 * Android dependency, like the other plain-Java helpers (FramePacer, ...).
 */
final class ImeInsets {
    private ImeInsets() { }

    /** A revealed navigation bar in immersive sticky is shorter than any
     *  real keyboard; below this many px the shrink is treated as a system
     *  bar, not the IME. The caller passes it already scaled to pixels. */
    static final int MIN_KEYBOARD_DP = 60;

    /**
     * @param rootHeight      the root view's height in px
     * @param visibleBottom   the visible display frame's bottom (getWindowVisibleDisplayFrame)
     * @param fieldFocused    whether a text field is focused (the only time an IME can be open)
     * @param minKeyboardPx   MIN_KEYBOARD_DP scaled by the display density
     * @return the keyboard's height in px, or 0 when there is none
     */
    static int insetPx(int rootHeight, int visibleBottom, boolean fieldFocused, int minKeyboardPx) {
        if (!fieldFocused) return 0;
        final int hidden = rootHeight - visibleBottom;
        return hidden >= minKeyboardPx ? hidden : 0;
    }
}
