package com.packing.backend.core.file.port.out;

import com.packing.backend.core.file.FileView;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.domain.project.ProjectId;

public interface FileFinder {
    
    Page<FileView> listAvailableInProject(ProjectId projectId, PageRequest page);
}
