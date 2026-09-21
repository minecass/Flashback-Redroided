package com.moulberry.flashback.exporting;

import com.moulberry.flashback.Flashback;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Access to the FFmpeg executable supplied by the PojavLauncher FFmpeg plugin.
 *
 * PojavLauncher exports POJAV_FFMPEG_PATH when the plugin is installed.
 * This avoids bundling JavaCPP/FFmpeg native libraries in the mod jar.
 */
public final class PojavFFmpeg {
    private PojavFFmpeg() {}

    public static String executable() {
        String path = System.getenv("POJAV_FFMPEG_PATH");
        if (path != null && !path.isBlank()) {
            return path;
        }

        // Do not try to execute libffmpeg.so directly. The PojavLauncher
        // FFmpeg plugin provides the actual executable through
        // POJAV_FFMPEG_PATH.
        //
        // Do not fall back to the literal "ffmpeg" on Android/Pojav.
        if (System.getenv("ANDROID_ROOT") != null) {
            return null;
        }

        // Keep desktop/dev environments usable when ffmpeg is on PATH.
        return "ffmpeg";
    }

    /**
     * Makes the MojoLauncher/PojavLauncher FFmpeg plugin's native-library
     * directory visible to the dynamic linker.
     *
     * libffmpeg.so depends on libc++_shared.so, which is shipped alongside
     * it by the FFmpeg plugin. When FFmpeg is launched as a child process,
     * the child must have the plugin directory in LD_LIBRARY_PATH.
     */
    private static void configureEnvironment(ProcessBuilder builder) {
        String executable = executable();

        if (executable == null || executable.isBlank()) {
            return;
        }

        File executableFile = new File(executable);
        File libraryDir = executableFile.getParentFile();

        if (libraryDir == null) {
            return;
        }

        String libraryPath = libraryDir.getAbsolutePath();

        Map<String, String> environment = builder.environment();
        String existing = environment.get("LD_LIBRARY_PATH");

        if (existing == null || existing.isBlank()) {
            environment.put("LD_LIBRARY_PATH", libraryPath);
        } else if (!existing.contains(libraryPath)) {
            environment.put(
                "LD_LIBRARY_PATH",
                libraryPath + ":" + existing
            );
        }

        Flashback.LOGGER.info(
            "FFmpeg LD_LIBRARY_PATH: {}",
            environment.get("LD_LIBRARY_PATH")
        );
    }

    public static boolean isAvailable() {
        try {
            String executable = executable();

            if (executable == null || executable.isBlank()) {
                Flashback.LOGGER.warn(
                    "PojavLauncher FFmpeg executable path is unavailable"
                );
                return false;
            }

            Flashback.LOGGER.info(
                "Checking PojavLauncher FFmpeg executable: {}",
                executable
            );

            ProcessBuilder builder = new ProcessBuilder(
                executable,
                "-hide_banner",
                "-version"
            );

            configureEnvironment(builder);

            Process process = builder
                .redirectErrorStream(true)
                .start();

            process.getInputStream().transferTo(
                java.io.OutputStream.nullOutputStream()
            );

            return process.waitFor() == 0;
        } catch (Throwable t) {
            Flashback.LOGGER.warn(
                "Unable to start PojavLauncher FFmpeg",
                t
            );
            return false;
        }
    }

    public static String probe(String... args)
        throws IOException, InterruptedException {

        String executable = executable();

        if (executable == null || executable.isBlank()) {
            throw new IOException(
                "PojavLauncher FFmpeg executable path is unavailable. " +
                "POJAV_FFMPEG_PATH was not provided by PojavLauncher."
            );
        }

        List<String> command = new ArrayList<>();
        command.add(executable);

        for (String arg : args) {
            command.add(arg);
        }

        Flashback.LOGGER.info(
            "Running PojavLauncher FFmpeg: {}",
            String.join(" ", command)
        );

        ProcessBuilder builder = new ProcessBuilder(command);

        configureEnvironment(builder);

        Process process = builder
            .redirectErrorStream(true)
            .start();

        String output;

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8
            )
        )) {
            output = reader.lines()
                .reduce("", (a, b) -> a + b + "\n");
        }

        int exit = process.waitFor();

        // Diagnostic logging: tells us whether FFmpeg itself succeeded.
        Flashback.LOGGER.info("FFmpeg exited with code {}", exit);
        Flashback.LOGGER.info("FFmpeg output:\n{}", output);

        if (exit != 0) {
            throw new IOException(
                "FFmpeg exited with code " + exit + ":\n" + output
            );
        }

        return output;
    }

    public static Process start(
        List<String> command,
        Path workingDirectory
    ) throws IOException {

        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException(
                "FFmpeg command cannot be empty"
            );
        }

        Flashback.LOGGER.info(
            "Starting PojavLauncher FFmpeg: {}",
            String.join(" ", command)
        );

        ProcessBuilder builder = new ProcessBuilder(command);

        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }

        configureEnvironment(builder);

        builder.redirectErrorStream(true);

        return builder.start();
    }

    public static List<String> command() {
        String executable = executable();

        if (executable == null || executable.isBlank()) {
            throw new IllegalStateException(
                "PojavLauncher FFmpeg executable path is unavailable."
            );
        }

        return new ArrayList<>(List.of(executable));
    }

    private static volatile String cachedEncoderList;
    private static volatile String cachedMuxerList;

    public static String encoderList() {
        String cached = cachedEncoderList;

        if (cached != null) {
            return cached;
        }

        try {
            cachedEncoderList = probe(
                "-hide_banner",
                "-encoders"
            );

            return cachedEncoderList;
        } catch (Exception e) {
            Flashback.LOGGER.warn(
                "Unable to query PojavLauncher FFmpeg encoders",
                e
            );

            return "";
        }
    }

    public static String muxerList() {
        String cached = cachedMuxerList;

        if (cached != null) {
            return cached;
        }

        try {
            cachedMuxerList = probe(
                "-hide_banner",
                "-muxers"
            );

            return cachedMuxerList;
        } catch (Exception e) {
            Flashback.LOGGER.warn(
                "Unable to query PojavLauncher FFmpeg muxers",
                e
            );

            return "";
        }
    }

    public static boolean hasEncoder(String name) {
        return encoderList().lines().anyMatch(line ->
            line.matches(
                "^\\s+\\S+\\s+" +
                java.util.regex.Pattern.quote(name) +
                "\\s+.*$"
            )
        );
    }

    public static void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException(
                "PojavLauncher FFmpeg plugin is not available. " +
                "Install/enable the PojavLauncher FFmpeg plugin " +
                "so POJAV_FFMPEG_PATH is provided."
            );
        }
    }
}
