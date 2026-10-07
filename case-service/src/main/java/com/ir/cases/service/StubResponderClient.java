package com.ir.cases.service;

import com.ir.cases.domain.StubResponder;
import com.ir.cases.repository.StubResponderRepository;
import com.ir.common.error.ApiException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.UUID;

/**
 * Local responder table (minimum slice). Same contract as the future responder-service:
 * filter by skill and capacity, prefer the lowest load, deterministic tie-break by id.
 * {@code @Version} on the row turns a double-booking race into a 409.
 */
@Component
public class StubResponderClient implements ResponderClient {

    private final StubResponderRepository responders;

    public StubResponderClient(StubResponderRepository responders) {
        this.responders = responders;
    }

    @Override
    public Reservation reserve(UUID caseId, String requiredSkill, UUID requestedResponderId) {
        StubResponder chosen = requestedResponderId != null
                ? eligibleOrThrow(requestedResponderId, requiredSkill)
                : responders.findByAvailableTrue().stream()
                        .filter(r -> r.hasSkill(requiredSkill) && r.hasCapacity())
                        .min(Comparator.comparingInt(StubResponder::getActiveCaseCount)
                                .thenComparing(StubResponder::getId))
                        .orElseThrow(() -> ApiException.conflict("no-responder-available",
                                "No available responder with skill " + requiredSkill));
        chosen.setActiveCaseCount(chosen.getActiveCaseCount() + 1);
        responders.save(chosen);
        return new Reservation(chosen.getId(), chosen.getUserId());
    }

    @Override
    public void release(UUID responderId) {
        responders.findById(responderId).ifPresent(r -> {
            r.setActiveCaseCount(Math.max(0, r.getActiveCaseCount() - 1));
            responders.save(r);
        });
    }

    private StubResponder eligibleOrThrow(UUID id, String requiredSkill) {
        StubResponder r = responders.findById(id).orElseThrow(() -> ApiException.notFound("Responder"));
        if (!r.hasSkill(requiredSkill) || !r.hasCapacity()) {
            throw ApiException.conflict("responder-not-eligible",
                    "Responder lacks skill " + requiredSkill + " or has no capacity");
        }
        return r;
    }
}
