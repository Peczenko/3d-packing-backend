package com.packing.backend.domain.packing;

import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.shared.DomainRuleViolationException;

public record ResultBin(long sizeBytes, String checksum) {

    public ResultBin {
        if (sizeBytes <= 0) {
            throw new DomainRuleViolationException("A result bin must have a positive size");
        }
        checksum = new Checksum(checksum).value();
    }
}
