package com.packing.backend.api.packing;

import com.packing.backend.domain.packing.ResultBin;

import java.util.List;
import java.util.stream.IntStream;

public record PackingJobResultBinResponse(int number, long sizeBytes, String checksum) {

    static List<PackingJobResultBinResponse> listFrom(List<ResultBin> bins) {
        return IntStream.range(0, bins.size())
                        .mapToObj(i -> new PackingJobResultBinResponse(i + 1,
                                                                       bins.get(i)
                                                                           .sizeBytes(),
                                                                       bins.get(i)
                                                                           .checksum()))
                        .toList();
    }
}
