package com.packing.backend.api.packing;

import com.packing.backend.domain.packing.PackingPreview;
import com.packing.backend.domain.packing.ResultBin;

import java.util.List;
import java.util.stream.IntStream;

public record PackingJobResultBinResponse(int number, long sizeBytes, String checksum, List<PackingJobPreviewResponse.Model> previews) {

    static List<PackingJobResultBinResponse> listFrom(List<ResultBin> bins, PackingPreview preview) {
        return IntStream.rangeClosed(1, bins.size())
                        .mapToObj(number -> new PackingJobResultBinResponse(number,
                                                                            bins.get(number - 1)
                                                                                .sizeBytes(),
                                                                            bins.get(number - 1)
                                                                                .checksum(),
                                                                            PackingJobPreviewResponse.modelsOf(preview.binModels(number,
                                                                                                                                 bins.size()))))
                        .toList();
    }
}
