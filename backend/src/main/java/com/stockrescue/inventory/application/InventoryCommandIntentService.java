package com.stockrescue.inventory.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.inventory.application.InventoryCommands.AllocationChange;
import com.stockrescue.inventory.application.InventoryCommands.PositionReference;
import com.stockrescue.inventory.application.InventoryCommands.ReceiptDocumentResult;
import com.stockrescue.inventory.application.InventoryCommands.ReceiveGoodsDocument;
import com.stockrescue.inventory.application.InventoryCommands.ReceiveGoodsDocumentLine;
import com.stockrescue.inventory.application.InventoryCommands.Result;
import com.stockrescue.inventory.application.InventoryCommands.StockChange;
import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.inventory.domain.InventoryFailure.Kind;
import com.stockrescue.inventory.domain.InventoryOperation;
import com.stockrescue.inventory.persistence.InventoryCommandIntentRepository;
import com.stockrescue.inventory.persistence.InventoryCommandIntentRepository.Intent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class InventoryCommandIntentService {
    public enum Operation {
        RECEIPT_DOCUMENT,RESERVE,RELEASE,ADJUST,WRITE_OFF,QUARANTINE,HOLD,RELEASE_HOLD,SALE
    }
    public enum InspectionStatus { PREPARED,COMMITTED_AWAITING_ACKNOWLEDGEMENT,ACKNOWLEDGED,REVIEWED,CONFLICT }
    public record PrepareRequest(UUID prepareId,String operation,JsonNode payload) { }
    public record Summary(UUID id,String operation,String subject,UUID requestId,String state,java.time.Instant preparedAt) { }
    public record Execution(UUID id,String operation,UUID requestId,Object outcome) { }
    public record Inspection(UUID id,String operation,String subject,UUID requestId,String state,java.time.Instant preparedAt,
            java.time.Instant executedAt,InspectionStatus executionStatus,PositionPayload positionPreview,ReceiptPayload receiptDocumentPreview,
            Result committedOutcome,ReceiptDocumentResult committedReceiptOutcome) { }
    public record RecoveryStatus(UUID id,String operation,String subject,UUID requestId,String state,java.time.Instant preparedAt,
            java.time.Instant executedAt,InspectionStatus executionStatus,Result committedOutcome,
            ReceiptDocumentResult committedReceiptOutcome) { }
    public record Page(List<Summary> items,boolean hasMore) { public Page { items=List.copyOf(items); } }
    public record PositionPayload(@NotNull UUID requestId,@NotNull UUID positionId,UUID allocationId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE-1) Long expectedVersion,@NotNull Long quantity,
            @NotBlank @Size(max=500) String reason,@NotBlank @Size(max=256) String evidenceReference) { }
    public record ReceiptPayload(@NotNull UUID requestId,@NotBlank @Size(max=200) String supplierKey,
            @NotBlank @Size(max=32) String documentType,@NotBlank @Size(max=200) String documentReference,
            @NotNull java.time.Instant physicallyReceivedAt,@NotBlank @Size(max=500) String reason,
            @NotBlank @Size(max=256) String evidenceReference,@NotNull @Size(min=1,max=InventoryService.MAX_RECEIPT_DOCUMENT_LINES)
                    List<@Valid ReceiptLinePayload> lines) {
        ReceiveGoodsDocument command() {
            return new ReceiveGoodsDocument(requestId,supplierKey,documentType,documentReference,physicallyReceivedAt,reason,evidenceReference,
                    lines==null?null:lines.stream().map(line->line==null?null:new ReceiveGoodsDocumentLine(line.documentLineReference(),
                            new PositionReference(line.locationId(),line.productId(),line.lotId()),line.quantity()==null?0:line.quantity(),line.expectedVersion())).toList());
        }
    }
    public record ReceiptLinePayload(@NotBlank @Size(max=64) String documentLineReference,@NotNull UUID locationId,
            @NotNull UUID productId,@NotNull UUID lotId,@NotNull @Positive Long quantity,
            @NotNull @Min(0) @Max(Long.MAX_VALUE-1) Long expectedVersion) { }
    private record Validated(Operation operation,String subject,UUID requestId,String payloadJson,byte[] fingerprint,
            PositionPayload position,ReceiveGoodsDocument receipt) { }
    private record DurableState(InspectionStatus status,Result outcome,ReceiptDocumentResult receiptOutcome) { }

    private static final int MAX_PAYLOAD_BYTES=65_536;
    private final AccessPolicy access;
    private final InventoryService inventory;
    private final InventoryCommandIntentRepository repository;
    private final ObjectMapper json;
    private final Clock clock;

    public InventoryCommandIntentService(AccessPolicy access,InventoryService inventory,
            InventoryCommandIntentRepository repository,ObjectMapper json,Clock clock) {
        this.access=access;this.inventory=inventory;this.repository=repository;this.json=json;this.clock=clock;
    }

    @Transactional
    public Summary prepare(UUID actor,UUID organizationId,UUID storeId,PrepareRequest request) {
        if(request==null || request.prepareId()==null || request.operation()==null || request.payload()==null)
            throw new InventoryFailure(Kind.INVALID_REQUEST);
        Operation operation;
        try { operation=Operation.valueOf(request.operation()); }
        catch(IllegalArgumentException invalid) { throw new InventoryFailure(Kind.INVALID_REQUEST); }
        var member=access.requireStore(actor,organizationId,storeId,requiredAction(operation),true);
        Validated validated=validate(operation,request.payload(),organizationId,storeId);
        if(validated.payloadJson().getBytes(StandardCharsets.UTF_8).length>MAX_PAYLOAD_BYTES)
            throw new InventoryFailure(Kind.INVALID_REQUEST);
        var candidate=new Intent(request.prepareId(),organizationId,storeId,actor,member.membershipId(),operation.name(),
                validated.subject(),validated.requestId(),validated.fingerprint(),validated.payloadJson(),"PENDING",clock.instant(),null,null);
        if(repository.insert(candidate)) return repository.byPrepareId(organizationId,actor,request.prepareId()).map(InventoryCommandIntentService::summary)
                .orElseThrow(OrganizationUnavailableException::new);
        Intent previous=repository.byPrepareId(organizationId,actor,request.prepareId())
                .or(()->repository.byRequest(organizationId,actor,operation.name(),validated.requestId())).orElse(null);
        if(previous!=null && sameDraft(previous,candidate)) return summary(previous);
        Intent active=repository.activeBySubject(organizationId,storeId,actor,validated.subject()).orElse(null);
        if(active!=null) throw new InventoryFailure(Kind.CONFLICT);
        throw new InventoryFailure(Kind.IDEMPOTENCY_CONFLICT);
    }

    @Transactional(readOnly=true)
    public Page pending(UUID actor,UUID organizationId,UUID storeId) {
        return pending(actor,organizationId,storeId,0,100,null);
    }

    @Transactional(readOnly=true)
    public Page pending(UUID actor,UUID organizationId,UUID storeId,int offset,int limit,String subject) {
        if(offset<0 || offset>10000 || limit<1 || limit>100
                || subject!=null && !subject.equals("receipt-document")
                        && !subject.matches("^position:[0-9a-fA-F-]{36}$"))
            throw new InventoryFailure(Kind.INVALID_REQUEST);
        access.requireStore(actor,organizationId,storeId,StoreAction.READ_INVENTORY,false);
        var rows=repository.pending(organizationId,storeId,actor,subject,offset,limit+1);
        return new Page(rows.stream().limit(limit).map(InventoryCommandIntentService::summary).toList(),rows.size()>limit);
    }

    @Transactional
    public Inspection inspect(UUID actor,UUID organizationId,UUID storeId,UUID intentId) {
        Intent observed=repository.byId(organizationId,storeId,actor,intentId,false).orElseThrow(OrganizationUnavailableException::new);
        Operation operation=Operation.valueOf(observed.operation());
        access.requireStore(actor,organizationId,storeId,requiredAction(operation),true);
        Intent intent=repository.byId(organizationId,storeId,actor,intentId,true).orElseThrow(OrganizationUnavailableException::new);
        DurableState durable=durableState(actor,organizationId,storeId,intent);

        PositionPayload positionPreview=null;
        ReceiptPayload receiptPreview=null;
        if(operation==Operation.RECEIPT_DOCUMENT) {
            receiptPreview=json.readValue(intent.payload(),ReceiptPayload.class);
        } else {
            positionPreview=json.readValue(intent.payload(),PositionPayload.class);
        }
        return new Inspection(intent.id(),intent.operation(),intent.subject(),intent.requestId(),intent.state(),intent.preparedAt(),
                intent.executedAt(),durable.status(),positionPreview,receiptPreview,durable.outcome(),durable.receiptOutcome());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RecoveryStatus status(UUID actor,UUID organizationId,UUID storeId,UUID intentId) {
        access.requireStore(actor,organizationId,storeId,StoreAction.READ_INVENTORY,false);
        Intent intent=repository.byId(organizationId,storeId,actor,intentId,false).orElseThrow(OrganizationUnavailableException::new);
        DurableState durable=durableState(actor,organizationId,storeId,intent);
        return new RecoveryStatus(intent.id(),intent.operation(),intent.subject(),intent.requestId(),intent.state(),intent.preparedAt(),
                intent.executedAt(),durable.status(),durable.outcome(),durable.receiptOutcome());
    }

    @Transactional
    public Execution execute(UUID actor,UUID organizationId,UUID storeId,UUID intentId) {
        Intent observed=repository.byId(organizationId,storeId,actor,intentId,false).orElseThrow(OrganizationUnavailableException::new);
        Operation operation=Operation.valueOf(observed.operation());
        access.requireStore(actor,organizationId,storeId,requiredAction(operation),true);
        Intent intent=repository.byId(organizationId,storeId,actor,intentId,true).orElseThrow(OrganizationUnavailableException::new);
        if(!intent.state().equals("PENDING")) throw new InventoryFailure(Kind.CONFLICT);
        Object outcome=execute(intent,operation);
        if(intent.executedAt()==null && !repository.markExecuted(organizationId,storeId,actor,intentId))
            throw new InventoryFailure(Kind.CONFLICT);
        return new Execution(intent.id(),intent.operation(),intent.requestId(),outcome);
    }

    @Transactional
    public Summary acknowledge(UUID actor,UUID organizationId,UUID storeId,UUID intentId) {
        access.requireStore(actor,organizationId,storeId,StoreAction.READ_INVENTORY,false);
        Intent intent=repository.byId(organizationId,storeId,actor,intentId,true).orElseThrow(OrganizationUnavailableException::new);
        if(intent.state().equals("ACKNOWLEDGED")) return summary(intent);
        if(intent.state().equals("REVIEWED")) throw new InventoryFailure(Kind.CONFLICT);
        if(intent.executedAt()==null || !repository.hasCommittedCommand(intent)) throw new InventoryFailure(Kind.CONFLICT);
        if(!repository.resolve(organizationId,storeId,actor,intentId,"ACKNOWLEDGED")) throw new InventoryFailure(Kind.CONFLICT);
        return repository.byId(organizationId,storeId,actor,intentId,false).map(InventoryCommandIntentService::summary)
                .orElseThrow(OrganizationUnavailableException::new);
    }

    @Transactional
    public Summary reviewRejected(UUID actor,UUID organizationId,UUID storeId,UUID intentId) {
        Intent observed=repository.byId(organizationId,storeId,actor,intentId,false).orElseThrow(OrganizationUnavailableException::new);
        Operation operation=Operation.valueOf(observed.operation());
        access.requireStore(actor,organizationId,storeId,requiredAction(operation),true);
        Intent intent=repository.byId(organizationId,storeId,actor,intentId,true).orElseThrow(OrganizationUnavailableException::new);
        if(intent.state().equals("REVIEWED")) return summary(intent);
        if(!intent.state().equals("PENDING") || repository.hasCommittedCommand(intent)) throw new InventoryFailure(Kind.CONFLICT);
        if(!repository.resolve(organizationId,storeId,actor,intentId,"REVIEWED")) throw new InventoryFailure(Kind.CONFLICT);
        return repository.byId(organizationId,storeId,actor,intentId,false).map(InventoryCommandIntentService::summary)
                .orElseThrow(OrganizationUnavailableException::new);
    }

    @Scheduled(fixedDelayString="${stockrescue.inventory.command-intents.purge-delay:24h}",initialDelayString="${stockrescue.inventory.command-intents.purge-initial-delay:10m}")
    @Transactional
    public void purgeResolvedIntents() {
        repository.purgeExpiredResolved();
    }

    private Object execute(Intent intent,Operation operation) {
        if(operation==Operation.RECEIPT_DOCUMENT) {
            ReceiptPayload payload=json.readValue(intent.payload(),ReceiptPayload.class);
            return inventory.receiveGoodsDocument(intent.actorId(),intent.organizationId(),intent.storeId(),payload.command()).result();
        }
        PositionPayload payload=json.readValue(intent.payload(),PositionPayload.class);
        var stock=new StockChange(payload.requestId(),payload.positionId(),payload.expectedVersion(),payload.quantity(),payload.reason(),payload.evidenceReference());
        return switch(operation) {
            case RESERVE -> inventory.reserveStock(intent.actorId(),intent.organizationId(),intent.storeId(),stock);
            case RELEASE -> inventory.releaseReservation(intent.actorId(),intent.organizationId(),intent.storeId(),new AllocationChange(stock,payload.allocationId()));
            case ADJUST -> inventory.adjustStock(intent.actorId(),intent.organizationId(),intent.storeId(),stock);
            case WRITE_OFF -> inventory.writeOff(intent.actorId(),intent.organizationId(),intent.storeId(),new AllocationChange(stock,payload.allocationId()));
            case QUARANTINE -> inventory.quarantine(intent.actorId(),intent.organizationId(),intent.storeId(),stock);
            case HOLD -> inventory.hold(intent.actorId(),intent.organizationId(),intent.storeId(),stock);
            case RELEASE_HOLD -> inventory.releaseHold(intent.actorId(),intent.organizationId(),intent.storeId(),new AllocationChange(stock,payload.allocationId()));
            case SALE -> inventory.recordSale(intent.actorId(),intent.organizationId(),intent.storeId(),stock);
            case RECEIPT_DOCUMENT -> throw new IllegalStateException("Receipt operation is handled separately");
        };
    }

    private DurableState durableState(UUID actor,UUID organizationId,UUID storeId,Intent intent) {
        boolean committed=repository.hasCommittedCommand(intent);
        boolean markedExecuted=intent.executedAt()!=null;
        InspectionStatus status;
        if(committed!=markedExecuted) status=InspectionStatus.CONFLICT;
        else if(committed) status=switch(intent.state()) {
            case "PENDING" -> InspectionStatus.COMMITTED_AWAITING_ACKNOWLEDGEMENT;
            case "ACKNOWLEDGED" -> InspectionStatus.ACKNOWLEDGED;
            default -> InspectionStatus.CONFLICT;
        };
        else status=switch(intent.state()) {
            case "PENDING" -> InspectionStatus.PREPARED;
            case "REVIEWED" -> InspectionStatus.REVIEWED;
            default -> InspectionStatus.CONFLICT;
        };

        Result outcome=null;
        ReceiptDocumentResult receiptOutcome=null;
        if(status==InspectionStatus.COMMITTED_AWAITING_ACKNOWLEDGEMENT || status==InspectionStatus.ACKNOWLEDGED) {
            if(intent.operation().equals(Operation.RECEIPT_DOCUMENT.name()))
                receiptOutcome=inventory.receiptDocumentOutcome(actor,organizationId,storeId,intent.requestId());
            else outcome=inventory.commandOutcome(actor,organizationId,storeId,InventoryOperation.valueOf(intent.operation()),intent.requestId());
        }
        return new DurableState(status,outcome,receiptOutcome);
    }

    private Validated validate(Operation operation,JsonNode payload,UUID organizationId,UUID storeId) {
        if(!payload.isObject()) throw new InventoryFailure(Kind.INVALID_REQUEST);
        try {
            if(operation==Operation.RECEIPT_DOCUMENT) {
                requireFields(payload,Set.of("requestId","supplierKey","documentType","documentReference","physicallyReceivedAt","reason","evidenceReference","lines"));
                var receipt=json.treeToValue(payload,ReceiptPayload.class);
                if(receipt.lines()!=null) for(JsonNode line:payload.path("lines"))
                    requireFields(line,Set.of("documentLineReference","locationId","productId","lotId","quantity","expectedVersion"));
                var command=receipt.command();inventory.validateGoodsDocumentIntent(command);
                String canonical=json.writeValueAsString(receipt);
                return new Validated(operation,"receipt-document",receipt.requestId(),canonical,sha256(canonical),null,command);
            }
            requireFields(payload,Set.of("requestId","positionId","allocationId","expectedVersion","quantity","reason","evidenceReference"));
            var position=json.treeToValue(payload,PositionPayload.class);
            if(position.expectedVersion()==null || operation==Operation.RELEASE && position.allocationId()==null
                    || operation==Operation.RELEASE_HOLD && position.allocationId()==null) throw new InventoryFailure(Kind.INVALID_REQUEST);
            InventoryOperation ledgerOperation=InventoryOperation.valueOf(operation.name());
            inventory.validateStockIntent(organizationId,storeId,ledgerOperation,new StockChange(position.requestId(),position.positionId(),position.expectedVersion(),
                    position.quantity()==null?0:position.quantity(),position.reason(),position.evidenceReference()),position.allocationId());
            String canonical=json.writeValueAsString(position);
            return new Validated(operation,"position:"+position.positionId(),position.requestId(),canonical,sha256(canonical),position,null);
        } catch(InventoryFailure invalid) { throw invalid; }
        catch(tools.jackson.core.JacksonException invalid) { throw new InventoryFailure(Kind.INVALID_REQUEST); }
    }

    private static void requireFields(JsonNode node,Set<String> allowed) {
        if(node==null || !node.isObject()) throw new InventoryFailure(Kind.INVALID_REQUEST);
        for(String field:node.propertyNames()) if(!allowed.contains(field)) throw new InventoryFailure(Kind.INVALID_REQUEST);
    }

    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static StoreAction requiredAction(Operation operation) {
        return switch(operation) {
            case RECEIPT_DOCUMENT -> StoreAction.RECEIVE_STOCK;
            case RESERVE -> StoreAction.RESERVE_STOCK;
            case RELEASE -> StoreAction.RELEASE_RESERVATION;
            case ADJUST -> StoreAction.ADJUST_STOCK;
            case WRITE_OFF -> StoreAction.WRITE_OFF_STOCK;
            case QUARANTINE,HOLD,RELEASE_HOLD -> StoreAction.QUARANTINE_STOCK;
            case SALE -> StoreAction.RECORD_SALE;
        };
    }

    private static boolean sameDraft(Intent left,Intent right) {
        return left.organizationId().equals(right.organizationId()) && left.storeId().equals(right.storeId())
                && left.actorId().equals(right.actorId()) && left.operation().equals(right.operation())
                && left.subject().equals(right.subject()) && left.requestId().equals(right.requestId())
                && MessageDigest.isEqual(left.fingerprint(),right.fingerprint());
    }

    private static Summary summary(Intent intent) {
        return new Summary(intent.id(),intent.operation(),intent.subject(),intent.requestId(),intent.state(),intent.preparedAt());
    }

    private static Summary summary(InventoryCommandIntentRepository.Pending pending) {
        return new Summary(pending.id(),pending.operation(),pending.subject(),pending.requestId(),"PENDING",pending.preparedAt());
    }
}
