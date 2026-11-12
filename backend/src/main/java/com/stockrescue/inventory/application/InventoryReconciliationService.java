package com.stockrescue.inventory.application;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.inventory.application.InventoryCommands.Result;
import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.inventory.domain.InventoryFailure.Kind;
import com.stockrescue.inventory.domain.StockBuckets;
import com.stockrescue.inventory.domain.StockBuckets.Delta;
import com.stockrescue.inventory.domain.StockPosition;
import com.stockrescue.inventory.domain.StockPosition.Trust;
import com.stockrescue.inventory.persistence.InventoryReconciliationRepository;
import com.stockrescue.inventory.persistence.InventoryReconciliationRepository.Allocation;
import com.stockrescue.inventory.persistence.InventoryReconciliationRepository.AllocationTransition;
import com.stockrescue.inventory.persistence.InventoryReconciliationRepository.Finding;
import com.stockrescue.inventory.persistence.InventoryReconciliationRepository.JournalLine;
import com.stockrescue.inventory.persistence.InventoryReconciliationRepository.Run;
import com.stockrescue.inventory.persistence.InventoryRepository;
import com.stockrescue.inventory.persistence.InventoryRepository.CommandReceipt;
import com.stockrescue.inventory.persistence.InventoryRepository.Operation;
import com.stockrescue.platform.audit.AuditWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InventoryReconciliationService {
    public record Page<T>(List<T> items,boolean hasMore) { public Page { items=List.copyOf(items); } }
    public record RepairRequest(UUID requestId,UUID runId,UUID positionId,long expectedVersion,String reason,String evidenceReference) {}

    private final AccessPolicy access;
    private final InventoryRepository inventory;
    private final InventoryReconciliationRepository reconciliations;
    private final AuditWriter audit;
    private final ObjectMapper json;
    private final Clock clock;

    public InventoryReconciliationService(AccessPolicy access,InventoryRepository inventory,
            InventoryReconciliationRepository reconciliations,AuditWriter audit,ObjectMapper json,Clock clock) {
        this.access=access;this.inventory=inventory;this.reconciliations=reconciliations;this.audit=audit;this.json=json;this.clock=clock;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Run reconcile(UUID actor,UUID org,UUID store) {
        var member=access.requireStore(actor,org,store,StoreAction.RECONCILE_INVENTORY,true);
        inventory.lockStoreForReconciliation(org,store);
        Instant started=clock.instant();
        UUID runId=UUID.randomUUID();
        var positions=inventory.lockPositionsForReconciliation(org,store);
        var journal=group(reconciliations.journalLines(org,store),JournalLine::positionId);
        var allocations=group(reconciliations.allocations(org,store),Allocation::positionId);
        var transitions=group(reconciliations.allocationTransitions(org,store),AllocationTransition::positionId);
        var analyzed=new ArrayList<Analysis>();
        for(var position:positions) analyzed.add(analyze(position,journal.getOrDefault(position.id(),List.of()),
                allocations.getOrDefault(position.id(),List.of()),transitions.getOrDefault(position.id(),List.of())));

        var findings=new ArrayList<Finding>();
        for(var analysis:analyzed) {
            var position=analysis.position();
            long scannedVersion=position.version();
            if(analysis.severity().equals("CRITICAL") && position.trust()!=Trust.FROZEN) {
                UUID requestId=UUID.randomUUID();
                String evidence="reconciliation-run:"+runId;
                String reason="Freeze position after critical ledger reconciliation";
                var digest=InventoryFingerprint.of(store,Operation.RECONCILE,runId,position.id(),analysis.code());
                var command=new CommandReceipt(UUID.randomUUID(),org,store,actor,member.membershipId(),Operation.RECONCILE,
                        requestId,digest,json.writeValueAsString(Map.of("runId",runId,"positionId",position.id(),"finding",analysis.code())));
                inventory.recordCommand(command);
                UUID movement=UUID.randomUUID();
                inventory.recordMovement(movement,command,reason,evidence);
                var frozen=inventory.postLine(org,store,movement,position.id(),position.version(),new Delta(0,0,0,0),Trust.FROZEN);
                audit.securityChange(actor,org,"stock-position",position.id(),"INVENTORY_RECONCILIATION_FREEZE",
                        Map.of("runId",runId,"finding",analysis.code(),"before",position,"after",frozen),reason);
                position=frozen;
            }
            findings.add(new Finding(position.id(),scannedVersion,analysis.severity(),analysis.code(),analysis.expected(),analysis.observed()));
        }
        String severity=findings.stream().map(Finding::severity).max((left,right)->Integer.compare(rank(left),rank(right))).orElse("CLEAN");
        Instant completed=clock.instant();
        var run=new Run(runId,org,store,actor,member.membershipId(),severity,positions.size(),started,completed);
        reconciliations.recordRun(run);
        findings.forEach(finding->reconciliations.recordFinding(org,store,runId,finding));
        audit.securityChange(actor,org,"inventory-reconciliation",runId,"INVENTORY_RECONCILIATION",
                Map.of("severity",severity,"checkedPositions",positions.size(),"criticalPositions",findings.stream().filter(f->f.severity().equals("CRITICAL")).count()),
                "Reconcile committed inventory history");
        return run;
    }

    @Transactional
    public Result remediateProjection(UUID actor,UUID org,UUID store,RepairRequest request) {
        var member=access.requireStore(actor,org,store,StoreAction.REMEDIATE_INVENTORY,true);
        if(request==null || request.requestId()==null || request.runId()==null || request.positionId()==null
                || request.expectedVersion()<0 || request.expectedVersion()==Long.MAX_VALUE) throw failure(Kind.INVALID_REQUEST);
        String reason=text(request.reason(),500),evidence=text(request.evidenceReference(),256);
        var digest=InventoryFingerprint.of(store,Operation.REMEDIATE,request.runId(),request.positionId(),request.expectedVersion(),reason,evidence);
        inventory.lockRequest(org,actor,Operation.REMEDIATE,request.requestId());
        var previous=inventory.command(org,actor,Operation.REMEDIATE,request.requestId());
        if(previous.isPresent()) {
            var receipt=previous.orElseThrow();
            if(!receipt.storeId().equals(store) || !MessageDigest.isEqual(receipt.payloadDigest(),digest)) throw failure(Kind.IDEMPOTENCY_CONFLICT);
            return json.readValue(receipt.outcome(),Result.class);
        }
        inventory.lockStoreForReconciliation(org,store);
        if(!reconciliations.hasCriticalFinding(org,store,request.runId(),request.positionId())) throw failure(Kind.CONFLICT);
        var before=inventory.position(org,store,request.positionId(),true).orElseThrow(OrganizationUnavailableException::new);
        if(before.version()!=request.expectedVersion() || before.trust()!=Trust.FROZEN
                || before.initialization()!=StockPosition.Initialization.INITIALIZED) throw failure(Kind.CONFLICT);
        var allLines=reconciliations.journalLines(org,store,before.id());
        var allAllocations=reconciliations.allocations(org,store,before.id());
        var allTransitions=reconciliations.allocationTransitions(org,store,before.id());
        var analysis=analyze(before,allLines,allAllocations,allTransitions);
        if(!analysis.code().equals("PROJECTION_MISMATCH") || analysis.buckets()==null || !analysis.allocationEvidenceValid()
                || analysis.version()!=before.version()) throw failure(Kind.CONFLICT);

        var afterBuckets=analysis.buckets();
        long version=Math.incrementExact(before.version());
        UUID movement=UUID.randomUUID();
        var result=new Result(movement,before.id(),version,afterBuckets,null,null);
        var command=new CommandReceipt(UUID.randomUUID(),org,store,actor,member.membershipId(),Operation.REMEDIATE,
                request.requestId(),digest,json.writeValueAsString(result));
        if(!inventory.recordCommand(command)) throw failure(Kind.IDEMPOTENCY_CONFLICT);
        inventory.recordMovement(movement,command,reason,evidence);
        var after=inventory.rebuildProjection(org,store,movement,request.runId(),UUID.randomUUID(),actor,member.membershipId(),
                before.id(),before.version(),afterBuckets,reason,evidence);
        audit.securityChange(actor,org,"stock-position",before.id(),"INVENTORY_PROJECTION_REBUILT",
                Map.of("runId",request.runId(),"before",before,"after",after,"evidenceReference",evidence),reason);
        return result;
    }

    @Transactional(readOnly=true)
    public Page<Run> runs(UUID actor,UUID org,UUID store,int offset,int limit) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_INVENTORY,false);
        validatePage(offset,limit);
        var rows=reconciliations.runs(org,store,offset,limit+1);
        return new Page<>(rows.stream().limit(limit).toList(),rows.size()>limit);
    }

    @Transactional(readOnly=true)
    public Page<Finding> findings(UUID actor,UUID org,UUID store,UUID run,int offset,int limit) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_INVENTORY,false);
        validatePage(offset,limit);
        reconciliations.run(org,store,run).orElseThrow(OrganizationUnavailableException::new);
        var rows=reconciliations.findings(org,store,run,offset,limit+1);
        return new Page<>(rows.stream().limit(limit).toList(),rows.size()>limit);
    }

    private Analysis analyze(StockPosition position,List<JournalLine> lines,List<Allocation> allocations,List<AllocationTransition> transitions) {
        BigInteger onHand=BigInteger.ZERO,reserved=BigInteger.ZERO,quarantined=BigInteger.ZERO,blocked=BigInteger.ZERO;
        long nextVersion=1;String latestTrust="UNVERIFIED";String ledgerFailure=null;
        for(var line:lines) {
            if(line.version()!=nextVersion && ledgerFailure==null) ledgerFailure="LEDGER_VERSION_GAP";
            if(nextVersion==Long.MAX_VALUE && ledgerFailure==null) ledgerFailure="LEDGER_VERSION_OVERFLOW";
            nextVersion=line.version()+1;
            onHand=onHand.add(BigInteger.valueOf(line.onHand()));reserved=reserved.add(BigInteger.valueOf(line.reserved()));
            quarantined=quarantined.add(BigInteger.valueOf(line.quarantined()));blocked=blocked.add(BigInteger.valueOf(line.otherBlocked()));
            latestTrust=line.trust();
        }
        long version=lines.isEmpty()?0:lines.getLast().version();
        StockBuckets rebuilt=null;
        try { rebuilt=new StockBuckets(onHand.longValueExact(),reserved.longValueExact(),quarantined.longValueExact(),blocked.longValueExact()); }
        catch(ArithmeticException|IllegalArgumentException invalid) { if(ledgerFailure==null) ledgerFailure="LEDGER_BALANCE_INVALID"; }
        if(position.version()!=version && ledgerFailure==null) ledgerFailure="POSITION_VERSION_MISMATCH";
        if((position.initialization()==StockPosition.Initialization.INITIALIZED)!=!lines.isEmpty() && ledgerFailure==null) ledgerFailure="INITIALIZATION_MISMATCH";
        if(!position.trust().name().equals(latestTrust) && ledgerFailure==null) ledgerFailure="TRUST_STATE_MISMATCH";

        var allocationCheck=allocationEvidence(position.id(),allocations,transitions);
        if(!allocationCheck.valid() && ledgerFailure==null) ledgerFailure="ALLOCATION_HISTORY_MISMATCH";
        boolean allocationBucketsMatch=rebuilt!=null && allocationCheck.reserved()==rebuilt.reserved()
                && allocationCheck.quarantined()==rebuilt.quarantined() && allocationCheck.blocked()==rebuilt.otherBlocked();
        String code;String severity;
        if(ledgerFailure!=null) { code=ledgerFailure;severity="CRITICAL"; }
        else if(!allocationBucketsMatch) { code="ALLOCATION_BUCKET_MISMATCH";severity="CRITICAL"; }
        else if(rebuilt!=null && !position.buckets().equals(rebuilt)) { code="PROJECTION_MISMATCH";severity="CRITICAL"; }
        else if(position.initialization()==StockPosition.Initialization.NOT_INITIALIZED) { code="POSITION_NOT_INITIALIZED";severity="WARN"; }
        else if(position.trust()==Trust.FROZEN) { code="POSITION_FROZEN";severity="WARN"; }
        else { code="BALANCES_MATCH";severity="CLEAN"; }
        String expected=expectedJson(rebuilt,onHand,reserved,quarantined,blocked,version,latestTrust,ledgerFailure,allocationCheck);
        String observed=positionJson(position,allocationCheck);
        return new Analysis(position,severity,code,expected,observed,rebuilt,version,allocationCheck.valid()&&allocationBucketsMatch);
    }

    private AllocationCheck allocationEvidence(UUID position,List<Allocation> current,List<AllocationTransition> history) {
        var currentById=current.stream().collect(Collectors.toMap(Allocation::id,Function.identity()));
        var transitionGroups=group(history,AllocationTransition::id);
        BigInteger reserved=BigInteger.ZERO,quarantined=BigInteger.ZERO,blocked=BigInteger.ZERO;boolean valid=true;
        for(var entry:transitionGroups.entrySet()) {
            var rows=entry.getValue();long previousVersion=-1,remaining=0;String state=null;String bucket=rows.getFirst().bucket();UUID allocationId=entry.getKey();
            for(var row:rows) {
                if(row.version()!=previousVersion+1 || row.before()!=remaining || !row.bucket().equals(bucket)) valid=false;
                if(previousVersion<0 && (row.version()!=0 || row.before()!=0 || row.after()<=0 || !row.state().equals("ACTIVE")
                        || !row.transition().equals("CREATE"))) valid=false;
                if(previousVersion>=0 && !List.of("RELEASE","CONSUME","WRITE_OFF").contains(row.transition())) valid=false;
                if(previousVersion>=0 && (row.after()>=remaining || row.after()<0)) valid=false;
                if(row.after()>0 && !row.state().equals("ACTIVE")) valid=false;
                if(row.after()==0 && !(row.transition().equals("RELEASE")&&row.state().equals("RELEASED")
                        || row.transition().equals("WRITE_OFF")&&row.state().equals("WRITTEN_OFF")
                        || row.transition().equals("CONSUME")&&row.state().equals("CONSUMED"))) valid=false;
                previousVersion=row.version();remaining=row.after();state=row.state();
            }
            var live=currentById.remove(allocationId);
            if(live==null || live.initialQuantity()!=rows.getFirst().after() || live.version()!=previousVersion
                    || live.quantity()!=remaining || !live.state().equals(state)
                    || !live.bucket().equals(bucket) || !live.positionId().equals(position)) valid=false;
            if("ACTIVE".equals(state)) {
                try {
                    switch(bucket) {
                        case "RESERVED"->reserved=reserved.add(BigInteger.valueOf(remaining));
                        case "QUARANTINED"->quarantined=quarantined.add(BigInteger.valueOf(remaining));
                        case "OTHER_BLOCKED"->blocked=blocked.add(BigInteger.valueOf(remaining));
                        default->valid=false;
                    }
                } catch(ArithmeticException overflow) { valid=false; }
            }
        }
        if(!currentById.isEmpty()) valid=false;
        long reservedValue=exactOrSentinel(reserved),quarantinedValue=exactOrSentinel(quarantined),blockedValue=exactOrSentinel(blocked);
        if(reservedValue<0||quarantinedValue<0||blockedValue<0) valid=false;
        return new AllocationCheck(valid,reservedValue,quarantinedValue,blockedValue);
    }

    private String expectedJson(StockBuckets buckets,BigInteger onHand,BigInteger reserved,BigInteger quarantined,BigInteger blocked,
            long version,String trust,String failure,AllocationCheck allocations) {
        var values=new HashMap<String,Object>();
        values.put("positionVersion",version);values.put("trust",trust);
        if(buckets==null) { values.put("ledgerFailure",failure);values.put("onHandDeltaTotal",onHand.toString());
            values.put("reservedDeltaTotal",reserved.toString());values.put("quarantinedDeltaTotal",quarantined.toString());values.put("otherBlockedDeltaTotal",blocked.toString()); }
        else values.putAll(bucketValues(buckets));
        values.put("activeAllocationBuckets",Map.of("reserved",allocations.reserved(),"quarantined",allocations.quarantined(),"otherBlocked",allocations.blocked()));
        return json.writeValueAsString(values);
    }
    private String positionJson(StockPosition position,AllocationCheck allocations) {
        var values=new HashMap<String,Object>(bucketValues(position.buckets()));values.put("positionVersion",position.version());
        values.put("initialization",position.initialization().name());values.put("trust",position.trust().name());
        values.put("activeAllocationBuckets",Map.of("reserved",allocations.reserved(),"quarantined",allocations.quarantined(),"otherBlocked",allocations.blocked()));
        return json.writeValueAsString(values);
    }
    private Map<String,Object> bucketValues(StockBuckets buckets) {
        return Map.of("onHand",buckets.onHand(),"reserved",buckets.reserved(),"quarantined",buckets.quarantined(),
                "otherBlocked",buckets.otherBlocked(),"available",buckets.available());
    }
    private static long exactOrSentinel(BigInteger value) { try { return value.longValueExact(); } catch(ArithmeticException overflow) { return -1; } }
    private static <T,K> Map<K,List<T>> group(List<T> values,Function<T,K> key) {
        return values.stream().collect(Collectors.groupingBy(key,HashMap::new,Collectors.toList()));
    }
    private static int rank(String severity) { return switch(severity) { case "CRITICAL"->2;case "WARN"->1;default->0; }; }
    private static void validatePage(int offset,int limit) { if(offset<0||offset>10000||limit<1||limit>100) throw failure(Kind.INVALID_REQUEST); }
    private static String text(String value,int maximum) {
        if(value==null||value.isBlank()||value.length()>maximum||value.codePoints().anyMatch(Character::isISOControl)) throw failure(Kind.INVALID_REQUEST);
        return value.strip();
    }
    private static InventoryFailure failure(Kind kind) { return new InventoryFailure(kind); }
    private record AllocationCheck(boolean valid,long reserved,long quarantined,long blocked) {}
    private record Analysis(StockPosition position,String severity,String code,String expected,String observed,StockBuckets buckets,
            long version,boolean allocationEvidenceValid) {}
}
