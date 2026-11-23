package com.stockrescue.inventory.application;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.catalog.application.FoodSafetyEvaluation;
import com.stockrescue.catalog.application.FoodSafetyEvaluationService;
import com.stockrescue.inventory.application.InventoryCommands.AllocationChange;
import com.stockrescue.inventory.application.InventoryCommands.PositionReference;
import com.stockrescue.inventory.application.InventoryCommands.ReceiveGoods;
import com.stockrescue.inventory.application.InventoryCommands.ReceiveGoodsDocument;
import com.stockrescue.inventory.application.InventoryCommands.ReceiveGoodsDocumentLine;
import com.stockrescue.inventory.application.InventoryCommands.ReceiptDocumentLineResult;
import com.stockrescue.inventory.application.InventoryCommands.ReceiptDocumentResult;
import com.stockrescue.inventory.application.InventoryCommands.ReceiptDocumentSubmission;
import com.stockrescue.inventory.application.InventoryCommands.Result;
import com.stockrescue.inventory.application.InventoryCommands.StockChange;
import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.inventory.domain.InventoryFailure.Kind;
import com.stockrescue.inventory.domain.StockBuckets;
import com.stockrescue.inventory.domain.StockBuckets.Delta;
import com.stockrescue.inventory.domain.StockPosition;
import com.stockrescue.inventory.domain.StockPosition.Trust;
import com.stockrescue.inventory.persistence.InventoryRepository;
import com.stockrescue.inventory.persistence.InventoryRepository.Allocation;
import com.stockrescue.inventory.persistence.InventoryRepository.CommandReceipt;
import com.stockrescue.inventory.persistence.InventoryRepository.Movement;
import com.stockrescue.inventory.persistence.InventoryRepository.PositionIdentity;
import com.stockrescue.inventory.persistence.InventoryRepository.ReceiptLine;
import com.stockrescue.inventory.domain.InventoryOperation;
import com.stockrescue.platform.audit.AuditWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class InventoryService {
    /** Kept in sync with the public receipt-document schema's maxItems bound. */
    public static final int MAX_RECEIPT_DOCUMENT_LINES=50;
    public static final int MAX_TRANSFER_LINES=50;
    public record Page<T>(List<T> items,boolean hasMore) { public Page { items=List.copyOf(items); } }
    private record NormalizedReceiptLine(String documentLineReference,PositionReference position,long quantity,long expectedVersion) { }
    private record NormalizedReceiptDocument(UUID requestId,String supplierKey,String documentType,String documentReference,
            Instant physicallyReceivedAt,String reason,String evidenceReference,List<NormalizedReceiptLine> lines) { }
    private record PreparedReceiptPosition(List<NormalizedReceiptLine> sourceLines,StockPosition before,long quantity,
            Delta delta,StockBuckets buckets,long version,UUID allocationId) {
        private PreparedReceiptPosition { sourceLines=List.copyOf(sourceLines); }
    }
    private record PreparedTransferStockLine(UUID lineId,StockPosition before,long quantity,Delta delta,
            StockBuckets buckets,long version,UUID allocationId,Allocation allocation) { }
    public record TransferReservationLine(UUID lineId,UUID positionId,long expectedVersion,long quantity) { }
    public record TransferReleaseLine(UUID lineId,UUID positionId,UUID allocationId,long expectedQuantity) { }
    public record TransferDispatchLine(UUID lineId,UUID positionId,UUID allocationId,long expectedVersion,long quantity) { }
    public record TransferReceiptLine(UUID lineId,UUID locationId,UUID productId,UUID lotId,
            long expectedVersion,long quantity) { }
    public record TransferReturnLine(UUID lineId,UUID positionId,long expectedVersion,long quantity) { }
    public record TransferSettlementPositionRequest(UUID lineId,UUID positionId,PositionIdentity identity) { }
    public record TransferSettlementPositionVersion(UUID lineId,UUID positionId,long version) { }
    public record TransferStockLineResult(UUID lineId,UUID positionId,UUID allocationId,long quantity,
            long positionVersion,StockBuckets buckets) { }
    public record TransferStockResult(UUID commandId,UUID movementId,List<TransferStockLineResult> lines) {
        public TransferStockResult { lines=List.copyOf(lines); }
    }
    private final AccessPolicy access;
    private final InventoryRepository inventory;
    private final FoodSafetyEvaluationService safety;
    private final AuditWriter audit;
    private final ObjectMapper json;
    private final Clock clock;

    public InventoryService(AccessPolicy access,InventoryRepository inventory,FoodSafetyEvaluationService safety,
            AuditWriter audit,ObjectMapper json,Clock clock) {
        this.access=access;this.inventory=inventory;this.safety=safety;this.audit=audit;this.json=json;this.clock=clock;
    }

    @Transactional
    public Result receiveGoods(UUID actor,UUID org,UUID store,ReceiveGoods request) {
        var member=access.requireStore(actor,org,store,StoreAction.RECEIVE_STOCK,true);
        if(request==null || request.requestId()==null || request.position()==null || request.position().locationId()==null
                || request.position().productId()==null || request.position().lotId()==null || request.quantity()<=0
                || request.expectedVersion()!=null && (request.expectedVersion()<0 || request.expectedVersion()==Long.MAX_VALUE)
                || request.physicallyReceivedAt()==null || request.physicallyReceivedAt().isAfter(clock.instant())
                || request.physicallyReceivedAt().isBefore(Instant.parse("0001-01-01T00:00:00Z"))) throw failure(Kind.INVALID_REQUEST);
        var received=request.physicallyReceivedAt().truncatedTo(ChronoUnit.MICROS);
        var supplier=text(request.supplierKey(),200);var documentType=text(request.documentType(),32);
        var document=text(request.documentReference(),200);var line=text(request.documentLineReference(),64);
        var reason=text(request.reason(),500);var evidence=text(request.evidenceReference(),256);
        var ref=request.position();
        var digest=InventoryFingerprint.of(store,InventoryOperation.RECEIVE,ref.locationId(),ref.productId(),ref.lotId(),request.quantity(),supplier,documentType,document,line,received,reason,evidence,request.expectedVersion());
        var replay=replay(actor,org,store,InventoryOperation.RECEIVE,request.requestId(),digest);
        if(replay!=null) return replay;
        inventory.lockStoreForWrite(org,store);
        inventory.lockReceiptDocument(org,supplier,documentType,document);
        if(inventory.receiptDocumentExists(org,supplier,documentType,document)) throw failure(Kind.DOCUMENT_ALREADY_RECEIVED);
        inventory.lockActiveContext(org,store,ref.locationId(),ref.productId(),ref.lotId());
        var before=inventory.positionByIdentity(org,store,ref.locationId(),ref.lotId())
                .orElseGet(()->inventory.createPosition(UUID.randomUUID(),org,store,ref.locationId(),ref.productId(),ref.lotId()));
        before=inventory.position(org,store,before.id(),true).orElseThrow(OrganizationUnavailableException::new);
        if(request.expectedVersion()!=null && request.expectedVersion()!=before.version()) throw failure(Kind.CONFLICT);
        if(before.trust()==Trust.FROZEN) throw failure(Kind.POSITION_FROZEN);
        boolean quarantine=!eligible(actor,org,store,before);
        UUID allocation=quarantine?UUID.randomUUID():null,receipt=UUID.randomUUID();
        var result=post(actor,org,store,member.membershipId(),InventoryOperation.RECEIVE,request.requestId(),digest,before,
                new Delta(request.quantity(),0,quarantine?request.quantity():0,0),reason,evidence,allocation,receipt);
        if(quarantine) inventory.openAllocation(org,store,before.id(),allocation,"QUARANTINED",actor,member.membershipId(),request.quantity(),result.movementId());
        inventory.recordReceipt(receipt,org,store,result.movementId(),before.id(),supplier,documentType,document,line,received,request.quantity(),quarantine);
        audit(actor,org,InventoryOperation.RECEIVE,before,result,reason,evidence);
        return result;
    }

    @Transactional
    public ReceiptDocumentSubmission receiveGoodsDocument(UUID actor,UUID org,UUID store,ReceiveGoodsDocument request) {
        var member=access.requireStore(actor,org,store,StoreAction.RECEIVE_STOCK,true);
        var normalized=normalizeDocument(request);
        var digest=documentFingerprint(store,normalized);
        var previous=replayDocument(actor,org,store,normalized.requestId(),digest);
        if(previous!=null) return new ReceiptDocumentSubmission(previous,false);

        inventory.lockStoreForWrite(org,store);
        inventory.lockReceiptDocument(org,normalized.supplierKey(),normalized.documentType(),normalized.documentReference());
        if(inventory.receiptDocumentExists(org,normalized.supplierKey(),normalized.documentType(),normalized.documentReference()))
            throw failure(Kind.DOCUMENT_ALREADY_RECEIVED);

        var groupedLines=new LinkedHashMap<PositionIdentity,List<NormalizedReceiptLine>>();
        for(var line:normalized.lines()) {
            var identity=new PositionIdentity(line.position().locationId(),line.position().productId(),line.position().lotId());
            groupedLines.computeIfAbsent(identity,ignored->new ArrayList<>()).add(line);
        }
        var identities=List.copyOf(groupedLines.keySet());
        inventory.lockActiveContexts(org,store,identities);

        var positions=new LinkedHashMap<PositionIdentity,StockPosition>();
        for(var identity:identities.stream().sorted(Comparator.comparing(PositionIdentity::productId)
                .thenComparing(PositionIdentity::lotId).thenComparing(PositionIdentity::locationId)).toList()) {
            positions.computeIfAbsent(identity,key->inventory.positionByIdentity(org,store,key.locationId(),key.lotId())
                    .orElseGet(()->inventory.createPosition(UUID.randomUUID(),org,store,key.locationId(),key.productId(),key.lotId())));
        }
        var locked=inventory.lockPositions(org,store,positions.values().stream().map(StockPosition::id).toList());
        var lockedById=locked.stream().collect(java.util.stream.Collectors.toMap(StockPosition::id,position->position));
        var prepared=new ArrayList<PreparedReceiptPosition>(groupedLines.size());
        for(var entry:groupedLines.entrySet()) {
            var identity=entry.getKey();
            var candidate=positions.get(identity);
            var before=lockedById.get(candidate.id());
            if(before==null) throw new OrganizationUnavailableException();
            var sourceLines=entry.getValue();
            long expectedVersion=sourceLines.getFirst().expectedVersion();
            if(sourceLines.stream().anyMatch(line->line.expectedVersion()!=expectedVersion) || expectedVersion!=before.version())
                throw failure(Kind.CONFLICT);
            long quantity=0;
            try {
                for(var line:sourceLines) quantity=Math.addExact(quantity,line.quantity());
            } catch(ArithmeticException overflow) {
                throw failure(Kind.INVALID_REQUEST);
            }
            if(before.trust()==Trust.FROZEN) throw failure(Kind.POSITION_FROZEN);
            boolean quarantine=!eligible(actor,org,store,before);
            var delta=new Delta(quantity,0,quarantine?quantity:0,0);
            StockBuckets buckets;long version;
            try {
                buckets=before.buckets().apply(delta);
                version=Math.incrementExact(before.version());
            } catch(ArithmeticException | IllegalArgumentException invalid) {
                throw failure(Kind.INVALID_REQUEST);
            }
            prepared.add(new PreparedReceiptPosition(sourceLines,before,quantity,delta,buckets,version,quarantine?UUID.randomUUID():null));
        }

        UUID movementId=UUID.randomUUID(),receiptId=UUID.randomUUID();
        var resultLines=prepared.stream().flatMap(position->position.sourceLines().stream().map(line->
                new ReceiptDocumentLineResult(line.documentLineReference(),line.quantity(),position.before().id(),position.version(),
                        position.buckets(),position.buckets().available(),position.allocationId())))
                .sorted(Comparator.comparing(ReceiptDocumentLineResult::documentLineReference)).toList();
        var result=new ReceiptDocumentResult(receiptId,movementId,resultLines);
        var command=new CommandReceipt(UUID.randomUUID(),org,store,actor,member.membershipId(),InventoryOperation.RECEIVE,
                normalized.requestId(),digest,json.writeValueAsString(result));
        if(!inventory.recordCommand(command)) throw failure(Kind.IDEMPOTENCY_CONFLICT);
        inventory.recordMovement(movementId,command,normalized.reason(),normalized.evidenceReference());

        for(var position:prepared) {
            var after=inventory.postLine(org,store,movementId,position.before().id(),position.before().version(),position.delta(),Trust.UNVERIFIED);
            if(position.allocationId()!=null) inventory.openAllocation(org,store,after.id(),position.allocationId(),"QUARANTINED",actor,
                    member.membershipId(),position.quantity(),movementId);
            audit(actor,org,InventoryOperation.RECEIVE,position.before(),new Result(movementId,after.id(),after.version(),after.buckets(),
                    position.allocationId(),receiptId),normalized.reason(),normalized.evidenceReference());
        }
        inventory.recordReceiptDocument(receiptId,org,store,movementId,normalized.supplierKey(),normalized.documentType(),
                normalized.documentReference(),normalized.physicallyReceivedAt(),prepared.stream().flatMap(position->position.sourceLines().stream()
                        .map(line->new ReceiptLine(position.before().id(),line.documentLineReference(),line.quantity(),position.allocationId()!=null)))
                        .sorted(Comparator.comparing(ReceiptLine::documentLineReference)).toList());
        return new ReceiptDocumentSubmission(result,true);
    }

    private NormalizedReceiptDocument normalizeDocument(ReceiveGoodsDocument request) {
        if(request==null || request.requestId()==null || request.physicallyReceivedAt()==null
                || request.physicallyReceivedAt().isAfter(clock.instant())
                || request.physicallyReceivedAt().isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || request.lines()==null || request.lines().isEmpty() || request.lines().size()>MAX_RECEIPT_DOCUMENT_LINES)
            throw failure(Kind.INVALID_REQUEST);
        var supplier=text(request.supplierKey(),200);var documentType=text(request.documentType(),32);
        var document=text(request.documentReference(),200);var reason=text(request.reason(),500);
        var evidence=text(request.evidenceReference(),256);
        var received=request.physicallyReceivedAt().truncatedTo(ChronoUnit.MICROS);
        var lineReferences=new HashSet<String>();
        var lines=new ArrayList<NormalizedReceiptLine>(request.lines().size());
        for(ReceiveGoodsDocumentLine line:request.lines()) {
            if(line==null || line.position()==null || line.position().locationId()==null || line.position().productId()==null
                    || line.position().lotId()==null || line.quantity()<=0 || line.expectedVersion()==null
                    || line.expectedVersion()<0 || line.expectedVersion()==Long.MAX_VALUE) throw failure(Kind.INVALID_REQUEST);
            String lineReference=text(line.documentLineReference(),64);
            if(!lineReferences.add(lineReference)) throw failure(Kind.INVALID_REQUEST);
            lines.add(new NormalizedReceiptLine(lineReference,line.position(),line.quantity(),line.expectedVersion()));
        }
        lines.sort(Comparator.comparing(NormalizedReceiptLine::documentLineReference));
        return new NormalizedReceiptDocument(request.requestId(),supplier,documentType,document,received,reason,evidence,List.copyOf(lines));
    }

    void validateGoodsDocumentIntent(ReceiveGoodsDocument request) { normalizeDocument(request); }

    private byte[] documentFingerprint(UUID store,NormalizedReceiptDocument document) {
        var values=new ArrayList<Object>();
        values.add(store);values.add(InventoryOperation.RECEIVE);values.add("GOODS_RECEIPT_DOCUMENT_V1");
        values.add(document.supplierKey());values.add(document.documentType());values.add(document.documentReference());
        values.add(document.physicallyReceivedAt());values.add(document.reason());values.add(document.evidenceReference());
        for(var line:document.lines()) {
            values.add(line.documentLineReference());values.add(line.position().locationId());values.add(line.position().productId());
            values.add(line.position().lotId());values.add(line.quantity());values.add(line.expectedVersion());
        }
        return InventoryFingerprint.of(values.toArray());
    }

    private ReceiptDocumentResult replayDocument(UUID actor,UUID org,UUID store,UUID request,byte[] digest) {
        inventory.lockRequest(org,actor,InventoryOperation.RECEIVE,request);
        var previous=inventory.command(org,actor,InventoryOperation.RECEIVE,request);
        if(previous.isEmpty()) return null;
        var recorded=previous.orElseThrow();
        if(!recorded.storeId().equals(store) || !MessageDigest.isEqual(recorded.payloadDigest(),digest))
            throw failure(Kind.IDEMPOTENCY_CONFLICT);
        return receiptDocumentResult(recorded.outcome(),org,store);
    }

    @Transactional public Result reserveStock(UUID actor,UUID org,UUID store,StockChange request) { return change(actor,org,store,InventoryOperation.RESERVE,request,null); }
    @Transactional public Result releaseReservation(UUID actor,UUID org,UUID store,AllocationChange request) {
        if(request==null) throw failure(Kind.INVALID_REQUEST);
        return change(actor,org,store,InventoryOperation.RELEASE,request.stock(),request.allocationId());
    }
    @Transactional public Result adjustStock(UUID actor,UUID org,UUID store,StockChange request) { return change(actor,org,store,InventoryOperation.ADJUST,request,null); }
    @Transactional public Result writeOff(UUID actor,UUID org,UUID store,AllocationChange request) {
        if(request==null) throw failure(Kind.INVALID_REQUEST);
        return change(actor,org,store,InventoryOperation.WRITE_OFF,request.stock(),request.allocationId());
    }
    @Transactional public Result quarantine(UUID actor,UUID org,UUID store,StockChange request) { return change(actor,org,store,InventoryOperation.QUARANTINE,request,null); }
    @Transactional public Result hold(UUID actor,UUID org,UUID store,StockChange request) { return change(actor,org,store,InventoryOperation.HOLD,request,null); }
    @Transactional public Result releaseHold(UUID actor,UUID org,UUID store,AllocationChange request) {
        if(request==null) throw failure(Kind.INVALID_REQUEST);
        return change(actor,org,store,InventoryOperation.RELEASE_HOLD,request.stock(),request.allocationId());
    }
    @Transactional public Result recordSale(UUID actor,UUID org,UUID store,StockChange request) { return change(actor,org,store,InventoryOperation.SALE,request,null); }

    @Transactional
    public TransferStockResult reserveForTransfer(UUID actor,UUID org,UUID store,UUID requestId,UUID transferId,
            String reason,String evidenceReference,List<TransferReservationLine> requestedLines) {
        if(requestId==null || transferId==null) throw failure(Kind.INVALID_REQUEST);
        var member=access.requireTransferStore(actor,org,store,StoreAction.RESERVE_STOCK,true);
        var lines=normalizeTransferReservations(requestedLines);
        reason=text(reason,500);evidenceReference=text(evidenceReference,256);
        byte[] digest=transferStockFingerprint(store,transferId,lines,reason,evidenceReference);
        inventory.lockRequest(org,actor,InventoryOperation.TRANSFER_RESERVE,requestId);
        var replay=transferStockReplay(actor,org,store,InventoryOperation.TRANSFER_RESERVE,requestId,digest);
        if(replay!=null) return replay;

        inventory.lockStoreForWrite(org,store);
        var observed=lines.stream().map(line->inventory.position(org,store,line.positionId(),false)
                .orElseThrow(OrganizationUnavailableException::new)).toList();
        inventory.lockActiveContexts(org,store,observed.stream().map(position ->
                new PositionIdentity(position.locationId(),position.productId(),position.lotId())).toList());
        var locked=inventory.lockPositions(org,store,lines.stream().map(TransferReservationLine::positionId).toList());
        var byId=locked.stream().collect(java.util.stream.Collectors.toMap(StockPosition::id,position->position));
        var prepared=new ArrayList<PreparedTransferStockLine>(lines.size());
        for(var line:lines) {
            var before=byId.get(line.positionId());
            if(before==null) throw new OrganizationUnavailableException();
            if(before.version()!=line.expectedVersion()) throw failure(Kind.CONFLICT);
            if(before.trust()==Trust.FROZEN) throw failure(Kind.POSITION_FROZEN);
            if(before.initialization()!=StockPosition.Initialization.INITIALIZED) throw failure(Kind.NOT_INITIALIZED);
            if(line.quantity()>before.buckets().available()) throw failure(Kind.INSUFFICIENT_STOCK);
            if(!eligible(actor,org,store,before,FoodSafetyEvaluation.Operation.TRANSFER))
                throw failure(Kind.LOT_NOT_ELIGIBLE);
            StockBuckets buckets;long version;
            try {
                buckets=before.buckets().apply(new Delta(0,line.quantity(),0,0));
                version=Math.incrementExact(before.version());
            } catch(ArithmeticException | IllegalArgumentException invalid) {
                throw failure(Kind.CONFLICT);
            }
            prepared.add(new PreparedTransferStockLine(line.lineId(),before,line.quantity(),
                    new Delta(0,line.quantity(),0,0),buckets,version,UUID.randomUUID(),null));
        }
        return postTransferStock(actor,org,store,member.membershipId(),requestId,digest,reason,evidenceReference,
                InventoryOperation.TRANSFER_RESERVE,prepared);
    }

    @Transactional
    public TransferStockResult releaseForTransfer(UUID actor,UUID org,UUID store,UUID requestId,UUID transferId,
            String reason,String evidenceReference,List<TransferReleaseLine> requestedLines) {
        if(requestId==null || transferId==null) throw failure(Kind.INVALID_REQUEST);
        var member=access.requireTransferStore(actor,org,store,StoreAction.RELEASE_RESERVATION,true);
        var lines=normalizeTransferReleases(requestedLines);
        reason=text(reason,500);evidenceReference=text(evidenceReference,256);
        byte[] digest=transferReleaseFingerprint(store,transferId,lines,reason,evidenceReference);
        inventory.lockRequest(org,actor,InventoryOperation.TRANSFER_RELEASE,requestId);
        var replay=transferStockReplay(actor,org,store,InventoryOperation.TRANSFER_RELEASE,requestId,digest);
        if(replay!=null) return replay;

        inventory.lockStoreForWrite(org,store);
        var locked=inventory.lockPositions(org,store,lines.stream().map(TransferReleaseLine::positionId).toList());
        var byId=locked.stream().collect(java.util.stream.Collectors.toMap(StockPosition::id,position->position));
        var prepared=new ArrayList<PreparedTransferStockLine>(lines.size());
        for(var line:lines) {
            var before=byId.get(line.positionId());
            if(before==null) throw new OrganizationUnavailableException();
            if(before.trust()==Trust.FROZEN) throw failure(Kind.POSITION_FROZEN);
            if(before.initialization()!=StockPosition.Initialization.INITIALIZED) throw failure(Kind.NOT_INITIALIZED);
            if(!inventory.transferAllocationMatches(org,store,transferId,line.lineId(),line.positionId(),line.allocationId()))
                throw failure(Kind.ALLOCATION_UNAVAILABLE);
            var allocation=inventory.allocation(org,store,line.positionId(),line.allocationId())
                    .orElseThrow(OrganizationUnavailableException::new);
            if(!allocation.bucket().equals("RESERVED") || !allocation.state().equals("ACTIVE")
                    || allocation.remaining()!=line.expectedQuantity()) throw failure(Kind.ALLOCATION_UNAVAILABLE);
            StockBuckets buckets;long version;
            try {
                buckets=before.buckets().apply(new Delta(0,-line.expectedQuantity(),0,0));
                version=Math.incrementExact(before.version());
            } catch(ArithmeticException | IllegalArgumentException invalid) {
                throw failure(Kind.CONFLICT);
            }
            prepared.add(new PreparedTransferStockLine(line.lineId(),before,line.expectedQuantity(),
                    new Delta(0,-line.expectedQuantity(),0,0),buckets,version,line.allocationId(),allocation));
        }
        return postTransferStock(actor,org,store,member.membershipId(),requestId,digest,reason,evidenceReference,
                InventoryOperation.TRANSFER_RELEASE,prepared);
    }

    @Transactional
    public long validateTransferPick(UUID actor,UUID org,UUID store,UUID transferId,UUID lineId,
            UUID positionId,UUID allocationId,long expectedVersion,long quantity) {
        access.requireTransferStore(actor,org,store,StoreAction.PICK_TRANSFER,true);
        if(transferId==null || lineId==null || positionId==null || allocationId==null
                || expectedVersion<0 || quantity<=0) throw failure(Kind.INVALID_REQUEST);
        inventory.lockStoreForWrite(org,store);
        var observed=inventory.position(org,store,positionId,false).orElseThrow(OrganizationUnavailableException::new);
        inventory.lockActiveContext(org,store,observed.locationId(),observed.productId(),observed.lotId());
        var before=inventory.position(org,store,positionId,true).orElseThrow(OrganizationUnavailableException::new);
        validateTransferReservation(actor,org,store,transferId,lineId,allocationId,expectedVersion,quantity,before);
        return before.version();
    }

    @Transactional
    public TransferStockResult dispatchForTransfer(UUID actor,UUID org,UUID store,UUID requestId,UUID transferId,
            String reason,String evidenceReference,List<TransferDispatchLine> requestedLines) {
        if(requestId==null || transferId==null) throw failure(Kind.INVALID_REQUEST);
        var member=access.requireTransferStore(actor,org,store,StoreAction.DISPATCH_TRANSFER,true);
        var lines=normalizeTransferDispatches(requestedLines);
        reason=text(reason,500);evidenceReference=text(evidenceReference,256);
        byte[] digest=transferDispatchFingerprint(store,transferId,lines,reason,evidenceReference);
        inventory.lockRequest(org,actor,InventoryOperation.TRANSFER_OUT,requestId);
        var replay=transferStockReplay(actor,org,store,InventoryOperation.TRANSFER_OUT,requestId,digest);
        if(replay!=null) return replay;

        inventory.lockStoreForWrite(org,store);
        var observed=lines.stream().map(line->inventory.position(org,store,line.positionId(),false)
                .orElseThrow(OrganizationUnavailableException::new)).toList();
        inventory.lockActiveContexts(org,store,observed.stream().map(position ->
                new PositionIdentity(position.locationId(),position.productId(),position.lotId())).toList());
        var locked=inventory.lockPositions(org,store,lines.stream().map(TransferDispatchLine::positionId).toList());
        var byId=locked.stream().collect(java.util.stream.Collectors.toMap(StockPosition::id,position->position));
        var prepared=new ArrayList<PreparedTransferStockLine>(lines.size());
        for(var line:lines) {
            var before=byId.get(line.positionId());
            if(before==null) throw new OrganizationUnavailableException();
            validateTransferReservation(actor,org,store,transferId,line.lineId(),line.allocationId(),
                    line.expectedVersion(),line.quantity(),before);
            StockBuckets buckets;long version;
            try {
                buckets=before.buckets().apply(new Delta(-line.quantity(),-line.quantity(),0,0));
                version=Math.incrementExact(before.version());
            } catch(ArithmeticException | IllegalArgumentException invalid) {
                throw failure(Kind.CONFLICT);
            }
            var allocation=inventory.allocation(org,store,line.positionId(),line.allocationId())
                    .orElseThrow(OrganizationUnavailableException::new);
            prepared.add(new PreparedTransferStockLine(line.lineId(),before,line.quantity(),
                    new Delta(-line.quantity(),-line.quantity(),0,0),buckets,version,line.allocationId(),allocation));
        }
        return postTransferStock(actor,org,store,member.membershipId(),requestId,digest,reason,evidenceReference,
                InventoryOperation.TRANSFER_OUT,prepared);
    }

    @Transactional
    public TransferStockResult receiveForTransfer(UUID actor,UUID org,UUID store,UUID requestId,UUID transferId,
            Instant physicallyReceivedAt,String reason,String evidenceReference,List<TransferReceiptLine> requestedLines) {
        if(requestId==null || transferId==null || physicallyReceivedAt==null
                || physicallyReceivedAt.isAfter(clock.instant())
                || physicallyReceivedAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))) throw failure(Kind.INVALID_REQUEST);
        var member=access.requireTransferSettlementStore(actor,org,store,StoreAction.RECEIVE_STOCK);
        var lines=normalizeTransferReceipts(requestedLines);
        var received=physicallyReceivedAt.truncatedTo(ChronoUnit.MICROS);
        reason=text(reason,500);evidenceReference=text(evidenceReference,256);
        byte[] digest=transferSettlementFingerprint(store,InventoryOperation.TRANSFER_IN,transferId,lines,reason,
                evidenceReference,received);
        inventory.lockRequest(org,actor,InventoryOperation.TRANSFER_IN,requestId);
        var replay=transferStockReplay(actor,org,store,InventoryOperation.TRANSFER_IN,requestId,digest);
        if(replay!=null) return replay;

        inventory.lockStoreForWrite(org,store);
        var grouped=new java.util.TreeMap<PositionIdentity,List<TransferReceiptLine>>(Comparator
                .comparing(PositionIdentity::productId).thenComparing(PositionIdentity::lotId)
                .thenComparing(PositionIdentity::locationId));
        for(var line:lines) grouped.computeIfAbsent(new PositionIdentity(line.locationId(),line.productId(),line.lotId()),
                ignored -> new ArrayList<>()).add(line);
        var identities=List.copyOf(grouped.keySet());
        inventory.lockTransferSettlementContexts(org,store,identities);
        var candidatesByIdentity=new LinkedHashMap<PositionIdentity,StockPosition>();
        for(var identity:identities) {
            var candidate=inventory.positionByIdentity(org,store,identity.locationId(),identity.lotId())
                    .orElseGet(() -> inventory.createPosition(UUID.randomUUID(),org,store,identity.locationId(),identity.productId(),identity.lotId()));
            if(!candidate.productId().equals(identity.productId())) throw new OrganizationUnavailableException();
            candidatesByIdentity.put(identity,candidate);
        }
        var locked=inventory.lockPositions(org,store,candidatesByIdentity.values().stream().map(StockPosition::id).toList());
        var byId=locked.stream().collect(java.util.stream.Collectors.toMap(StockPosition::id,position -> position));
        var prepared=new ArrayList<PreparedTransferStockLine>(lines.size());
        for(var entry:grouped.entrySet()) {
            var groupLines=entry.getValue();
            long expectedVersion=groupLines.getFirst().expectedVersion();
            if(groupLines.stream().anyMatch(line -> line.expectedVersion()!=expectedVersion)) throw failure(Kind.CONFLICT);
            long totalQuantity=0;
            try {
                for(var line:groupLines) totalQuantity=Math.addExact(totalQuantity,line.quantity());
            } catch(ArithmeticException overflow) {
                throw failure(Kind.INVALID_REQUEST);
            }
            var candidate=candidatesByIdentity.get(entry.getKey());
            var before=byId.get(candidate.id());
            if(before==null) throw new OrganizationUnavailableException();
            if(before.version()!=expectedVersion) throw failure(Kind.CONFLICT);
            if(before.trust()==Trust.FROZEN) throw failure(Kind.POSITION_FROZEN);
            var quarantineByLine=new LinkedHashMap<UUID,Boolean>();
            long quarantinedQuantity=0;
            try {
                for(var line:groupLines) {
                    boolean quarantine=!eligible(actor,org,store,before,FoodSafetyEvaluation.Operation.TRANSFER);
                    quarantineByLine.put(line.lineId(),quarantine);
                    if(quarantine) quarantinedQuantity=Math.addExact(quarantinedQuantity,line.quantity());
                }
            } catch(ArithmeticException overflow) {
                throw failure(Kind.INVALID_REQUEST);
            }
            var delta=new Delta(totalQuantity,0,quarantinedQuantity,0);
            StockBuckets buckets;long version;
            try {
                buckets=before.buckets().apply(delta);
                version=Math.incrementExact(before.version());
            } catch(ArithmeticException | IllegalArgumentException invalid) {
                throw failure(Kind.INVALID_REQUEST);
            }
            for(var line:groupLines) {
                boolean quarantine=quarantineByLine.get(line.lineId());
                prepared.add(new PreparedTransferStockLine(line.lineId(),before,line.quantity(),
                        new Delta(line.quantity(),0,quarantine?line.quantity():0,0),buckets,version,
                        quarantine?UUID.randomUUID():null,null));
            }
        }
        return postTransferStock(actor,org,store,member.membershipId(),requestId,digest,reason,evidenceReference,
                InventoryOperation.TRANSFER_IN,prepared);
    }

    @Transactional
    public TransferStockResult returnForTransfer(UUID actor,UUID org,UUID store,UUID requestId,UUID transferId,
            String reason,String evidenceReference,List<TransferReturnLine> requestedLines) {
        if(requestId==null || transferId==null) throw failure(Kind.INVALID_REQUEST);
        var member=access.requireTransferSettlementStore(actor,org,store,StoreAction.RECEIVE_STOCK);
        var lines=normalizeTransferReturns(requestedLines);
        reason=text(reason,500);evidenceReference=text(evidenceReference,256);
        byte[] digest=transferSettlementFingerprint(store,InventoryOperation.TRANSFER_RETURN,transferId,lines,reason,evidenceReference);
        inventory.lockRequest(org,actor,InventoryOperation.TRANSFER_RETURN,requestId);
        var replay=transferStockReplay(actor,org,store,InventoryOperation.TRANSFER_RETURN,requestId,digest);
        if(replay!=null) return replay;

        inventory.lockStoreForWrite(org,store);
        var observed=lines.stream().map(line -> inventory.position(org,store,line.positionId(),false)
                .orElseThrow(OrganizationUnavailableException::new)).toList();
        inventory.lockTransferSettlementContexts(org,store,observed.stream().map(position ->
                new PositionIdentity(position.locationId(),position.productId(),position.lotId())).toList());
        var locked=inventory.lockPositions(org,store,lines.stream().map(TransferReturnLine::positionId).toList());
        var byId=locked.stream().collect(java.util.stream.Collectors.toMap(StockPosition::id,position -> position));
        var prepared=new ArrayList<PreparedTransferStockLine>(lines.size());
        for(var line:lines) {
            var before=byId.get(line.positionId());
            if(before==null) throw new OrganizationUnavailableException();
            if(before.version()!=line.expectedVersion()) throw failure(Kind.CONFLICT);
            if(before.trust()==Trust.FROZEN) throw failure(Kind.POSITION_FROZEN);
            if(before.initialization()!=StockPosition.Initialization.INITIALIZED) throw failure(Kind.NOT_INITIALIZED);
            boolean quarantine=!eligible(actor,org,store,before,FoodSafetyEvaluation.Operation.TRANSFER);
            var delta=new Delta(line.quantity(),0,quarantine?line.quantity():0,0);
            StockBuckets buckets;long version;
            try {
                buckets=before.buckets().apply(delta);
                version=Math.incrementExact(before.version());
            } catch(ArithmeticException | IllegalArgumentException invalid) {
                throw failure(Kind.INVALID_REQUEST);
            }
            prepared.add(new PreparedTransferStockLine(line.lineId(),before,line.quantity(),delta,buckets,version,
                    quarantine?UUID.randomUUID():null,null));
        }
        return postTransferStock(actor,org,store,member.membershipId(),requestId,digest,reason,evidenceReference,
                InventoryOperation.TRANSFER_RETURN,prepared);
    }

    @Transactional(readOnly=true)
    public List<TransferSettlementPositionVersion> transferSettlementPositionVersions(UUID actor,UUID org,UUID store,
            List<TransferSettlementPositionRequest> requests) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_INVENTORY,false);
        if(requests==null || requests.isEmpty() || requests.stream().anyMatch(request -> request==null || request.lineId()==null)
                || requests.stream().map(TransferSettlementPositionRequest::lineId).distinct().count()!=requests.size())
            throw new IllegalArgumentException("Transfer position versions require unique line identities");
        return requests.stream().map(request -> {
            if(request.positionId()!=null && request.identity()!=null || request.positionId()==null && request.identity()==null)
                throw new IllegalArgumentException("Each transfer position requires exactly one lookup identity");
            if(request.positionId()!=null) {
                var position=inventory.position(org,store,request.positionId(),false).orElseThrow(OrganizationUnavailableException::new);
                return new TransferSettlementPositionVersion(request.lineId(),position.id(),position.version());
            }
            var identity=request.identity();
            var position=inventory.positionByIdentity(org,store,identity.locationId(),identity.lotId());
            if(position.isPresent() && !position.orElseThrow().productId().equals(identity.productId()))
                throw new OrganizationUnavailableException();
            return position.map(value -> new TransferSettlementPositionVersion(request.lineId(),value.id(),value.version()))
                    .orElseGet(() -> new TransferSettlementPositionVersion(request.lineId(),null,0));
        }).toList();
    }

    private void validateTransferReservation(UUID actor,UUID org,UUID store,UUID transferId,UUID lineId,
            UUID allocationId,long expectedVersion,long quantity,StockPosition before) {
        if(before.version()!=expectedVersion) throw failure(Kind.CONFLICT);
        if(before.trust()==Trust.FROZEN) throw failure(Kind.POSITION_FROZEN);
        if(before.initialization()!=StockPosition.Initialization.INITIALIZED) throw failure(Kind.NOT_INITIALIZED);
        if(!inventory.transferAllocationMatches(org,store,transferId,lineId,before.id(),allocationId))
            throw failure(Kind.ALLOCATION_UNAVAILABLE);
        var allocation=inventory.allocation(org,store,before.id(),allocationId)
                .orElseThrow(OrganizationUnavailableException::new);
        if(!allocation.bucket().equals("RESERVED") || !allocation.state().equals("ACTIVE")
                || allocation.remaining()!=quantity) throw failure(Kind.ALLOCATION_UNAVAILABLE);
        if(!eligible(actor,org,store,before,FoodSafetyEvaluation.Operation.TRANSFER))
            throw failure(Kind.LOT_NOT_ELIGIBLE);
    }

    private Result change(UUID actor,UUID org,UUID store,InventoryOperation operation,StockChange request,UUID allocationId) {
        if(operation==InventoryOperation.TRANSFER_RESERVE || operation==InventoryOperation.TRANSFER_RELEASE
                || operation==InventoryOperation.TRANSFER_OUT)
            throw new IllegalStateException("Transfer stock operations require the transfer-owned posting port");
        var member=access.requireStore(actor,org,store,action(operation),true);
        var command=normalize(request,operation);
        if((operation==InventoryOperation.RELEASE || operation==InventoryOperation.RELEASE_HOLD) && allocationId==null) throw failure(Kind.INVALID_REQUEST);
        var digest=InventoryFingerprint.of(store,operation,command.positionId(),command.expectedVersion(),command.quantity(),command.reason(),command.evidenceReference(),allocationId);
        var replay=replay(actor,org,store,operation,command.requestId(),digest);
        if(replay!=null) {
            if(operation==InventoryOperation.RELEASE || operation==InventoryOperation.RELEASE_HOLD) {
                var owned=inventory.allocation(org,store,command.positionId(),allocationId).orElseThrow(OrganizationUnavailableException::new);
                if(inventory.isTransferLinkedAllocation(org,store,command.positionId(),allocationId))
                    throw failure(Kind.ALLOCATION_UNAVAILABLE);
                requireOwner(actor,org,store,owned);
            }
            return replay;
        }
        inventory.lockStoreForWrite(org,store);
        var before=lockedPosition(org,store,command.positionId());
        if(command.expectedVersion()!=null && command.expectedVersion()!=before.version()) throw failure(Kind.CONFLICT);
        if(before.trust()==Trust.FROZEN) throw failure(Kind.POSITION_FROZEN);
        if(before.initialization()!=StockPosition.Initialization.INITIALIZED) throw failure(Kind.NOT_INITIALIZED);
        Allocation allocation=allocationId==null?null:inventory.allocation(org,store,before.id(),allocationId).orElseThrow(OrganizationUnavailableException::new);
        long quantity=command.quantity();
        if(allocation!=null) {
            if(inventory.isTransferLinkedAllocation(org,store,before.id(),allocation.id()))
                throw failure(Kind.ALLOCATION_UNAVAILABLE);
            if(!allocation.state().equals("ACTIVE") || quantity>allocation.remaining()) throw failure(Kind.ALLOCATION_UNAVAILABLE);
            if(operation==InventoryOperation.RELEASE && !allocation.bucket().equals("RESERVED")
                    || operation==InventoryOperation.RELEASE_HOLD && allocation.bucket().equals("RESERVED")) throw failure(Kind.ALLOCATION_UNAVAILABLE);
            if(operation==InventoryOperation.RELEASE || operation==InventoryOperation.RELEASE_HOLD) requireOwner(actor,org,store,allocation);
        }
        if(operation==InventoryOperation.SALE || operation==InventoryOperation.RESERVE || operation==InventoryOperation.RELEASE_HOLD) {
            if(!eligible(actor,org,store,before)) throw failure(Kind.LOT_NOT_ELIGIBLE);
        }
        String newBucket=switch(operation) {
            case RESERVE->"RESERVED"; case QUARANTINE->"QUARANTINED"; case HOLD->"OTHER_BLOCKED";
            case ADJUST->quantity>0 && !eligible(actor,org,store,before)?"QUARANTINED":null;
            default->null;
        };
        boolean needsAvailable=operation==InventoryOperation.RESERVE || operation==InventoryOperation.QUARANTINE || operation==InventoryOperation.HOLD
                || operation==InventoryOperation.SALE || operation==InventoryOperation.WRITE_OFF && allocation==null || operation==InventoryOperation.ADJUST && quantity<0;
        if(needsAvailable && Math.abs(quantity)>before.buckets().available()) throw failure(Kind.INSUFFICIENT_STOCK);
        var delta=switch(operation) {
            case RESERVE, QUARANTINE, HOLD->bucketDelta(newBucket,quantity,0);
            case RELEASE, RELEASE_HOLD->bucketDelta(allocation.bucket(),-quantity,0);
            case WRITE_OFF->allocation==null?new Delta(-quantity,0,0,0):bucketDelta(allocation.bucket(),-quantity,-quantity);
            case ADJUST->newBucket==null?new Delta(quantity,0,0,0):bucketDelta(newBucket,quantity,quantity);
            case SALE->new Delta(-quantity,0,0,0);
            default->throw new IllegalStateException("Unsupported stock command");
        };
        UUID createdAllocation=newBucket==null?null:UUID.randomUUID();
        var result=post(actor,org,store,member.membershipId(),operation,command.requestId(),digest,before,delta,
                command.reason(),command.evidenceReference(),allocation==null?createdAllocation:allocation.id(),null);
        if(createdAllocation!=null) inventory.openAllocation(org,store,before.id(),createdAllocation,newBucket,actor,member.membershipId(),quantity,result.movementId());
        if(allocation!=null) inventory.reduceAllocation(org,store,allocation,quantity,operation==InventoryOperation.WRITE_OFF?"WRITE_OFF":"RELEASE",result.movementId());
        audit(actor,org,operation,before,result,command.reason(),command.evidenceReference());
        return result;
    }

    private StockPosition lockedPosition(UUID org,UUID store,UUID id) {
        var observed=inventory.position(org,store,id,false).orElseThrow(OrganizationUnavailableException::new);
        inventory.lockActiveContext(org,store,observed.locationId(),observed.productId(),observed.lotId());
        return inventory.position(org,store,id,true).orElseThrow(OrganizationUnavailableException::new);
    }

    private Result replay(UUID actor,UUID org,UUID store,InventoryOperation operation,UUID request,byte[] digest) {
        inventory.lockRequest(org,actor,operation,request);
        var previous=inventory.command(org,actor,operation,request);
        if(previous.isEmpty()) return null;
        var recorded=previous.orElseThrow();
        if(!recorded.storeId().equals(store) || !MessageDigest.isEqual(recorded.payloadDigest(),digest)) throw failure(Kind.IDEMPOTENCY_CONFLICT);
        return json.readValue(recorded.outcome(),Result.class);
    }

    private Result post(UUID actor,UUID org,UUID store,UUID membership,InventoryOperation operation,UUID request,byte[] digest,
            StockPosition before,Delta delta,String reason,String evidence,UUID allocation,UUID receipt) {
        StockBuckets after; long version;
        try { after=before.buckets().apply(delta); version=Math.incrementExact(before.version()); }
        catch(ArithmeticException | IllegalArgumentException invalid) { throw failure(Kind.CONFLICT); }
        var result=new Result(UUID.randomUUID(),before.id(),version,after,allocation,receipt);
        var command=new CommandReceipt(UUID.randomUUID(),org,store,actor,membership,operation,request,digest,json.writeValueAsString(result));
        if(!inventory.recordCommand(command)) throw failure(Kind.IDEMPOTENCY_CONFLICT);
        inventory.recordMovement(result.movementId(),command,reason,evidence);
        inventory.postLine(org,store,result.movementId(),before.id(),before.version(),delta,Trust.UNVERIFIED);
        return result;
    }

    private TransferStockResult postTransferStock(UUID actor,UUID org,UUID store,UUID membership,UUID requestId,
            byte[] digest,String reason,String evidence,InventoryOperation operation,
            List<PreparedTransferStockLine> prepared) {
        UUID commandId=UUID.randomUUID(),movementId=UUID.randomUUID();
        var result=new TransferStockResult(commandId,movementId,prepared.stream()
                .map(line->new TransferStockLineResult(line.lineId(),line.before().id(),line.allocationId(),
                        line.quantity(),line.version(),line.buckets()))
                .sorted(Comparator.comparing(TransferStockLineResult::lineId)).toList());
        var command=new CommandReceipt(commandId,org,store,actor,membership,operation,requestId,digest,json.writeValueAsString(result));
        if(!inventory.recordCommand(command)) throw failure(Kind.IDEMPOTENCY_CONFLICT);
        inventory.recordMovement(movementId,command,reason,evidence);
        if(operation==InventoryOperation.TRANSFER_IN) {
            var byPosition=prepared.stream().collect(java.util.stream.Collectors.groupingBy(
                    line->line.before().id(),LinkedHashMap::new,java.util.stream.Collectors.toList()));
            var orderedGroups=byPosition.values().stream().sorted(Comparator
                    .comparing((List<PreparedTransferStockLine> group)->group.getFirst().before().productId())
                    .thenComparing(group->group.getFirst().before().lotId())
                    .thenComparing(group->group.getFirst().before().locationId())
                    .thenComparing(group->group.getFirst().before().id())).toList();
            for(var group:orderedGroups) {
                var first=group.getFirst();
                long onHand=0,quarantined=0;
                try {
                    for(var line:group) {
                        if(!line.before().equals(first.before()) || line.version()!=first.version()
                                || !line.buckets().equals(first.buckets())) throw failure(Kind.CONFLICT);
                        onHand=Math.addExact(onHand,line.delta().onHand());
                        quarantined=Math.addExact(quarantined,line.delta().quarantined());
                    }
                } catch(ArithmeticException overflow) {
                    throw failure(Kind.INVALID_REQUEST);
                }
                var delta=new Delta(onHand,0,quarantined,0);
                var after=inventory.postLine(org,store,movementId,first.before().id(),first.before().version(),delta,Trust.UNVERIFIED);
                if(after.version()!=first.version() || !after.buckets().equals(first.buckets())) throw failure(Kind.CONFLICT);
                for(var line:group) {
                    if(line.allocationId()!=null) inventory.openAllocation(org,store,after.id(),line.allocationId(),
                            "QUARANTINED",actor,membership,line.quantity(),movementId);
                    var lineResult=new Result(movementId,after.id(),after.version(),after.buckets(),line.allocationId(),null);
                    audit.securityChange(actor,org,"stock-position",after.id(),"STOCK_"+operation.name(),
                            Map.of("outcome","SUCCEEDED","before",line.before(),"after",lineResult,
                                    "transferLineId",line.lineId()),reason);
                }
            }
            return result;
        }
        for(var line:prepared) {
            var after=inventory.postLine(org,store,movementId,line.before().id(),line.before().version(),line.delta(),Trust.UNVERIFIED);
            if(operation==InventoryOperation.TRANSFER_RESERVE) {
                inventory.openAllocation(org,store,after.id(),line.allocationId(),"RESERVED",actor,membership,
                        line.quantity(),movementId);
            } else if(operation==InventoryOperation.TRANSFER_OUT) {
                inventory.reduceAllocation(org,store,line.allocation(),line.quantity(),
                        "CONSUME",movementId);
            } else if(operation==InventoryOperation.TRANSFER_IN || operation==InventoryOperation.TRANSFER_RETURN) {
                if(line.allocationId()!=null) inventory.openAllocation(org,store,after.id(),line.allocationId(),
                        "QUARANTINED",actor,membership,line.quantity(),movementId);
            } else {
                inventory.reduceAllocation(org,store,line.allocation(),line.quantity(),"RELEASE",movementId);
            }
            var lineResult=new Result(movementId,after.id(),after.version(),after.buckets(),line.allocationId(),null);
            audit.securityChange(actor,org,"stock-position",after.id(),"STOCK_"+operation.name(),
                    Map.of("outcome","SUCCEEDED","before",line.before(),"after",lineResult,
                            "transferLineId",line.lineId()),reason);
        }
        return result;
    }

    private TransferStockResult transferStockReplay(UUID actor,UUID org,UUID store,InventoryOperation operation,
            UUID requestId,byte[] digest) {
        var previous=inventory.command(org,actor,operation,requestId);
        if(previous.isEmpty()) return null;
        var recorded=previous.orElseThrow();
        if(!recorded.storeId().equals(store) || !MessageDigest.isEqual(recorded.payloadDigest(),digest))
            throw failure(Kind.IDEMPOTENCY_CONFLICT);
        return json.readValue(recorded.outcome(),TransferStockResult.class);
    }

    private static List<TransferReservationLine> normalizeTransferReservations(List<TransferReservationLine> lines) {
        if(lines==null || lines.isEmpty() || lines.size()>MAX_TRANSFER_LINES) throw failure(Kind.INVALID_REQUEST);
        var lineIds=new HashSet<UUID>();var positions=new HashSet<UUID>();
        var normalized=new ArrayList<TransferReservationLine>(lines.size());
        for(var line:lines) {
            if(line==null || line.lineId()==null || line.positionId()==null || line.expectedVersion()<0
                    || line.expectedVersion()==Long.MAX_VALUE || line.quantity()<=0
                    || !lineIds.add(line.lineId()) || !positions.add(line.positionId()))
                throw failure(Kind.INVALID_REQUEST);
            normalized.add(line);
        }
        normalized.sort(Comparator.comparing(TransferReservationLine::lineId));
        return List.copyOf(normalized);
    }

    private static List<TransferReleaseLine> normalizeTransferReleases(List<TransferReleaseLine> lines) {
        if(lines==null || lines.isEmpty() || lines.size()>MAX_TRANSFER_LINES) throw failure(Kind.INVALID_REQUEST);
        var lineIds=new HashSet<UUID>();var positions=new HashSet<UUID>();var allocations=new HashSet<UUID>();
        var normalized=new ArrayList<TransferReleaseLine>(lines.size());
        for(var line:lines) {
            if(line==null || line.lineId()==null || line.positionId()==null || line.allocationId()==null
                    || line.expectedQuantity()<=0 || !lineIds.add(line.lineId()) || !positions.add(line.positionId())
                    || !allocations.add(line.allocationId())) throw failure(Kind.INVALID_REQUEST);
            normalized.add(line);
        }
        normalized.sort(Comparator.comparing(TransferReleaseLine::lineId));
        return List.copyOf(normalized);
    }

    private static List<TransferDispatchLine> normalizeTransferDispatches(List<TransferDispatchLine> lines) {
        if(lines==null || lines.isEmpty() || lines.size()>MAX_TRANSFER_LINES) throw failure(Kind.INVALID_REQUEST);
        var lineIds=new HashSet<UUID>();var positions=new HashSet<UUID>();var allocations=new HashSet<UUID>();
        var normalized=new ArrayList<TransferDispatchLine>(lines.size());
        for(var line:lines) {
            if(line==null || line.lineId()==null || line.positionId()==null || line.allocationId()==null
                    || line.expectedVersion()<0 || line.expectedVersion()==Long.MAX_VALUE || line.quantity()<=0
                    || !lineIds.add(line.lineId()) || !positions.add(line.positionId()) || !allocations.add(line.allocationId()))
                throw failure(Kind.INVALID_REQUEST);
            normalized.add(line);
        }
        normalized.sort(Comparator.comparing(TransferDispatchLine::lineId));
        return List.copyOf(normalized);
    }

    private static List<TransferReceiptLine> normalizeTransferReceipts(List<TransferReceiptLine> lines) {
        if(lines==null || lines.isEmpty() || lines.size()>MAX_TRANSFER_LINES) throw failure(Kind.INVALID_REQUEST);
        var lineIds=new HashSet<UUID>();
        var normalized=new ArrayList<TransferReceiptLine>(lines.size());
        for(var line:lines) {
            if(line==null || line.lineId()==null || line.locationId()==null || line.productId()==null || line.lotId()==null
                    || line.expectedVersion()<0 || line.expectedVersion()==Long.MAX_VALUE || line.quantity()<=0
                    || !lineIds.add(line.lineId()))
                throw failure(Kind.INVALID_REQUEST);
            normalized.add(line);
        }
        normalized.sort(Comparator.comparing(TransferReceiptLine::lineId));
        return List.copyOf(normalized);
    }

    private static List<TransferReturnLine> normalizeTransferReturns(List<TransferReturnLine> lines) {
        if(lines==null || lines.isEmpty() || lines.size()>MAX_TRANSFER_LINES) throw failure(Kind.INVALID_REQUEST);
        var lineIds=new HashSet<UUID>();var positions=new HashSet<UUID>();
        var normalized=new ArrayList<TransferReturnLine>(lines.size());
        for(var line:lines) {
            if(line==null || line.lineId()==null || line.positionId()==null || line.expectedVersion()<0
                    || line.expectedVersion()==Long.MAX_VALUE || line.quantity()<=0 || !lineIds.add(line.lineId())
                    || !positions.add(line.positionId())) throw failure(Kind.INVALID_REQUEST);
            normalized.add(line);
        }
        normalized.sort(Comparator.comparing(TransferReturnLine::lineId));
        return List.copyOf(normalized);
    }

    private static byte[] transferSettlementFingerprint(UUID store,InventoryOperation operation,UUID transfer,
            List<?> lines,String reason,String evidence,Object... additional) {
        var values=new ArrayList<Object>();
        values.add(store);values.add(operation);values.add(transfer);values.add(reason);values.add(evidence);
        values.addAll(List.of(additional));
        for(Object line:lines) values.add(line);
        return InventoryFingerprint.of(values.toArray());
    }

    private static byte[] transferStockFingerprint(UUID store,UUID transfer,List<TransferReservationLine> lines,
            String reason,String evidence) {
        var values=new ArrayList<Object>();
        values.add(store);values.add(InventoryOperation.TRANSFER_RESERVE);values.add(transfer);
        values.add(reason);values.add(evidence);
        for(var line:lines) {
            values.add(line.lineId());values.add(line.positionId());values.add(line.expectedVersion());values.add(line.quantity());
        }
        return InventoryFingerprint.of(values.toArray());
    }

    private static byte[] transferReleaseFingerprint(UUID store,UUID transfer,List<TransferReleaseLine> lines,
            String reason,String evidence) {
        var values=new ArrayList<Object>();
        values.add(store);values.add(InventoryOperation.TRANSFER_RELEASE);values.add(transfer);
        values.add(reason);values.add(evidence);
        for(var line:lines) {
            values.add(line.lineId());values.add(line.positionId());values.add(line.allocationId());values.add(line.expectedQuantity());
        }
        return InventoryFingerprint.of(values.toArray());
    }

    private static byte[] transferDispatchFingerprint(UUID store,UUID transfer,List<TransferDispatchLine> lines,
            String reason,String evidence) {
        var values=new ArrayList<Object>();
        values.add(store);values.add(InventoryOperation.TRANSFER_OUT);values.add(transfer);
        values.add(reason);values.add(evidence);
        for(var line:lines) {
            values.add(line.lineId());values.add(line.positionId());values.add(line.allocationId());
            values.add(line.expectedVersion());values.add(line.quantity());
        }
        return InventoryFingerprint.of(values.toArray());
    }

    private boolean eligible(UUID actor,UUID org,UUID store,StockPosition position) {
        return eligible(actor,org,store,position,FoodSafetyEvaluation.Operation.SALE);
    }
    private boolean eligible(UUID actor,UUID org,UUID store,StockPosition position,FoodSafetyEvaluation.Operation operation) {
        return safety.evaluate(actor,org,position.lotId(),store,null,position.locationId(),operation)
                .decision()==FoodSafetyEvaluation.Decision.ALLOWED;
    }
    private void requireOwner(UUID actor,UUID org,UUID store,Allocation allocation) {
        if(!allocation.ownerId().equals(actor)) access.requireStore(actor,org,store,StoreAction.MANAGE_INVENTORY_ALLOCATIONS,true);
    }
    private void audit(UUID actor,UUID org,InventoryOperation operation,StockPosition before,Result after,String reason,String evidence) {
        audit.securityChange(actor,org,"stock-position",before.id(),"STOCK_"+operation.name(),
                Map.of("outcome","SUCCEEDED","before",before,"after",after,"evidenceReference",evidence),reason);
    }
    private static StockChange normalize(StockChange command,InventoryOperation operation) {
        if(command==null || command.requestId()==null || command.positionId()==null || command.quantity()==0 || command.quantity()==Long.MIN_VALUE
                || operation!=InventoryOperation.ADJUST && command.quantity()<0
                || command.expectedVersion()!=null && (command.expectedVersion()<0 || command.expectedVersion()==Long.MAX_VALUE)) throw failure(Kind.INVALID_REQUEST);
        return new StockChange(command.requestId(),command.positionId(),command.expectedVersion(),command.quantity(),text(command.reason(),500),text(command.evidenceReference(),256));
    }

    void validateStockIntent(UUID org,UUID store,InventoryOperation operation,StockChange command,UUID allocationId) {
        normalize(command,operation);
        if((operation==InventoryOperation.RELEASE || operation==InventoryOperation.RELEASE_HOLD) && allocationId==null)
            throw failure(Kind.INVALID_REQUEST);
        if(inventory.position(org,store,command.positionId(),false).isEmpty()) throw new OrganizationUnavailableException();
    }
    private static String text(String value,int maximum) {
        if(value==null || value.isBlank() || value.length()>maximum || value.codePoints().anyMatch(Character::isISOControl)) throw failure(Kind.INVALID_REQUEST);
        return value.strip();
    }
    private static Delta bucketDelta(String bucket,long quantity,long physical) {
        return switch(bucket) {
            case "RESERVED"->new Delta(physical,quantity,0,0); case "QUARANTINED"->new Delta(physical,0,quantity,0);
            case "OTHER_BLOCKED"->new Delta(physical,0,0,quantity); default->throw new IllegalStateException("Unsupported stock bucket");
        };
    }
    private static StoreAction action(InventoryOperation operation) {
        return switch(operation) {
            case RESERVE->StoreAction.RESERVE_STOCK; case RELEASE->StoreAction.RELEASE_RESERVATION; case ADJUST->StoreAction.ADJUST_STOCK;
            case WRITE_OFF->StoreAction.WRITE_OFF_STOCK; case QUARANTINE,HOLD,RELEASE_HOLD->StoreAction.QUARANTINE_STOCK;
            case SALE->StoreAction.RECORD_SALE; default->throw new IllegalStateException("Unsupported stock command");
        };
    }
    private static InventoryFailure failure(Kind kind) { return new InventoryFailure(kind); }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public StockPosition snapshot(UUID actor,UUID org,UUID store,UUID position) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_INVENTORY,false);
        return inventory.position(org,store,position,false).orElseThrow(OrganizationUnavailableException::new);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<StockPosition> positions(UUID actor,UUID org,UUID store,UUID lot,int offset,int limit) {
        return positions(actor,org,store,null,lot,null,offset,limit);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<StockPosition> positions(UUID actor,UUID org,UUID store,UUID product,UUID lot,UUID location,int offset,int limit) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_INVENTORY,false);
        if(offset<0 || offset>10000 || limit<1 || limit>100) throw failure(Kind.INVALID_REQUEST);
        var rows=inventory.positions(org,store,product,lot,location,offset,limit+1);
        return new Page<>(rows.stream().limit(limit).toList(),rows.size()>limit);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<InventoryReadModels.Allocation> allocations(UUID actor,UUID org,UUID store,UUID position,int offset,int limit) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_INVENTORY,false);
        inventory.position(org,store,position,false).orElseThrow(OrganizationUnavailableException::new);
        if(offset<0 || offset>10000 || limit<1 || limit>100) throw failure(Kind.INVALID_REQUEST);
        var rows=inventory.allocations(org,store,position,offset,limit+1);
        return new Page<>(rows.stream().limit(limit)
                .map(row->new InventoryReadModels.Allocation(row.id(),row.positionId(),row.bucket(),row.remaining(),row.state(),row.version(),row.ownerId().equals(actor)))
                .toList(),rows.size()>limit);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<InventoryReadModels.Movement> history(UUID actor,UUID org,UUID store,UUID position,long afterVersion,int limit) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_INVENTORY,false);
        inventory.position(org,store,position,false).orElseThrow(OrganizationUnavailableException::new);
        if(afterVersion<0 || limit<1 || limit>100) throw failure(Kind.INVALID_REQUEST);
        var rows=inventory.history(org,store,position,afterVersion,limit+1);
        return new Page<>(rows.stream().limit(limit)
                .map(row->new InventoryReadModels.Movement(row.id(),row.operation(),row.positionVersion(),row.delta(),row.reason(),row.evidenceReference(),row.recordedAt()))
                .toList(),rows.size()>limit);
    }
    @Transactional(readOnly=true)
    public Result commandOutcome(UUID actor,UUID org,UUID store,InventoryOperation operation,UUID requestId) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_INVENTORY,false);
        var receipt=inventory.command(org,actor,operation,requestId).orElseThrow(OrganizationUnavailableException::new);
        if(!receipt.storeId().equals(store) || json.readTree(receipt.outcome()).has("lines")) throw new OrganizationUnavailableException();
        return json.readValue(receipt.outcome(),Result.class);
    }
    @Transactional(readOnly=true)
    public ReceiptDocumentResult receiptDocumentOutcome(UUID actor,UUID org,UUID store,UUID requestId) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_INVENTORY,false);
        var receipt=inventory.command(org,actor,InventoryOperation.RECEIVE,requestId).orElseThrow(OrganizationUnavailableException::new);
        if(!receipt.storeId().equals(store) || !json.readTree(receipt.outcome()).has("lines"))
            throw new OrganizationUnavailableException();
        return receiptDocumentResult(receipt.outcome(),org,store);
    }

    private ReceiptDocumentResult receiptDocumentResult(String outcome,UUID org,UUID store) {
        var result=json.readTree(outcome);
        var lines=result.path("lines");
        var missingQuantity=false;
        for(var line:lines) missingQuantity|=!line.hasNonNull("quantity");
        if(!missingQuantity) return json.readValue(outcome,ReceiptDocumentResult.class);
        var receiptId=UUID.fromString(result.path("receiptId").asString());
        var quantities=inventory.receiptLineQuantities(org,store,receiptId);
        for(var line:lines) {
            if(line.hasNonNull("quantity")) continue;
            var quantity=quantities.getOrDefault(line.path("documentLineReference").asString(),0L);
            if(quantity<=0) throw new OrganizationUnavailableException();
            ((ObjectNode)line).put("quantity",quantity);
        }
        return json.readValue(result.toString(),ReceiptDocumentResult.class);
    }
}
