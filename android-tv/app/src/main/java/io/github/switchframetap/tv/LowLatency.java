/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.media.MediaFormat;
import android.os.Build;
import java.util.Locale;

/** 0.7.19-touch-4: the decoders' own low-latency switches, as Moonlight sets them
 *  (moonlight-android, MediaCodecHelper.setDecoderLowLatencyOptions). Without them
 *  many hardware decoders that do not advertise Android 11's FEATURE_LowLatency
 *  hold decoded frames back for smooth film playback - delay a game stream does
 *  not want. Tried first (VideoDecoder level 2); a decoder that refuses them, or
 *  fails or slows down with them, goes back to the standard keys (level 1). */
final class LowLatency {
    private LowLatency() { }

    static boolean qualcomm(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("omx.qcom") || n.startsWith("c2.qti");
    }

    /** Worth a try beyond the standard keys: a hardware decoder without the official
     *  FEATURE_LowLatency (Moonlight uses that alone where it exists). */
    static boolean worthVendorLevel(boolean officialLowLatency, boolean software) {
        return !officialLowLatency && !software;
    }

    /** Snapdragon 765/765G/768G (Adreno 620): Moonlight measured crashes with the
     *  maximum operating rate there (Mi 10 Lite 5G, Redmi K30i 5G). */
    private static boolean adreno620() {
        String hw = Build.HARDWARE == null ? "" : Build.HARDWARE.toLowerCase(Locale.ROOT);
        String board = Build.BOARD == null ? "" : Build.BOARD.toLowerCase(Locale.ROOT);
        return hw.equals("lito") || board.equals("lito");
    }

    /** The options, most to least risky, as one format: a decoder that rejects them at
     *  configure() is set up again with the standard keys. */
    static void vendorOptions(MediaFormat format, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        boolean qcom = qualcomm(name);
        // Android 11's official key, best effort on a decoder that does not advertise it
        if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        // MediaTek's ACodec (and Amazon's Amlogic Fire TVs): OMX...LowLatencyDecode.
        // Not on Xiaomi with Android 6, where Moonlight saw it break the decoder.
        if (!"xiaomi".equalsIgnoreCase(Build.MANUFACTURER) || Build.VERSION.SDK_INT > 23)
            format.setInteger("vdec-lowlatency", 1);
        // Qualcomm: the decoder at its highest clock. Not together with priority 0,
        // which makes it fail when it cannot meet the rate (Moonlight).
        if (qcom && !adreno620())
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
        else
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        // "vendor.<extension>.<parameter>" keys reach the codec from Android 8
        if (Build.VERSION.SDK_INT >= 26) {
            if (qcom) {
                // frames out in decode order (this stream has no B frames) and the low-latency path
                format.setInteger("vendor.qti-ext-dec-picture-order.enable", 1);
                format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
            } else if (n.startsWith("omx.hisi") || n.startsWith("c2.hisi")) {
                format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1);
                format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1);
            } else if (n.startsWith("omx.exynos") || n.startsWith("c2.exynos")) {
                format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1);
            } else if (n.startsWith("omx.amlogic") || n.startsWith("c2.amlogic")) {
                format.setInteger("vendor.low-latency.enable", 1);
            }
        }
    }
}
