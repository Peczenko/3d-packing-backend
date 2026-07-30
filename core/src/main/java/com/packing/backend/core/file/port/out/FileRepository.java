package com.packing.backend.core.file.port.out;

import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.project.ProjectId;

import java.util.List;
import java.util.Optional;

public interface FileRepository {

    StoredFile save(StoredFile file);

    List<StoredFile> saveAll(List<StoredFile> files);

    Optional<StoredFile> findById(FileId id);

    List<StoredFile> findAllAvailableByProject(ProjectId projectId);
}
