package com.packing.backend.api.project;

import com.packing.backend.core.project.ProjectMemberView;
import com.packing.backend.core.shared.Page;

import java.util.List;

public record ProjectMemberPageResponse(
        List<ProjectMemberResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static ProjectMemberPageResponse from(Page<ProjectMemberView> page) {
        return new ProjectMemberPageResponse(
                                             page.content()
                                                 .stream()
                                                 .map(ProjectMemberResponse::from)
                                                 .toList(),
                                             page.page(),
                                             page.size(),
                                             page.totalElements(),
                                             page.totalPages());
    }
}
