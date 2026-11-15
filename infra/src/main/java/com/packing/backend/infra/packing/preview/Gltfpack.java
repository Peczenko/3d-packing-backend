package com.packing.backend.infra.packing.preview;

import com.packing.backend.domain.packing.PreviewQuality;
import com.packing.backend.infra.shared.process.ExternalProcess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class Gltfpack {

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
        try {
            ExternalProcess.Outcome outcome = ExternalProcess.run(command,
                                                                  output.toAbsolutePath()
                                                                        .getParent(),
                                                                  log,
                                                                  timeout);
            if (outcome.timedOut()) {
                throw new IOException("gltfpack did not finish " + quality + " within " + timeout);
            }
            if (!outcome.succeeded() || !Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new IOException("gltfpack failed to build " + quality + " with exit code " + outcome.exitCode() + ": " + outcome.output());
            }
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
