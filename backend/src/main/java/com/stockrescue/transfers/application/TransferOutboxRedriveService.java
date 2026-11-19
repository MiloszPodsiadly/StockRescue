package com.stockrescue.transfers.application;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.transfers.messaging.TransferEventCodec;
import com.stockrescue.transfers.messaging.TransferEventContractException;
import com.stockrescue.transfers.persistence.TransferOutboxFingerprint;
import com.stockrescue.transfers.persistence.TransferOutboxRedriveRepository;
import com.stockrescue.transfers.persistence.TransferOutboxRedriveRepository.Attempt;
import com.stockrescue.transfers.persistence.TransferOutboxRedriveRepository.ParkedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import io.swagger.v3.oas.annotations.media.Schema;

@Service
public class TransferOutboxRedriveService {
    @Schema(name="TransferParkedPage")
    public record ParkedPage(List<ParkedDiagnostic> items,boolean hasMore) {
        public ParkedPage { items=List.copyOf(items); }
    }
    @Schema(name="TransferParkedDiagnostic")
    public record ParkedDiagnostic(UUID eventId,UUID transferId,long aggregateVersion,String eventType,int schemaVersion,
            java.time.Instant occurredAt,java.time.Instant parkedAt,long originalClaimVersion,int originalAttemptCount,
            String sourceFingerprint,long latestGeneration,String state,int redriveAttemptCount,
            java.time.Instant latestAttemptAt,String lastErrorCode) { }
    public record RedriveRequest(UUID requestId,long expectedOriginalClaimVersion,int expectedOriginalAttemptCount,
            String sourceFingerprint,String reason,String evidenceReference) { }
    @Schema(name="TransferOutboxRedriveResult")
    public record RedriveResult(UUID requestId,UUID eventId,long generation,String state,int sendAttemptCount,
            java.time.Instant nextAttemptAt,java.time.Instant publishedAt,java.time.Instant blockedAt,
            String lastErrorCode,String sourceFingerprint) { }

    private final AccessPolicy access;
    private final TransferOutboxRedriveRepository redrives;
    private final TransferEventCodec codec;
    private final AuditWriter audit;

    public TransferOutboxRedriveService(AccessPolicy access,TransferOutboxRedriveRepository redrives,
            TransferEventCodec codec,AuditWriter audit) {
        this.access=access;this.redrives=redrives;this.codec=codec;this.audit=audit;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ParkedPage parked(UUID actor,UUID organizationId,int offset,int limit) {
        if(offset<0||offset>10000||limit<1||limit>100) throw new IllegalArgumentException("Invalid parked-event page");
        access.requireAdmin(actor,organizationId,false);
        var rows=redrives.parked(organizationId,offset,limit+1);
        return new ParkedPage(rows.stream().limit(limit).map(this::diagnostic).toList(),rows.size()>limit);
    }

    @Transactional
    public RedriveResult redrive(UUID actor,UUID organizationId,UUID eventId,RedriveRequest request) {
        if(request==null||request.requestId()==null||request.expectedOriginalClaimVersion()<0
                ||request.expectedOriginalAttemptCount()<0||request.sourceFingerprint()==null
                ||!request.sourceFingerprint().matches("[0-9a-f]{64}"))
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        String reason=requiredEvidence(request.reason(),500);
        String evidence=requiredEvidence(request.evidenceReference(),256);
        var administrator=access.requireAdmin(actor,organizationId,true);
        byte[] fingerprint=TransferOutboxFingerprint.digest("redrive-request",organizationId,eventId,actor,request.requestId(),
                request.expectedOriginalClaimVersion(),request.expectedOriginalAttemptCount(),request.sourceFingerprint(),reason,evidence);
        redrives.lockRequestScope(organizationId,actor,request.requestId());
        Attempt replay=redrives.findRequest(organizationId,actor,request.requestId());
        if(replay!=null) {
            if(!MessageDigest.isEqual(replay.requestFingerprint(),fingerprint))
                throw new InventoryFailure(InventoryFailure.Kind.IDEMPOTENCY_CONFLICT);
            return result(replay);
        }

        ParkedEvent parked=redrives.lockParked(organizationId,eventId);
        if(parked==null) throw new OrganizationUnavailableException();
        if(parked.claimVersion()!=request.expectedOriginalClaimVersion()
                ||parked.originalAttemptCount()!=request.expectedOriginalAttemptCount()
                ||!MessageDigest.isEqual(parked.sourceFingerprint().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        request.sourceFingerprint().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new InventoryFailure(InventoryFailure.Kind.CONFLICT);
        if(redrives.hasAcknowledgedOrActiveAttempt(eventId)) throw new InventoryFailure(InventoryFailure.Kind.CONFLICT);

        String blockedCode=null;
        try { codec.fromOutbox(parked.event()); }
        catch(TransferEventContractException invalid) { blockedCode=invalid.code(); }
        Attempt created=redrives.insert(UUID.randomUUID(),parked,request.requestId(),actor,administrator.membershipId(),
                fingerprint,reason,evidence,blockedCode);
        RedriveResult response=result(created);
        var details=new java.util.LinkedHashMap<String,Object>();
        details.put("requestId",response.requestId().toString());details.put("eventId",response.eventId().toString());
        details.put("generation",response.generation());details.put("state",response.state());
        details.put("sendAttemptCount",response.sendAttemptCount());details.put("nextAttemptAt",response.nextAttemptAt().toString());
        details.put("publishedAt",null);details.put("blockedAt",response.blockedAt()==null?null:response.blockedAt().toString());
        details.put("lastErrorCode",response.lastErrorCode());details.put("sourceFingerprint",response.sourceFingerprint());
        audit.securityChange(actor,organizationId,"transfer-outbox-event",eventId,"TRANSFER_OUTBOX_REDRIVE_REQUESTED",
                Map.of("outcome",response.state(),"generation",created.generation(),"requestId",request.requestId().toString(),
                        "sourceFingerprint",parked.sourceFingerprint(),"evidenceReference",evidence,"response",details),reason);
        return response;
    }

    private ParkedDiagnostic diagnostic(ParkedEvent row) {
        var event=row.event();
        return new ParkedDiagnostic(event.id(),event.transferId(),event.aggregateVersion(),event.eventType(),
                event.schemaVersion(),event.occurredAt(),row.parkedAt(),row.claimVersion(),row.originalAttemptCount(),
                row.sourceFingerprint(),row.latestGeneration(),row.state(),row.redriveAttemptCount(),
                row.latestAttemptAt(),row.lastErrorCode());
    }
    private static RedriveResult result(Attempt attempt) {
        return new RedriveResult(attempt.requestId(),attempt.eventId(),attempt.generation(),attempt.acceptedState(),0,
                attempt.acceptedAt(),null,attempt.acceptedBlockedAt(),attempt.acceptedErrorCode(),attempt.sourceFingerprintHex());
    }
    private static String requiredEvidence(String value,int maximum) {
        if(value==null) throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        String normalized=value.trim();
        if(normalized.isEmpty()||normalized.length()>maximum||normalized.codePoints().anyMatch(Character::isISOControl))
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        return normalized;
    }
}
