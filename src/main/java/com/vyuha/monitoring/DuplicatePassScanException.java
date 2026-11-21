package com.vyuha.monitoring;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thrown on duplicate pass check-in or invalid check-out so the HTTP 409 CONFLICT
 * response is returned while allowing any recorded DUPLICATE_PASS_SCAN alert to commit.
 */
public class DuplicatePassScanException extends ResponseStatusException {

    public DuplicatePassScanException(String reason) {
        super(HttpStatus.CONFLICT, reason);
    }
}
