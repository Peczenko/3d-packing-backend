package com.packing.backend.infra.conversion;

import com.packing.backend.infra.shared.process.ExternalProcess;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class Assimp {

    static final List<String> OPTIONS = List.of("--format=objnomtl", "--triangulate", "--drop-normals", "--join-identical-vertices");

    private static final String LOAD_FAILURE = "ERROR: Failed to load file: ";

    private final String   executable;
    private final Duration timeout;

    Assimp(String executable, Duration timeout) {
        this.executable = executable;
        this.timeout = timeout;
    }

    Duration timeout() {
        return timeout;
    }

    Result export(Path directory, String source, String target) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("export");
        command.add(source);
        command.add(target);
        command.addAll(OPTIONS);
        Path log = directory.resolve("assimp.log");
        ExternalProcess.Outcome outcome;
        try {
            outcome = ExternalProcess.run(command, directory, log, timeout);
        } finally {
            log.toFile()
               .delete();
        }
        if (outcome.timedOut()) {
            return Result.TIMED_OUT;
        }
        if (outcome.succeeded()) {
            return Result.EXPORTED;
        }
        return new Result(false, false, outcome.exitCode(), loadFailure(outcome.output()).orElse(null));
    }

    static Optional<String> loadFailure(String output) {
        return output.lines()
                     .filter(line -> line.startsWith(LOAD_FAILURE))
                     .map(line -> line.substring(LOAD_FAILURE.length())
                                      .strip())
                     .filter(reason -> !reason.isEmpty())
                     .reduce((first, last) -> last);
    }

    record Result(boolean exported, boolean timedOut, int exitCode, String reason) {

        static final Result EXPORTED = new Result(true, false, 0, null);
        static final Result TIMED_OUT = new Result(false, true, -1, null);
    }
}
