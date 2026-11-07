package com.packing.backend.core.file.port.out;

import com.packing.backend.core.file.SampleModel;

import java.util.List;
import java.util.Optional;

public interface SampleModelCatalog {

    List<SampleModel> list();

    Optional<SampleModel> find(String id);
}
