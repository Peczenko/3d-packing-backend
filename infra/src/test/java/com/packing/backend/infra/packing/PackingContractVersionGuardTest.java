package com.packing.backend.infra.packing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PackingContractVersionGuardTest {

    private static final Path    CONTRACTS         = Path.of("..", "contracts", "packing");
    private static final Pattern VERSION_DIRECTORY = Pattern.compile("v(\\d+)");

    private final PackingContractCodec codec = new PackingContractCodec(new ObjectMapper());

    @Test
    void requestEnvelopeIsEmittedOnlyAtTheVersionItsDeclaredContractCarries() throws IOException {
        List<Integer> declared = discoverContractVersions();
        int requestVersion = PackingRequestEnvelope.versionOne(60, "{}")
                                                   .requestVersion();

        assertThat(declared).contains(requestVersion);
        assertThat(CONTRACTS.resolve("v" + requestVersion)
                            .resolve("request-envelope.example.json")).exists();
        assertThat(declared.stream()
                           .filter(this::codecEmitsRequestVersion)
                           .toList()).containsExactly(requestVersion);
        assertThat(codecEmitsRequestVersion(declared.get(declared.size() - 1) + 1)).isFalse();
    }

    private boolean codecEmitsRequestVersion(int version) {
        try {
            codec.encodeRequest(new PackingRequestEnvelope(version, 60, "{}"));
            return true;
        } catch (DomainRuleViolationException e) {
            return false;
        }
    }

    static List<Integer> discoverContractVersions() throws IOException {
        List<Integer> versions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CONTRACTS, Files::isDirectory)) {
            for (Path dir : stream) {
                Matcher matcher = VERSION_DIRECTORY.matcher(dir.getFileName()
                                                               .toString());
                if (matcher.matches()) {
                    versions.add(Integer.parseInt(matcher.group(1)));
                }
            }
        }
        versions.sort(null);
        return versions;
    }
}
