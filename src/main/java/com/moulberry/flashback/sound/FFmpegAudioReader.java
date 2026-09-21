package com.moulberry.flashback.sound;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.PojavFFmpeg;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decodes arbitrary audio through the PojavLauncher FFmpeg executable.
 * Output is signed 16-bit little-endian PCM at 48 kHz stereo.
 */
public final class FFmpegAudioReader {
    private FFmpegAudioReader() {}

    public record RawAudioData(ByteBuffer byteBuffer, AudioFormat audioFormat) {}

    public static RawAudioData read(InputStream inputStream) {
        Path input = null;
        try {
            input = Files.createTempFile("flashback-audio-", ".input");
            try (var out = Files.newOutputStream(input)) {
                inputStream.transferTo(out);
            }

            var command = PojavFFmpeg.command();
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("error");
            command.add("-i");
            command.add(input.toString());
            command.add("-f");
            command.add("s16le");
            command.add("-ar");
            command.add("48000");
            command.add("-ac");
            command.add("2");
            command.add("pipe:1");

            Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();

            byte[] pcm;
            try (var stdout = process.getInputStream()) {
                pcm = stdout.readAllBytes();
            }
            String stderr;
            try (var err = process.getErrorStream()) {
                stderr = new String(err.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }

            int exit = process.waitFor();
            if (exit != 0) {
                Flashback.LOGGER.error("FFmpeg audio decode failed ({}): {}", exit, stderr);
                return null;
            }

            ByteBuffer buffer = ByteBuffer.allocateDirect(pcm.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(pcm).flip();

            AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                48000,
                16,
                2,
                4,
                48000,
                false
            );
            return new RawAudioData(buffer, format);
        } catch (Throwable t) {
            Flashback.LOGGER.error("Unable to decode audio with PojavLauncher FFmpeg", t);
            return null;
        } finally {
            if (input != null) {
                try { Files.deleteIfExists(input); } catch (IOException ignored) {}
            }
        }
    }
}
