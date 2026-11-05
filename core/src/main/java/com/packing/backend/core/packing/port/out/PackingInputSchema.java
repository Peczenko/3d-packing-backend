package com.packing.backend.core.packing.port.out;

import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.packing.PackingParameters;

import java.util.List;
import java.util.Set;

public interface PackingInputSchema {

    SchemaDocument document();

    Set<ModelFormat> acceptedFormats();

    ValidatedInput validate(String body);

    Settings defaults();

    Reconciled reconcile(Settings stored);

    record SchemaDocument(int version, String json, String etag) {
    }

    record RequestedItem(FileId fileId, int copies) {
    }

    record Settings(long maxRuntimeSeconds, PackingParameters parameters) {
    }

    record Reconciled(Settings settings, List<String> resetFields) {

        public Reconciled {
            resetFields = List.copyOf(resetFields);
        }
    }

    record ValidatedInput(int schemaVersion,
            List<RequestedItem> items,
            long maxRuntimeSeconds,
            PackingParameters parameters) {

        public ValidatedInput {
            items = List.copyOf(items);
        }
    }
}
