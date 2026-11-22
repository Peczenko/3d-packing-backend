package com.packing.backend.api.packing;

import com.packing.backend.domain.packing.PackingPreview;
import com.packing.backend.domain.packing.ResultBin;

import java.util.List;
import java.util.stream.IntStream;

public record PackingJobResultBinResponse(int number, Long sizeBytes, String checksum, List<PackingJobPreviewResponse.Model> previews) {

    static List<PackingJobResultBinResponse> listFrom(int binCount, List<ResultBin> bins, PackingPreview preview) {
        return IntStream.rangeClosed(1, binCount)
                        .mapToObj(number -> {
                            ResultBin bin = number <= bins.size() ? bins.get(number - 1) : null;
                            return new PackingJobResultBinResponse(number,
                                                                   bin == null ? null : bin.sizeBytes(),
                                                                   bin == null ? null : bin.checksum(),
                                                                   PackingJobPreviewResponse.modelsOf(preview.binModels(number, binCount)));
                        })
                        .toList();
    }
}
