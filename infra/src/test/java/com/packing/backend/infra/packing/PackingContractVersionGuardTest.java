package com.packing.backend.infra.packing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.packing.message.PackingDispatchMessage;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PackingContractVersionGuardTest {

    private static final PackingJobId JOB_ID            = new PackingJobId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final Pattern      VERSION_DIRECTORY = Pattern.compile("v(\\d+)");

    private final PackingContractCodec codec = new PackingContractCodec(new ObjectMapper());

    @Test
    void codecAcceptsExactlyTheDeclaredContractVersions() throws IOException {
        List<Integer> declared = discoverContractVersions();

        List<Integer> accepted = declared.stream()
                                         .filter(this::codecAcceptsVersion)
                                         .collect(Collectors.toList());

        assertThat(accepted)
                            .as("versions decodeDispatch accepts, out of the declared contract directories %s", declared)
                            .isEqualTo(declared);

        int beyondNewest = declared.get(declared.size() - 1) + 1;
        assertThat(codecAcceptsVersion(beyondNewest))
                                                     .as("decodeDispatch accepted version %d, which has no contracts/packing/v%d directory",
                                                         beyondNewest,
                                                         beyondNewest)
                                                     .isFalse();
    }

    @Test
    void codecEmitsTheNewestDeclaredContractVersionAndNothingElse() throws IOException {
        List<Integer> declared = discoverContractVersions();
        int newest = declared.get(declared.size() - 1);

        List<Integer> emitting = declared.stream()
                                         .filter(this::codecEmitsVersion)
                                         .collect(Collectors.toList());

        assertThat(emitting)
                            .as("versions encodeDispatch will emit, out of the declared contract directories %s", declared)
                            .containsExactly(newest);
        assertThat(codecAcceptsVersion(newest))
                                               .as("codec must accept the version it emits")
                                               .isTrue();
    }

    private boolean codecAcceptsVersion(int version) {
        String dispatch = "{\"messageVersion\":" + version + ",\"jobId\":\"" + JOB_ID + "\"}";
        try {
            codec.decodeDispatch(dispatch);
            return true;
        } catch (DomainRuleViolationException e) {
            return false;
        }
    }

    private boolean codecEmitsVersion(int version) {
        try {
            codec.encodeDispatch(new PackingDispatchMessage(version, JOB_ID));
            return true;
        } catch (DomainRuleViolationException e) {
            return false;
        }
    }

    private List<Integer> discoverContractVersions() throws IOException {
        Path contractsDir = Path.of("..", "contracts", "packing");
        List<Integer> versions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(contractsDir, Files::isDirectory)) {
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
