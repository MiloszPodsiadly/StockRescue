package com.stockrescue.access.application;

import java.util.UUID;
import com.stockrescue.access.persistence.InvitationRateLimitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationRateLimit {
    private final InvitationRateLimitRepository attempts;
    public InvitationRateLimit(InvitationRateLimitRepository attempts) { this.attempts = attempts; }

    // A separate commit counts denied/invalid tokens too, even when their command rolls back.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean admit(UUID actor, String operation) {
        int seconds = operation.equals("INVITE") ? 3600 : 900;
        int limit = operation.equals("INVITE") ? 10 : 20;
        var count = attempts.recordAttempt(actor, operation, seconds, limit + 1);
        return count <= limit;
    }
}
