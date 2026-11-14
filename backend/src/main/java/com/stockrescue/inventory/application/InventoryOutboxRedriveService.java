package com.stockrescue.inventory.application;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.inventory.messaging.InventoryEventCodec;
import com.stockrescue.inventory.persistence.InventoryOutboxFingerprint;
import com.stockrescue.inventory.persistence.InventoryOutboxRedriveRepository;
import com.stockrescue.inventory.persistence.InventoryOutboxRedriveRepository.Attempt;
import com.stockrescue.inventory.persistence.InventoryOutboxRedriveRepository.ParkedEvent;
import com.stockrescue.platform.audit.AuditWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryOutboxRedriveService {
    public record ParkedPage(List<ParkedDiagnostic> items,boolean hasMore) {
        public ParkedPage { items=List.copyOf(items); }
    }
    public record ParkedDiagnostic(UUID eventId,UUID movementId,UUID positionId,long aggregateVersion,
            String eventType,int schemaVersion,java.time.Instant occurredAt,java.time.Instant parkedAt,
            long originalClaimVersion,int originalAttemptCount,String sourceFingerprint,long latestGeneration,
            String state,int redriveAttemptCount,java.time.Instant latestAttemptAt,String lastErrorCode) { }
    public record RedriveRequest(UUID requestId,long expectedOriginalClaimVersion,int expectedOriginalAttemptCount,
            String sourceFingerprint,String reason,String evidenceReference) { }
    public record RedriveResult(UUID requestId,UUID eventId,long generation,String state,int sendAttemptCount,
            java.time.Instant nextAttemptAt,java.time.Instant publishedAt,java.time.Instant blockedAt,
            String lastErrorCode,String sourceFingerprint) { }

    private final AccessPolicy access;
    private final InventoryOutboxRedriveRepository redrives;
    private final InventoryEventCodec codec;
    private final AuditWriter audit;

    public InventoryOutboxRedriveService(AccessPolicy access,InventoryOutboxRedriveRepository redrives,
            InventoryEventCodec codec,AuditWriter audit) {
        this.access=access;this.redrives=redrives;this.codec=codec;this.audit=audit;
    }

    @Transactional
    public ParkedPage parked(UUID actor,UUID organizationId,UUID storeId,int offset,int limit) {
        if(offset<0 || offset>10000 || limit<1 || limit>100) throw new IllegalArgumentException("Invalid parked-event page");
        access.requireAdmin(actor,organizationId,true);
        requireStore(organizationId,storeId);
        var page=redrives.parked(organizationId,storeId,offset,limit+1);
        boolean hasMore=page.size()>limit;
        return new ParkedPage(page.stream().limit(limit).map(this::diagnostic).toList(),hasMore);
    }

    @Transactional
    public RedriveResult redrive(UUID actor,UUID organizationId,UUID storeId,UUID eventId,RedriveRequest request) {
        if(request==null || request.requestId()==null || request.expectedOriginalClaimVersion()<0
                || request.expectedOriginalAttemptCount()<0 || request.sourceFingerprint()==null
                || !request.sourceFingerprint().matches("[0-9a-f]{64}")) {
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        }
        String reason=requiredEvidence(request.reason(),500);
        String evidence=requiredEvidence(request.evidenceReference(),256);
        var administrator=access.requireAdmin(actor,organizationId,true);
        requireStore(organizationId,storeId);
        byte[] requestFingerprint=InventoryOutboxFingerprint.digest("redrive-request",organizationId,storeId,eventId,
                actor,request.requestId(),request.expectedOriginalClaimVersion(),request.expectedOriginalAttemptCount(),
                request.sourceFingerprint(),reason,evidence);
        redrives.lockRequestScope(organizationId,actor,request.requestId());
        Attempt replay=redrives.findRequest(organizationId,actor,request.requestId());
        if(replay!=null) {
            if(!MessageDigest.isEqual(replay.requestFingerprint(),requestFingerprint))
                throw new InventoryFailure(InventoryFailure.Kind.IDEMPOTENCY_CONFLICT);
            return result(replay);
        }

        ParkedEvent parked=redrives.lockParked(organizationId,storeId,eventId);
        if(parked==null) throw new OrganizationUnavailableException();
        if(parked.claimVersion()!=request.expectedOriginalClaimVersion()
                || parked.originalAttemptCount()!=request.expectedOriginalAttemptCount()
                || !MessageDigest.isEqual(parked.source().fingerprint().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        request.sourceFingerprint().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new InventoryFailure(InventoryFailure.Kind.CONFLICT);
        }
        if(redrives.hasAcknowledgedOrActiveAttempt(eventId)) throw new InventoryFailure(InventoryFailure.Kind.CONFLICT);

        String blockedCode=null;
        try { codec.fromOutbox(parked.event(),parked.source()); }
        catch(com.stockrescue.inventory.messaging.InventoryEventContractException invalid) { blockedCode=invalid.code(); }
        Attempt created=redrives.insert(UUID.randomUUID(),parked,request.requestId(),actor,administrator.membershipId(),
                requestFingerprint,reason,evidence,blockedCode);
        String state=blockedCode==null?"REDRIVE_QUEUED":"BLOCKED";
        RedriveResult response=result(created);
        var responseEvidence=new java.util.LinkedHashMap<String,Object>();
        responseEvidence.put("requestId",response.requestId().toString());
        responseEvidence.put("eventId",response.eventId().toString());
        responseEvidence.put("generation",response.generation());
        responseEvidence.put("state",response.state());
        responseEvidence.put("sendAttemptCount",response.sendAttemptCount());
        responseEvidence.put("nextAttemptAt",response.nextAttemptAt().toString());
        responseEvidence.put("publishedAt",null);
        responseEvidence.put("blockedAt",response.blockedAt()==null?null:response.blockedAt().toString());
        responseEvidence.put("lastErrorCode",response.lastErrorCode());
        responseEvidence.put("sourceFingerprint",response.sourceFingerprint());
        audit.securityChange(actor,organizationId,"inventory-outbox-event",eventId,"INVENTORY_OUTBOX_REDRIVE_REQUESTED",
                Map.of("outcome",state,"storeId",storeId.toString(),"generation",created.generation(),
                        "requestId",request.requestId().toString(),"sourceFingerprint",parked.source().fingerprint(),
                        "evidenceReference",evidence,"response",responseEvidence),reason);
        return response;
    }

    private ParkedDiagnostic diagnostic(ParkedEvent row) {
        var event=row.event();
        return new ParkedDiagnostic(event.id(),event.movementId(),event.positionId(),event.aggregateVersion(),
                event.eventType(),event.schemaVersion(),event.occurredAt(),row.parkedAt(),row.claimVersion(),
                row.originalAttemptCount(),row.source().fingerprint(),row.latestGeneration(),row.state(),
                row.redriveAttemptCount(),row.latestAttemptAt(),row.lastErrorCode());
    }

    private static RedriveResult result(Attempt attempt) {
        return new RedriveResult(attempt.requestId(),attempt.eventId(),attempt.generation(),attempt.acceptedState(),
                0,attempt.acceptedNextAttemptAt(),null,attempt.acceptedBlockedAt(),
                attempt.acceptedErrorCode(),attempt.sourceFingerprintHex());
    }

    private void requireStore(UUID organizationId,UUID storeId) {
        if(!redrives.storeExists(organizationId,storeId)) throw new OrganizationUnavailableException();
    }

    private static String requiredEvidence(String value,int maximum) {
        if(value==null) throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        String normalized=value.trim();
        if(normalized.isEmpty() || normalized.length()>maximum || normalized.codePoints().anyMatch(Character::isISOControl))
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        return normalized;
    }
}
