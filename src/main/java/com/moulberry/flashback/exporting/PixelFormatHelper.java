package com.moulberry.flashback.exporting;

/**
 * FFmpeg pixel-format selection is delegated to the PojavLauncher FFmpeg CLI.
 * This class remains as a compatibility shim for callers that only need to
 * distinguish formats handled by the Java-side staging layer.
 */
public final class PixelFormatHelper {
    private PixelFormatHelper() {}

    public static String pixelFormatToString(int pixelFormat) {
        return pixelFormat == 0 ? "rgba" : "custom";
    }

    public static boolean isYuvFormat(int pixelFormat) {
        return false;
    }

    public static boolean doesPixelFormatSupportTransparency(int pixelFormat) {
        return pixelFormat == 0;
    }

    public static int getBestPixelFormat(String codecName, int srcPixelFormat, boolean transparent) {
        return srcPixelFormat;
    }
}
