package com.packing.backend.domain.packing;

import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PackingInput(int schemaVersion, List<PackingItem> items, PackingParameters parameters) {

    public static final int MAX_ITEMS = 50;
    public static final int MAX_TOTAL_COPIES = 200;

    public PackingInput {
        if (schemaVersion < 1) {
            throw new DomainRuleViolationException("Input schema version must be at least 1");
        }
        Objects.requireNonNull(parameters, "parameters");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new DomainRuleViolationException("A packing job needs between 1 and " + MAX_ITEMS + " files");
        }
        Set<FileId> seen = new HashSet<>();
        int total = 0;
        for (PackingItem item : items) {
            if (!seen.add(item.fileId())) {
                throw new DomainRuleViolationException("File " + item.fileName()
                                                                     .value()
                        + " is listed more than once");
            }
            total += item.copies();
        }
        if (total > MAX_TOTAL_COPIES) {
            throw new DomainRuleViolationException(
                                                   "A packing job may hold at most " + MAX_TOTAL_COPIES + " objects; this one has " + total);
        }
    }
}
