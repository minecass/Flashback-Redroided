package com.moulberry.flashback.combo_options;

import com.moulberry.flashback.exporting.PojavFFmpeg;

import java.util.ArrayList;
import java.util.List;

public enum AudioCodec implements ComboOption {
    AAC("AAC", "aac"),
    MP3("MP3", "libmp3lame"),
    OPUS("Opus", "libopus"),
    VORBIS("Vorbis", "libvorbis");

    private final String text;
    private final String encoder;

    AudioCodec(String text, String encoder) {
        this.text = text;
        this.encoder = encoder;
    }

    @Override
    public String text() {
        return text;
    }

    public String ffmpegEncoder() {
        return encoder;
    }

    public String[] getEncoders() {
        return PojavFFmpeg.hasEncoder(encoder) ? new String[] {encoder} : new String[0];
    }
}
