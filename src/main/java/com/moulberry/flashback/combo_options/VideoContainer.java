package com.moulberry.flashback.combo_options;

import com.moulberry.flashback.exporting.PojavFFmpeg;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public enum VideoContainer implements ComboOption {
    MP4("MP4", "mp4"),
    MKV("MKV", "mkv"),
    AVI("AVI", "avi"),
    MOV("MOV", "mov"),
    PNG_SEQUENCE("PNG Sequence", "png"),
    EXR_SEQUENCE("EXR Sequence", "exr"),
    WEBP("WebP", "webp"),
    WEBM("WebM", "webm"),
    GIF("GIF", "gif");

    private final String text;
    private final String extension;
    private VideoCodec[] supportedVideoCodecs;
    private VideoCodec[] supportedVideoCodecsWithTransparency;
    private AudioCodec[] supportedAudioCodecs;
    private boolean isImageSequence;

    VideoContainer(String text, String extension) {
        this.text = text;
        this.extension = extension;
    }

    @Override
    public String text() {
        return text;
    }

    public String extension() {
        return extension;
    }

    public static VideoContainer[] findSupportedContainers(boolean transparency) {
        List<VideoContainer> containers = new ArrayList<>();
        for (VideoContainer container : values()) {
            if (container.getSupportedVideoCodecs(transparency).length != 0) {
                containers.add(container);
            }
        }
        return containers.toArray(new VideoContainer[0]);
    }

    public boolean isImageSequence() {
        if (this == PNG_SEQUENCE || this == EXR_SEQUENCE) {
            isImageSequence = true;
        }
        return isImageSequence;
    }

    public VideoCodec[] getSupportedVideoCodecs(boolean transparency) {
        VideoCodec[] codecs = transparency ? supportedVideoCodecsWithTransparency : supportedVideoCodecs;
        if (codecs != null) return codecs;

        List<VideoCodec> supported = new ArrayList<>();
        for (VideoCodec codec : VideoCodec.values()) {
            if (codec.validContainers().size() > 0 && !codec.validContainers().contains(this)) continue;
            if (codec.getEncoders().length == 0) continue;
            if (transparency && !codec.supportsTransparency()) continue;
            if (this == PNG_SEQUENCE || this == EXR_SEQUENCE) isImageSequence = true;
            supported.add(codec);
        }

        codecs = supported.toArray(new VideoCodec[0]);
        if (transparency) supportedVideoCodecsWithTransparency = codecs;
        else supportedVideoCodecs = codecs;
        return codecs;
    }

    public AudioCodec[] getSupportedAudioCodecs() {
        if (supportedAudioCodecs != null) return supportedAudioCodecs;
        if (isImageSequence()) return supportedAudioCodecs = new AudioCodec[0];

        List<AudioCodec> supported = new ArrayList<>();
        for (AudioCodec codec : AudioCodec.values()) {
            if (codec.getEncoders().length != 0) supported.add(codec);
        }
        return supportedAudioCodecs = supported.toArray(new AudioCodec[0]);
    }

    public String mimeType() {
        return switch (this) {
            case MP4 -> "video/mp4";
            case MKV -> "video/mkv";
            case AVI -> "video/x-msvideo";
            case MOV -> "video/quicktime";
            case PNG_SEQUENCE -> "image/png";
            case EXR_SEQUENCE -> "image/x-exr";
            case WEBP -> "image/webp";
            case WEBM -> "video/webm";
            case GIF -> "image/gif";
        };
    }
}
