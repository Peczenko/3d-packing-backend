package com.packing.backend.api.packing;

import com.packing.backend.core.packing.PackingJobApplicationService.DraftSource;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobDraft;

import java.util.List;
import java.util.UUID;

public record PackingJobDraftResponse(
        DraftSource source,
        UUID sourceJobId,
        PackingJobInputResponse input,
        List<OmittedItem> omittedItems,
        List<String> resetFields) {

    public static PackingJobDraftResponse from(PackingJobDraft draft) {
        return new PackingJobDraftResponse(draft.source(),
                                           draft.sourceJobId(),
                                           PackingJobInputResponse.from(draft.input()),
                                           draft.omittedItems()
                                                .stream()
                                                .map(item -> new OmittedItem(item.fileId(), item.fileName()))
                                                .toList(),
                                           draft.resetFields());
    }

    public record OmittedItem(UUID fileId, String fileName) {
    }
}
