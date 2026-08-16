package com.packing.backend.api.packing;

import com.packing.backend.core.packing.PackingJobView;
import com.packing.backend.core.shared.Page;

import java.util.List;

public record PackingJobPageResponse(
        List<PackingJobResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static PackingJobPageResponse from(Page<PackingJobView> page) {
        return new PackingJobPageResponse(
                                          page.content()
                                              .stream()
                                              .map(PackingJobResponse::from)
                                              .toList(),
                                          page.page(),
                                          page.size(),
                                          page.totalElements(),
                                          page.totalPages());
    }
}
