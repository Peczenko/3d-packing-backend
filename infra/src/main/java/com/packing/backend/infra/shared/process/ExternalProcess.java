package com.packing.backend.infra.shared.process;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class ExternalProcess {

    private static final int         LOG_TAIL_BYTES = 2048;
    private static final Set<String> KEPT_VARIABLES = Set.of("SystemRoot");

    private ExternalProcess() {
    }

    public static Outcome run(List<String> command, Path workingDirectory, Path log, Duration timeout) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile())
                                                            .redirectErrorStream(true)
                                                            .redirectOutput(log.toFile());
        builder.environment()
               .keySet()
               .retainAll(KEPT_VARIABLES);
        Process process = builder.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                       .waitFor(5, TimeUnit.SECONDS);
                return new Outcome(-1, true, tail(log));
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread()
                  .interrupt();
            InterruptedIOException failure = new InterruptedIOException("Interrupted while running " + command.getFirst());
            failure.initCause(interrupted);
            throw failure;
        }
        return new Outcome(process.exitValue(), false, tail(log));
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

    public record Outcome(int exitCode, boolean timedOut, String output) {

        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }
}
