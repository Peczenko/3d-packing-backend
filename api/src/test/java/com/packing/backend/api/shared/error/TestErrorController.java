package com.packing.backend.api.shared.error;

import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.shared.ResourceNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test-errors")
class TestErrorController {

    @GetMapping("/unexpected")
    void unexpected() {
        throw new IllegalStateException("boom");
    }

    @GetMapping("/unexpected/{id}")
    void unexpectedForId(@PathVariable String id) {
        throw new IllegalStateException("boom for " + id);
    }

    @GetMapping("/dependency")
    void dependency() {
        throw new ExternalServiceException("azure-blob-storage", "unreachable");
    }

    @GetMapping("/missing")
    void missing() {
        throw new ResourceNotFoundException("No such thing");
    }

    @GetMapping("/rejected")
    void rejected() {
        throw new DomainRuleViolationException("Not allowed");
    }
}
