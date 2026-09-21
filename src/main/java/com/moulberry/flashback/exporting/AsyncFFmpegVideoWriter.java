package com.moulberry.flashback.exporting;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.SneakyThrow;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * FFmpeg writer backed by the PojavLauncher FFmpeg executable.
 *
 * Frames/audio are staged as raw files first.  At finish(), the plugin's
 * ffmpeg executable performs the actual encode/mux.  This keeps all native
 * FFmpeg/JavaCPP code out of the mod jar.
 */
public class AsyncFFmpegVideoWriter implements AutoCloseable, VideoWriter {
    private final ExportSettings settings;
    private final String filename;
    private boolean started;

    private final ArrayBlockingQueue<ImageFrame> encodeQueue = new ArrayBlockingQueue<>(32);
    private final AtomicBoolean finishEncodeThread = new AtomicBoolean(false);
    private final AtomicBoolean finishedWriting = new AtomicBoolean(false);
    private final AtomicReference<Throwable> threadedError = new AtomicReference<>();

    private Path tempDirectory;
    private Path rawVideo;
    private Path rawAudio;
    private BufferedOutputStream videoOut;
    private BufferedOutputStream audioOut;
    private int audioChannels;
    private Thread encodeThread;

    public AsyncFFmpegVideoWriter(ExportSettings settings, String filename) {
        this.settings = settings;
        this.filename = filename;
    }

    public synchronized void tryStart(int ignoredSrcPixelFormat) {
        if (started) return;
        started = true;

        PojavFFmpeg.requireAvailable();

        try {
            tempDirectory = Files.createTempDirectory("flashback-ffmpeg-");
            rawVideo = tempDirectory.resolve("video.rgba");
            rawAudio = tempDirectory.resolve("audio.f32le");

            videoOut = new BufferedOutputStream(Files.newOutputStream(rawVideo), 1024 * 1024);

            audioChannels = settings.recordAudio()
                ? ((settings.audioCodec() == com.moulberry.flashback.combo_options.AudioCodec.VORBIS
                    || settings.stereoAudio()) ? 2 : 1)
                : 0;

            if (settings.recordAudio()) {
                audioOut = new BufferedOutputStream(Files.newOutputStream(rawAudio), 1024 * 1024);
            }

            encodeThread = new Thread(this::encodeLoop, "Video Raw Frame Thread");
            encodeThread.start();

            Flashback.LOGGER.info("Using PojavLauncher FFmpeg plugin at {}", PojavFFmpeg.executable());
        } catch (IOException e) {
            throw SneakyThrow.sneakyThrow(e);
        }
    }

    private void encodeLoop() {
        try {
            while (true) {
                ImageFrame src = encodeQueue.poll(10, TimeUnit.MILLISECONDS);
                if (src == null) {
                    if (finishEncodeThread.get()) break;
                    continue;
                }

                try {
                    writeVideoFrame(src);
                    if (src.audioBuffer != null && audioOut != null) {
                        writeAudio(src.audioBuffer);
                    }
                } finally {
                    src.close();
                }
            }

            videoOut.close();
            if (audioOut != null) audioOut.close();

            runFFmpeg();
        } catch (Throwable t) {
            threadedError.set(t);
        } finally {
            finishedWriting.set(true);
        }
    }

    private void writeVideoFrame(ImageFrame src) throws IOException {
        if (src.width != settings.resolutionX() || src.height != settings.resolutionY()) {
            throw new IOException("Frame size " + src.width + "x" + src.height +
                " does not match export size " + settings.resolutionX() + "x" + settings.resolutionY());
        }

        // FFmpeg receives RGBA8. This is lossless staging; FFmpeg performs
        // scaling/colorspace conversion for the selected encoder.
        ByteBuffer rgba = ByteBuffer.allocate(4 * src.width * src.height);
        long p = src.pixels;

        if (src.format == ImageFrame.Format.RGBA_U8) {
            MemoryUtil.memByteBuffer(p, (int) src.size).get(rgba.array());
        } else if (src.format == ImageFrame.Format.GRAY_F32) {
            for (int i = 0; i < src.width * src.height; i++) {
                float value = MemoryUtil.memGetFloat(p + i * 4L);
                int v = Math.max(0, Math.min(255, Math.round(value * 255f)));
                rgba.put((byte) v).put((byte) v).put((byte) v).put((byte) 0xFF);
            }
        } else {
            throw new IOException("Unsupported ImageFrame custom pixel format: " + src.ffmpegPixelFormat());
        }

        videoOut.write(rgba.array());
    }

    private void writeAudio(FloatBuffer source) throws IOException {
        FloatBuffer audio = source.duplicate();
        audio.rewind();
        ByteBuffer bytes = ByteBuffer.allocate(audio.remaining() * Float.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        while (audio.hasRemaining()) {
            bytes.putFloat(audio.get());
        }
        audioOut.write(bytes.array());
    }

    private void runFFmpeg() throws IOException, InterruptedException {
        int width = settings.resolutionX();
        int height = settings.resolutionY();

        List<String> command = PojavFFmpeg.command();
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("warning");
        command.add("-y");

        command.add("-f");
        command.add("rawvideo");
        command.add("-pix_fmt");
        command.add("rgba");
        command.add("-video_size");
        command.add(width + "x" + height);
        command.add("-framerate");
        command.add(Double.toString(settings.framerate()));
        command.add("-i");
        command.add(rawVideo.toString());

        if (settings.recordAudio()) {
            command.add("-f");
            command.add("f32le");
            command.add("-ar");
            command.add("48000");
            command.add("-ac");
            command.add(Integer.toString(audioChannels));
            command.add("-i");
            command.add(rawAudio.toString());
        }

        command.add("-c:v");
        command.add(settings.encoder());

        if (settings.bitrate() > 0) {
            command.add("-b:v");
            command.add(Integer.toString(settings.bitrate()));
        }

        command.add("-g");
        command.add(Integer.toString((int) Math.max(20, Math.min(240, Math.ceil(settings.framerate() * 2)))));

        if (settings.transparent()) {
            String pixFmt = switch (settings.codec()) {
                case VP9 -> "yuva420p";
                case PRO_RES -> "yuva444p10le";
                case PNG -> "rgba";
                default -> "yuv420p";
            };
            command.add("-pix_fmt");
            command.add(pixFmt);
        } else if (settings.codec() != com.moulberry.flashback.combo_options.VideoCodec.PNG) {
            command.add("-pix_fmt");
            command.add("yuv420p");
        }

        if (settings.bitrate() == 0) {
            if (settings.encoder().endsWith("_nvenc")) {
                command.add("-preset");
                command.add("p7");
            } else if (settings.encoder().endsWith("_amf")) {
                command.add("-quality");
                command.add("quality");
            } else if (settings.encoder().equals("libx264")) {
                command.add("-preset");
                command.add("slower");
            }
        }

        if (settings.recordAudio()) {
            command.add("-c:a");
            command.add(settings.audioCodec().ffmpegEncoder());
            command.add("-b:a");
            command.add("256k");
        }

        command.add("-shortest");
        command.add(filename);

        Flashback.LOGGER.info(
            "Starting FFmpeg export: {}",
            String.join(" ", command)
        );
        Flashback.LOGGER.info("FFmpeg raw video: {}", rawVideo);
        if (settings.recordAudio()) {
            Flashback.LOGGER.info("FFmpeg raw audio: {}", rawAudio);
        }
        Flashback.LOGGER.info("FFmpeg output file: {}", filename);

        Process process = PojavFFmpeg.start(command, tempDirectory);
        String log;
        try (InputStream in = new BufferedInputStream(process.getInputStream())) {
            log = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        int exit = process.waitFor();

        Flashback.LOGGER.info("FFmpeg export exited with code {}", exit);
        if (!log.isBlank()) {
            Flashback.LOGGER.info("FFmpeg export output:\n{}", log);
        }

        Path outputPath = Path.of(filename);
        if (exit == 0) {
            if (Files.exists(outputPath)) {
                Flashback.LOGGER.info(
                    "FFmpeg export succeeded: output exists at {} ({} bytes)",
                    outputPath, Files.size(outputPath)
                );
            } else {
                Flashback.LOGGER.warn(
                    "FFmpeg returned exit code 0, but output file does not exist: {}",
                    outputPath
                );
            }
        }

        if (exit != 0) {
            throw new IOException("PojavLauncher FFmpeg failed with exit code " + exit + ":\n" + log);
        }
    }

    private void checkEncodeError(@Nullable AutoCloseable closeable) {
        Throwable t = threadedError.get();
        if (t == null) return;

        finishEncodeThread.set(true);
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                Flashback.LOGGER.error("Error while trying to close passed AutoCloseable", e);
            }
        }
        SneakyThrow.sneakyThrow(t);
    }

    @Override
    public void encode(ImageFrame src) {
        tryStart(src.ffmpegPixelFormat());
        checkEncodeError(src);

        if (finishEncodeThread.get() || finishedWriting.get()) {
            src.close();
            throw new IllegalStateException("Cannot encode after finish()");
        }

        while (true) {
            try {
                encodeQueue.put(src);
                return;
            } catch (InterruptedException ignored) {
                checkEncodeError(src);
            }
        }
    }

    @Override
    public void finish(Consumer<String> wait) {
        if (!started) return;

        checkEncodeError(null);

        while (!encodeQueue.isEmpty()) {
            checkEncodeError(null);
            wait.accept("encode queue");
            Thread.yield();
        }

        finishEncodeThread.set(true);

        while (!finishedWriting.get()) {
            checkEncodeError(null);
            wait.accept("ffmpeg encode");
            Thread.yield();
        }

        checkEncodeError(null);
        cleanupTempFiles();
    }

    @Override
    public void close() {
        if (!started) return;

        for (ImageFrame src : encodeQueue) {
            src.close();
        }
        encodeQueue.clear();
        finishEncodeThread.set(true);

        try {
            if (encodeThread != null) encodeThread.join(5000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        cleanupTempFiles();
    }

    private void cleanupTempFiles() {
        try {
            if (videoOut != null) videoOut.close();
        } catch (Exception ignored) {}
        try {
            if (audioOut != null) audioOut.close();
        } catch (Exception ignored) {}
        if (tempDirectory != null) {
            try {
                Files.walk(tempDirectory)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
            } catch (IOException ignored) {}
        }
    }
}
