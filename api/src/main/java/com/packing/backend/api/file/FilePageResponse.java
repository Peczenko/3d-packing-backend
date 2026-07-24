package com.packing.backend.api.file;

import com.packing.backend.core.file.FileView;
import com.packing.backend.core.shared.Page;

import java.util.List;

public record FilePageResponse(
        List<FileResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static FilePageResponse from(Page<FileView> page) {
        return new FilePageResponse(
                page.content().stream().map(FileResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
