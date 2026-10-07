package com.ir.cases.service;

import java.util.UUID;

/**
 * Responder reservation port. Step 1-5 uses {@link StubResponderClient}; step 6 swaps in an
 * HTTP client to responder-service wrapped in a Resilience4j circuit breaker.
 */
public interface ResponderClient {

    /**
     * Atomically reserves a responder (the requested one, or the best match).
     *
     * @throws ResponderUnavailableException when the responder system cannot be reached
     * @throws com.ir.common.error.ApiException 409 when no eligible responder exists
     */
    Reservation reserve(UUID caseId, String requiredSkill, UUID requestedResponderId);

    void release(UUID responderId);

    record Reservation(UUID responderId, String userId) {
    }

    class ResponderUnavailableException extends RuntimeException {
        public ResponderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
