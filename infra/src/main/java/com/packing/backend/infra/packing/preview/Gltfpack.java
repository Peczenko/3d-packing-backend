package com.packing.backend.infra.packing.preview;

import com.packing.backend.domain.packing.PreviewQuality;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class Gltfpack {

    private static final int LOG_TAIL_BYTES = 2048;

    private final String   executable;
    private final Duration timeout;

    Gltfpack(String executable, Duration timeout) {
        this.executable = executable;
        this.timeout = timeout;
    }

    static List<String> options(PreviewQuality quality) {
        return switch (quality) {
            case HIGH -> List.of("-cc", "-mi", "-noq");
            case MEDIUM -> List.of("-cc", "-mi", "-si", "0.25");
            case LOW -> List.of("-cc", "-mi", "-si", "0.05", "-se", "0.05");
        };
    }

    void pack(Path input, Path output, PreviewQuality quality) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(options(quality));
        command.add("-i");
        command.add(input.toString());
        command.add("-o");
        command.add(output.toString());
        Path log = output.resolveSibling(output.getFileName() + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                                                     .redirectOutput(log.toFile())
                                                     .start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("gltfpack did not finish " + quality + " within " + timeout);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread()
                  .interrupt();
            throw new IOException("Interrupted while gltfpack built " + quality, interrupted);
        }
        try {
            if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new IOException("gltfpack failed to build " + quality + " with exit code " + process.exitValue() + ": " + tail(log));
            }
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static String tail(Path log) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            int size = (int) Math.min(length, LOG_TAIL_BYTES);
            byte[] bytes = new byte[size];
            file.seek(length - size);
            file.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8).strip();
        }
    }
}
