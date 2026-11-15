package com.packing.backend.core.file.port.out;

import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.domain.file.ModelFormat;

public interface ModelConverter {

    ConvertedModel toObj(ContentSource source, ModelFormat format);

    interface ConvertedModel extends ContentSource, AutoCloseable {

        @Override
        void close();
    }
}
