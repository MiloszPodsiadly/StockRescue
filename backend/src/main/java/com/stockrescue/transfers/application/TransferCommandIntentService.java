package com.stockrescue.transfers.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.inventory.domain.InventoryFailure.Kind;
import com.stockrescue.transfers.application.TransferService.CommandOutcome;
import com.stockrescue.transfers.application.TransferService.Recovery;
import com.stockrescue.transfers.persistence.TransferCommandIntentRepository;
import com.stockrescue.transfers.persistence.TransferCommandIntentRepository.Intent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TransferCommandIntentService {
    public enum InspectionStatus { PREPARED, SUBMITTED_AWAITING_OUTCOME, COMMITTED_AWAITING_ACKNOWLEDGEMENT, ACKNOWLEDGED, REJECTED, CONFLICTED }
    public enum FailureResolution { REJECTED, CONFLICTED }
    public record PrepareRequest(@NotNull UUID prepareId, @NotBlank String operation, UUID transferId, @NotNull JsonNode payload) { }
    public record Summary(UUID id, String operation, UUID transferId, UUID requestId, String state, Instant preparedAt) { }
    public record Page(List<Summary> items) { public Page { items = List.copyOf(items); } }
    public record Inspection(UUID id, String operation, UUID transferId, UUID requestId, String state,
            Instant preparedAt, InspectionStatus executionStatus, JsonNode payload, CommandOutcome outcome) { }

    private static final int MAX_PAYLOAD_BYTES = 65_536;
    private final AccessPolicy access;
    private final TransferService transfers;
    private final TransferCommandIntentRepository repository;
    private final ObjectMapper json;
    private final Clock clock;

    public TransferCommandIntentService(AccessPolicy access, TransferService transfers,
            TransferCommandIntentRepository repository, ObjectMapper json, Clock clock) {
        this.access = access;
        this.transfers = transfers;
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public Summary prepare(UUID actor, UUID organizationId, PrepareRequest request) {
        if (request == null || request.prepareId() == null || request.operation() == null || request.payload() == null
                || !request.payload().isObject() || !TransferService.COMMAND_OPERATIONS.contains(request.operation())
                || (request.operation().equals("CREATE") != (request.transferId() == null)))
            throw new InventoryFailure(Kind.INVALID_REQUEST);
        UUID requestId = payloadRequestId(request.payload());
        String payload = json.writeValueAsString(request.payload());
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) throw new InventoryFailure(Kind.INVALID_REQUEST);
        UUID membershipId = transfers.authorizeCommandIntent(actor, organizationId, request.operation(), request.transferId(), request.payload());
        byte[] fingerprint = fingerprint(organizationId, request.operation(), request.transferId(), requestId, payload);
        var candidate = new Intent(request.prepareId(), organizationId, request.transferId(), actor, membershipId,
                request.operation(), requestId, fingerprint, payload, "PREPARED", clock.instant(), null, null);
        Intent existing = repository.byId(organizationId, actor, request.prepareId(), false)
                .or(() -> repository.byRequest(organizationId, actor, request.operation(), requestId)).orElse(null);
        if (existing != null) {
            if (sameDraft(existing, candidate)) return summary(existing);
            throw new InventoryFailure(Kind.IDEMPOTENCY_CONFLICT);
        }
        if (repository.hasCommittedCommand(organizationId, actor, request.operation(), requestId))
            throw new InventoryFailure(Kind.IDEMPOTENCY_CONFLICT);
        if (repository.insert(candidate)) return repository.byId(organizationId, actor, candidate.id(), false)
                .map(TransferCommandIntentService::summary).orElseThrow(OrganizationUnavailableException::new);

        Intent previous = repository.byId(organizationId, actor, request.prepareId(), false)
                .or(() -> repository.byRequest(organizationId, actor, request.operation(), requestId)).orElse(null);
        if (previous != null && sameDraft(previous, candidate)) return summary(previous);
        if (repository.activeByActor(organizationId, actor).isPresent()) throw new InventoryFailure(Kind.CONFLICT);
        throw new InventoryFailure(Kind.IDEMPOTENCY_CONFLICT);
    }

    @Transactional(readOnly = true)
    public Page pending(UUID actor, UUID organizationId) {
        access.organization(actor, organizationId);
        var pending = repository.pending(organizationId, actor);
        if (pending.size() > 1) throw new IllegalStateException("More than one pending transfer intent exists for this actor");
        List<Summary> items = pending.stream()
                .map(item -> new Summary(item.id(), item.operation(), item.transferId(), item.requestId(), item.state(), item.preparedAt())).toList();
        return new Page(items);
    }

    @Transactional
    public Summary markSubmitted(UUID actor, UUID organizationId, UUID intentId) {
        Intent intent = repository.byId(organizationId, actor, intentId, false).orElseThrow(OrganizationUnavailableException::new);
        JsonNode payload = json.readTree(intent.payload());
        UUID currentMembership = transfers.authorizeCommandIntent(actor, organizationId, intent.operation(), intent.transferId(), payload);
        if (!currentMembership.equals(intent.membershipId())) throw new InventoryFailure(Kind.CONFLICT);
        Intent locked = repository.byId(organizationId, actor, intentId, true).orElseThrow(OrganizationUnavailableException::new);
        if (locked.state().equals("SUBMITTED")) return summary(locked);
        if (!locked.state().equals("PREPARED") || !repository.markSubmitted(organizationId, actor, intentId))
            throw new InventoryFailure(Kind.CONFLICT);
        return repository.byId(organizationId, actor, intentId, false).map(TransferCommandIntentService::summary)
                .orElseThrow(OrganizationUnavailableException::new);
    }

    @Transactional(readOnly = true)
    public Inspection inspect(UUID actor, UUID organizationId, UUID intentId) {
        Intent intent = repository.byId(organizationId, actor, intentId, false).orElseThrow(OrganizationUnavailableException::new);
        JsonNode payload = json.readTree(intent.payload());
        transfers.authorizeCommandIntentRead(actor, organizationId, intent.operation(), intent.transferId(), payload);
        CommandOutcome outcome = committedOutcome(actor, organizationId, intent);
        InspectionStatus executionStatus = intent.state().equals("ACKNOWLEDGED") ? InspectionStatus.ACKNOWLEDGED
                : intent.state().equals("REJECTED") ? InspectionStatus.REJECTED
                : intent.state().equals("CONFLICTED") ? InspectionStatus.CONFLICTED
                : outcome != null ? InspectionStatus.COMMITTED_AWAITING_ACKNOWLEDGEMENT
                : intent.state().equals("SUBMITTED") ? InspectionStatus.SUBMITTED_AWAITING_OUTCOME : InspectionStatus.PREPARED;
        return new Inspection(intent.id(), intent.operation(), intent.transferId(), intent.requestId(), intent.state(),
                intent.preparedAt(), executionStatus, payload, outcome);
    }

    @Transactional
    public Summary acknowledge(UUID actor, UUID organizationId, UUID intentId) {
        Intent intent = repository.byId(organizationId, actor, intentId, true).orElseThrow(OrganizationUnavailableException::new);
        JsonNode payload = json.readTree(intent.payload());
        transfers.authorizeCommandIntentRead(actor, organizationId, intent.operation(), intent.transferId(), payload);
        if (intent.state().equals("ACKNOWLEDGED")) return summary(intent);
        if (intent.state().equals("REJECTED") || intent.state().equals("CONFLICTED")) throw new InventoryFailure(Kind.CONFLICT);
        if (!intent.state().equals("SUBMITTED")) throw new InventoryFailure(Kind.CONFLICT);
        if (committedOutcome(actor, organizationId, intent) == null || !repository.acknowledge(organizationId, actor, intentId))
            throw new InventoryFailure(Kind.CONFLICT);
        return repository.byId(organizationId, actor, intentId, false).map(TransferCommandIntentService::summary)
                .orElseThrow(OrganizationUnavailableException::new);
    }

    @Transactional
    public Summary resolveFailure(UUID actor, UUID organizationId, UUID intentId, FailureResolution resolution) {
        if (resolution == null) throw new InventoryFailure(Kind.INVALID_REQUEST);
        Intent intent = repository.byId(organizationId, actor, intentId, true).orElseThrow(OrganizationUnavailableException::new);
        transfers.authorizeCommandIntentRead(actor, organizationId, intent.operation(), intent.transferId(), json.readTree(intent.payload()));
        if (intent.state().equals(resolution.name())) return summary(intent);
        if ((!intent.state().equals("PREPARED") && !intent.state().equals("SUBMITTED")) || repository.hasCommittedCommand(intent))
            throw new InventoryFailure(Kind.CONFLICT);
        if (!repository.resolveFailure(organizationId, actor, intentId, resolution.name())) throw new InventoryFailure(Kind.CONFLICT);
        return repository.byId(organizationId, actor, intentId, false).map(TransferCommandIntentService::summary)
                .orElseThrow(OrganizationUnavailableException::new);
    }

    @Scheduled(fixedDelayString = "${stockrescue.transfer.command-intents.purge-delay:24h}",
            initialDelayString = "${stockrescue.transfer.command-intents.purge-initial-delay:10m}")
    @Transactional
    public void purgeExpiredIntents() {
        repository.expirePendingWithoutOutcome();
        repository.purgeExpiredResolved();
    }

    private CommandOutcome committedOutcome(UUID actor, UUID organizationId, Intent intent) {
        if (!repository.hasCommittedCommand(intent)) return null;
        Recovery recovery = transfers.recover(actor, organizationId, intent.operation(), intent.requestId());
        if (!intent.operation().equals(recovery.operation())
                || intent.transferId() != null && !intent.transferId().equals(recovery.outcome().transferId()))
            throw new InventoryFailure(Kind.IDEMPOTENCY_CONFLICT);
        return recovery.outcome();
    }

    private static UUID payloadRequestId(JsonNode payload) {
        String value = payload.path("requestId").asString();
        if (value == null) throw new InventoryFailure(Kind.INVALID_REQUEST);
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { throw new InventoryFailure(Kind.INVALID_REQUEST); }
    }

    private static boolean sameDraft(Intent previous, Intent candidate) {
        return (previous.state().equals("PREPARED") || previous.state().equals("SUBMITTED"))
                && previous.operation().equals(candidate.operation())
                && java.util.Objects.equals(previous.transferId(), candidate.transferId())
                && previous.requestId().equals(candidate.requestId())
                && MessageDigest.isEqual(previous.fingerprint(), candidate.fingerprint());
    }

    private static byte[] fingerprint(UUID organizationId, String operation, UUID transferId, UUID requestId, String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String identity = organizationId + "|" + operation + "|" + (transferId == null ? "" : transferId) + "|" + requestId + "|";
            digest.update(identity.getBytes(StandardCharsets.UTF_8));
            return digest.digest(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static Summary summary(Intent intent) {
        return new Summary(intent.id(), intent.operation(), intent.transferId(), intent.requestId(), intent.state(), intent.preparedAt());
    }
}
