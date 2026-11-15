package com.packing.backend.core.packing.port.out;

import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PreviewQuality;

import java.util.Locale;

public sealed interface PackingArtifact {

    PackingJobId jobId();

    String downloadName();

    static BinResult binResult(PackingJob job, int number) {
        return new BinResult(job.id(), job.resultChecksum(), number, job.resultBins()
                                                                        .size());
    }

    static BinPreview binPreview(PackingJob job, int number, PreviewQuality quality) {
        return new BinPreview(job.id(), number, job.resultBins()
                                                   .size(), quality);
    }

    sealed interface PreviewArtifact extends PackingArtifact permits Preview, BinPreview {

        PreviewQuality quality();
    }

    record Result(PackingJobId jobId) implements PackingArtifact {

        @Override
        public String downloadName() {
            return prefix(jobId) + ".obj";
        }
    }

    record BinResult(PackingJobId jobId, String resultChecksum, int number, int binCount) implements PackingArtifact {

        @Override
        public String downloadName() {
            return prefix(jobId) + bin(number) + ".obj";
        }
    }

    record Preview(PackingJobId jobId, PreviewQuality quality) implements PreviewArtifact {

        @Override
        public String downloadName() {
            return prefix(jobId) + "-" + label(quality) + ".glb";
        }
    }

    record BinPreview(PackingJobId jobId, int number, int binCount, PreviewQuality quality) implements PreviewArtifact {

        @Override
        public String downloadName() {
            return prefix(jobId) + bin(number) + "-" + label(quality) + ".glb";
        }
    }

    private static String prefix(PackingJobId jobId) {
        return "packing-job-" + jobId;
    }

    private static String bin(int number) {
        return String.format(Locale.ROOT, "-bin-%03d", number);
    }

    private static String label(PreviewQuality quality) {
        return quality.name()
                      .toLowerCase(Locale.ROOT);
    }
}
