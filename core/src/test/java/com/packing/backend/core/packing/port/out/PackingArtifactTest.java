package com.packing.backend.core.packing.port.out;

import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PreviewQuality;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PackingArtifactTest {

    private static final PackingJobId JOB = new PackingJobId(UUID.fromString("7f8a1c2e-0d3b-4e5f-9a6b-1c2d3e4f5a6b"));

    @Test
    void namesEachDownloadAfterTheJobTheContainerAndTheQuality() {
        assertThat(new PackingArtifact.Result(JOB).downloadName()).isEqualTo("packing-job-" + JOB + ".obj");
        assertThat(new PackingArtifact.BinResult(JOB, "b".repeat(64), 2, 3).downloadName()).isEqualTo("packing-job-" + JOB + "-bin-002.obj");
        assertThat(new PackingArtifact.Preview(JOB, PreviewQuality.HIGH).downloadName()).isEqualTo("packing-job-" + JOB + "-high.glb");
        assertThat(new PackingArtifact.BinPreview(JOB, 12, 12, PreviewQuality.LOW).downloadName()).isEqualTo("packing-job-" + JOB
                + "-bin-012-low.glb");
    }
}
