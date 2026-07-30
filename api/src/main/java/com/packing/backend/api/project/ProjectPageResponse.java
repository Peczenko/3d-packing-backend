package com.packing.backend.api.project;

import com.packing.backend.core.project.ProjectSummaryView;
import com.packing.backend.core.shared.Page;

import java.util.List;

public record ProjectPageResponse(
        List<ProjectSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static ProjectPageResponse from(Page<ProjectSummaryView> page) {
        return new ProjectPageResponse(
                page.content().stream().map(ProjectSummaryResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
