package com.moulberry.flashback.combo_options;

import com.moulberry.flashback.exporting.PojavFFmpeg;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public enum VideoCodec implements ComboOption {
    H264("H264 (AVC)", Set.of(), false,
        "libx264", "h264_mediacodec", "h264_v4l2m2m", "h264_nvenc", "h264_amf"),
    H265("H265 (HEVC)", Set.of(), false,
        "libx265", "hevc_mediacodec", "hevc_v4l2m2m", "hevc_nvenc", "hevc_amf"),
    AV1("AV1", Set.of(VideoContainer.MP4), false,
        "libaom-av1", "libsvtav1", "av1_mediacodec", "av1_nvenc"),
    VP9("VP9", Set.of(), false,
        "libvpx-vp9", "vp9_mediacodec"),
    PRO_RES("Apple ProRes", Set.of(), false, "prores_ks", "prores"),
    QUICK_TIME("QuickTime", Set.of(), false, "qtrle"),
    WEBP("WebP", Set.of(VideoContainer.WEBP), false, "libwebp"),
    GIF("GIF", Set.of(VideoContainer.GIF), false, "gif"),
    PNG("PNG", Set.of(VideoContainer.PNG_SEQUENCE), true, "png"),
    EXR("EXR", Set.of(VideoContainer.EXR_SEQUENCE), true, "exr");

    private final String text;
    private final Set<VideoContainer> validContainers;
    private final boolean transparency;
    private final String[] candidates;

    VideoCodec(String text, Set<VideoContainer> validContainers, boolean transparency, String... candidates) {
        this.text = text;
        this.validContainers = validContainers;
        this.transparency = transparency;
        this.candidates = candidates;
    }

    @Override
    public String text() {
        return text;
    }

    public int codecId() {
        // Kept only for source compatibility with code outside the exporter.
        return ordinal();
    }

    public Set<VideoContainer> validContainers() {
        return validContainers;
    }

    public boolean supportsTransparency() {
        return transparency || this == VP9 || this == PRO_RES;
    }

    public String[] getEncoders() {
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (PojavFFmpeg.hasEncoder(candidate)) {
                result.add(candidate);
            }
        }
        return result.toArray(new String[0]);
    }

    public static VideoCodec fromEncoder(String encoder) {
        for (VideoCodec codec : values()) {
            for (String candidate : codec.candidates) {
                if (candidate.equals(encoder)) return codec;
            }
        }
        return H264;
    }
}
