package com.stockrescue.inventory.application;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.catalog.application.FoodSafetyEvaluation;
import com.stockrescue.catalog.application.FoodSafetyEvaluationService;
import com.stockrescue.inventory.application.InventoryCommands.AllocationChange;
import com.stockrescue.inventory.application.InventoryCommands.ReceiveGoods;
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
import com.stockrescue.inventory.domain.InventoryOperation;
import com.stockrescue.platform.audit.AuditWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InventoryService {
    public record Page<T>(List<T> items,boolean hasMore) { public Page { items=List.copyOf(items); } }
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

    private Result change(UUID actor,UUID org,UUID store,InventoryOperation operation,StockChange request,UUID allocationId) {
        var member=access.requireStore(actor,org,store,action(operation),true);
        var command=normalize(request,operation);
        if((operation==InventoryOperation.RELEASE || operation==InventoryOperation.RELEASE_HOLD) && allocationId==null) throw failure(Kind.INVALID_REQUEST);
        var digest=InventoryFingerprint.of(store,operation,command.positionId(),command.expectedVersion(),command.quantity(),command.reason(),command.evidenceReference(),allocationId);
        var replay=replay(actor,org,store,operation,command.requestId(),digest);
        if(replay!=null) {
            if(operation==InventoryOperation.RELEASE || operation==InventoryOperation.RELEASE_HOLD) {
                var owned=inventory.allocation(org,store,command.positionId(),allocationId).orElseThrow(OrganizationUnavailableException::new);
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

    private boolean eligible(UUID actor,UUID org,UUID store,StockPosition position) {
        return safety.evaluate(actor,org,position.lotId(),store,null,position.locationId(),FoodSafetyEvaluation.Operation.SALE)
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
        if(!receipt.storeId().equals(store)) throw new OrganizationUnavailableException();
        return json.readValue(receipt.outcome(),Result.class);
    }
}
