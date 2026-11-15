package com.packing.backend.api.shared.web;

import com.packing.backend.core.shared.TemporaryUrl;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public final class TemporaryRedirect {

    private TemporaryRedirect() {
    }

    public static ResponseEntity<Void> to(TemporaryUrl target) {
        return ResponseEntity.status(HttpStatus.FOUND)
                             .location(target.url())
                             .cacheControl(CacheControl.noStore())
                             .header(HttpHeaders.PRAGMA, "no-cache")
                             .build();
    }
}
