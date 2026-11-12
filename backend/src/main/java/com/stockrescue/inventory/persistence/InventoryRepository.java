package com.stockrescue.inventory.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.time.Instant;

import com.stockrescue.inventory.domain.InventoryWriteConflict;
import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.inventory.domain.StockBuckets;
import com.stockrescue.inventory.domain.StockPosition;
import com.stockrescue.inventory.domain.StockPosition.Initialization;
import com.stockrescue.inventory.domain.StockPosition.Trust;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Transaction primitives. Application commands must authorize before taking inventory locks. */
@Repository
public class InventoryRepository {
    public enum Operation { RECEIVE, RESERVE, RELEASE, ADJUST, WRITE_OFF, QUARANTINE, HOLD, RELEASE_HOLD,
        SALE, VERIFY_ZERO, RECONCILE, REMEDIATE }

    public record CommandReceipt(UUID id, UUID organizationId, UUID storeId, UUID actorId, UUID membershipId,
            Operation operation, UUID requestId, byte[] payloadDigest, String outcome) {
        public CommandReceipt { payloadDigest = payloadDigest.clone(); }
        @Override public byte[] payloadDigest() { return payloadDigest.clone(); }
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public InventoryRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    public record Allocation(UUID id,UUID positionId,String bucket,UUID ownerId,long remaining,String state,long version) {}
    public record Movement(UUID id,Operation operation,long positionVersion,StockBuckets.Delta delta,String reason,String evidenceReference,Instant recordedAt) {}

    public void lockRequest(UUID org,UUID actor,Operation operation,UUID request) {
        requireWriteTransaction();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key",org+":"+actor+":"+operation+":"+request).query((row,index)->true).single();
    }

    /** Shared store lock keeps a reconciliation's store-wide position set stable without serializing stock commands. */
    public void lockStoreForWrite(UUID org,UUID store) {
        requireWriteTransaction();
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(hashtextextended(:key,0))")
                .param("key","inventory-store:"+org+":"+store).query((row,index)->true).single();
    }

    /** Exclusive counterpart to stock commands' shared lock; acquired after current store authorization. */
    public void lockStoreForReconciliation(UUID org,UUID store) {
        requireWriteTransaction();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key","inventory-store:"+org+":"+store).query((row,index)->true).single();
    }

    /** Store authority is locked first; catalog writers use product then lot in the same order. */
    public void lockActiveContext(UUID org,UUID store,UUID location,UUID product,UUID lot) {
        requireWriteTransaction();
        boolean productActive=jdbc.sql("SELECT active FROM stockrescue.product WHERE org_id=:org AND id=:id FOR SHARE")
                .param("org",org).param("id",product).query(Boolean.class).optional().orElseThrow(OrganizationUnavailableException::new);
        boolean lotActive=jdbc.sql("SELECT active FROM stockrescue.manufacturer_lot WHERE org_id=:org AND product_id=:product AND id=:id FOR SHARE")
                .param("org",org).param("product",product).param("id",lot).query(Boolean.class).optional().orElseThrow(OrganizationUnavailableException::new);
        boolean locationActive=jdbc.sql("""
                SELECT l.active AND z.active AS active FROM stockrescue.storage_location l
                JOIN stockrescue.storage_zone z ON z.org_id=l.org_id AND z.store_id=l.store_id AND z.id=l.zone_id
                WHERE l.org_id=:org AND l.store_id=:store AND l.id=:id FOR SHARE OF l,z
                """).param("org",org).param("store",store).param("id",location).query(Boolean.class).optional().orElseThrow(OrganizationUnavailableException::new);
        if (!productActive || !lotActive || !locationActive) throw new InventoryFailure(InventoryFailure.Kind.INACTIVE);
    }

    public Optional<StockPosition> positionByIdentity(UUID org,UUID store,UUID location,UUID lot) {
        return jdbc.sql("SELECT * FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND location_id=:location AND lot_id=:lot")
                .param("org",org).param("store",store).param("location",location).param("lot",lot).query((row,index)->position(row)).optional();
    }

    public List<StockPosition> positions(UUID org,UUID store,UUID lot,int offset,int limit) {
        return positions(org,store,null,lot,null,offset,limit);
    }

    public List<StockPosition> positions(UUID org,UUID store,UUID product,UUID lot,UUID location,int offset,int limit) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store
                    AND (CAST(:product AS uuid) IS NULL OR product_id=:product)
                    AND (CAST(:lot AS uuid) IS NULL OR lot_id=:lot)
                    AND (CAST(:location AS uuid) IS NULL OR location_id=:location)
                ORDER BY product_id,lot_id,location_id,id LIMIT :limit OFFSET :offset
                """).param("org",org).param("store",store).param("product",product,java.sql.Types.OTHER)
                .param("lot",lot,java.sql.Types.OTHER).param("location",location,java.sql.Types.OTHER)
                .param("offset",offset).param("limit",limit).query((row,index)->position(row)).list();
    }

    public List<Allocation> allocations(UUID org,UUID store,UUID position,int offset,int limit) {
        return jdbc.sql("""
                SELECT id,position_id,bucket,owner_user_id,remaining_quantity,state,version
                FROM stockrescue.stock_allocation WHERE org_id=:org AND store_id=:store AND position_id=:position
                ORDER BY bucket,id LIMIT :limit OFFSET :offset
                """).param("org",org).param("store",store).param("position",position).param("offset",offset).param("limit",limit)
                .query((row,index)->new Allocation(row.getObject("id",UUID.class),row.getObject("position_id",UUID.class),
                        row.getString("bucket"),row.getObject("owner_user_id",UUID.class),row.getLong("remaining_quantity"),
                        row.getString("state"),row.getLong("version"))).list();
    }

    public List<StockPosition> lockPositionsForReconciliation(UUID org,UUID store) {
        requireWriteTransaction();
        return jdbc.sql("SELECT * FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store ORDER BY id FOR UPDATE")
                .param("org",org).param("store",store).query((row,index)->position(row)).list();
    }

    public List<Movement> history(UUID org,UUID store,UUID position,long afterVersion,int limit) {
        return jdbc.sql("""
                SELECT m.id,m.operation,l.position_version,l.delta_on_hand,l.delta_reserved,l.delta_quarantined,l.delta_other_blocked,
                    m.reason,m.evidence_reference,m.recorded_at FROM stockrescue.stock_movement_line l
                JOIN stockrescue.stock_movement m ON m.org_id=l.org_id AND m.store_id=l.store_id AND m.id=l.movement_id
                WHERE l.org_id=:org AND l.store_id=:store AND l.position_id=:position AND l.position_version>:after
                ORDER BY l.position_version LIMIT :limit
                """).param("org",org).param("store",store).param("position",position).param("after",afterVersion).param("limit",limit)
                .query((row,index)->new Movement(row.getObject("id",UUID.class),Operation.valueOf(row.getString("operation")),row.getLong("position_version"),
                        new StockBuckets.Delta(row.getLong("delta_on_hand"),row.getLong("delta_reserved"),row.getLong("delta_quarantined"),row.getLong("delta_other_blocked")),
                        row.getString("reason"),row.getString("evidence_reference"),row.getTimestamp("recorded_at").toInstant())).list();
    }

    public Optional<Allocation> allocation(UUID org,UUID store,UUID position,UUID id) {
        requireWriteTransaction();
        return jdbc.sql("SELECT * FROM stockrescue.stock_allocation WHERE org_id=:org AND store_id=:store AND position_id=:position AND id=:id FOR UPDATE")
                .param("org",org).param("store",store).param("position",position).param("id",id)
                .query((row,index)->new Allocation(id,position,row.getString("bucket"),row.getObject("owner_user_id",UUID.class),
                        row.getLong("remaining_quantity"),row.getString("state"),row.getLong("version"))).optional();
    }

    public void openAllocation(UUID org,UUID store,UUID position,UUID id,String bucket,UUID owner,UUID membership,long quantity,UUID movement) {
        requireWriteTransaction();
        jdbc.sql("""
                INSERT INTO stockrescue.stock_allocation(id,org_id,store_id,position_id,bucket,owner_user_id,owner_membership_id,
                    initial_quantity,remaining_quantity,state) VALUES (:id,:org,:store,:position,:bucket,:owner,:member,:quantity,:quantity,'ACTIVE')
                """).param("id",id).param("org",org).param("store",store).param("position",position).param("bucket",bucket)
                .param("owner",owner).param("member",membership).param("quantity",quantity).update();
        appendAllocationTransition(org,store,id,position,bucket,movement,0,0,quantity,"ACTIVE","CREATE");
    }

    public void reduceAllocation(UUID org,UUID store,Allocation allocation,long quantity,String transition,UUID movement) {
        requireWriteTransaction();
        long remaining=allocation.remaining()-quantity;
        String state=remaining>0?"ACTIVE":switch(transition) { case "RELEASE"->"RELEASED"; case "WRITE_OFF"->"WRITTEN_OFF"; case "CONSUME"->"CONSUMED"; default->throw new IllegalArgumentException(); };
        long version=Math.incrementExact(allocation.version());
        int changed=jdbc.sql("""
                UPDATE stockrescue.stock_allocation SET remaining_quantity=:remaining,state=:state,version=:version
                WHERE org_id=:org AND store_id=:store AND id=:id AND version=:expected AND state='ACTIVE'
                """).param("remaining",remaining).param("state",state).param("version",version).param("org",org).param("store",store)
                .param("id",allocation.id()).param("expected",allocation.version()).update();
        if(changed!=1) throw new InventoryWriteConflict();
        appendAllocationTransition(org,store,allocation.id(),allocation.positionId(),allocation.bucket(),movement,version,allocation.remaining(),remaining,state,transition);
    }

    private void appendAllocationTransition(UUID org,UUID store,UUID id,UUID position,String bucket,UUID movement,long version,long before,long after,String state,String transition) {
        jdbc.sql("""
                INSERT INTO stockrescue.stock_allocation_transition(org_id,store_id,allocation_id,position_id,bucket,movement_id,version,
                    remaining_before,remaining_after,state_after,transition) VALUES (:org,:store,:id,:position,:bucket,:movement,:version,:before,:after,:state,:transition)
                """).param("org",org).param("store",store).param("id",id).param("position",position).param("bucket",bucket)
                .param("movement",movement).param("version",version).param("before",before).param("after",after).param("state",state).param("transition",transition).update();
    }

    public void recordReceipt(UUID id,UUID org,UUID store,UUID movement,UUID position,String supplier,String type,String document,String line,Instant receivedAt,long quantity,boolean quarantine) {
        requireWriteTransaction();
        int changed=jdbc.sql("""
                INSERT INTO stockrescue.goods_receipt(id,org_id,store_id,movement_id,supplier_key,document_type,document_reference,physical_receipt_at)
                VALUES (:id,:org,:store,:movement,:supplier,:type,:document,:received)
                ON CONFLICT (org_id,supplier_key,document_type,document_reference) DO NOTHING
                """).param("id",id).param("org",org).param("store",store).param("movement",movement).param("supplier",supplier).param("type",type)
                .param("document",document).param("received",java.sql.Timestamp.from(receivedAt)).update();
        if (changed!=1) throw new InventoryFailure(InventoryFailure.Kind.DOCUMENT_ALREADY_RECEIVED);
        jdbc.sql("""
                INSERT INTO stockrescue.goods_receipt_line(org_id,store_id,receipt_id,movement_id,position_id,document_line_reference,quantity,disposition)
                VALUES (:org,:store,:id,:movement,:position,:line,:quantity,:disposition)
                """).param("org",org).param("store",store).param("id",id).param("movement",movement).param("position",position).param("line",line)
                .param("quantity",quantity).param("disposition",quarantine?"QUARANTINED":"AVAILABLE").update();
    }

    public Optional<StockPosition> position(UUID org, UUID store, UUID id, boolean lock) {
        if (lock) requireWriteTransaction();
        return jdbc.sql("SELECT * FROM stockrescue.stock_position WHERE org_id=:org AND store_id=:store AND id=:id"
                +(lock ? " FOR UPDATE" : ""))
                .param("org",org).param("store",store).param("id",id).query((row,index)->position(row)).optional();
    }

    public StockPosition createPosition(UUID id, UUID org, UUID store, UUID location, UUID product, UUID lot) {
        requireWriteTransaction();
        jdbc.sql("""
                INSERT INTO stockrescue.stock_position(id,org_id,store_id,location_id,product_id,lot_id)
                VALUES (:id,:org,:store,:location,:product,:lot)
                ON CONFLICT (org_id,store_id,location_id,lot_id) DO NOTHING
                """).param("id",id).param("org",org).param("store",store).param("location",location)
                .param("product",product).param("lot",lot).update();
        return positionByIdentity(org,store,location,lot).orElseThrow();
    }

    public Optional<CommandReceipt> command(UUID org, UUID actor, Operation operation, UUID requestId) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.inventory_command_receipt
                WHERE org_id=:org AND actor_user_id=:actor AND operation=:operation AND request_id=:request
                """).param("org",org).param("actor",actor).param("operation",operation.name()).param("request",requestId)
                .query((row,index)->new CommandReceipt(row.getObject("id",UUID.class),org,row.getObject("store_id",UUID.class),
                        actor,row.getObject("membership_id",UUID.class),operation,requestId,row.getBytes("payload_digest"),row.getString("outcome")))
                .optional();
    }

    public boolean recordCommand(CommandReceipt receipt) {
        requireWriteTransaction();
        return jdbc.sql("""
                INSERT INTO stockrescue.inventory_command_receipt
                    (id,org_id,store_id,actor_user_id,membership_id,operation,request_id,payload_digest,outcome)
                VALUES (:id,:org,:store,:actor,:member,:operation,:request,:digest,CAST(:outcome AS jsonb))
                ON CONFLICT (org_id,actor_user_id,operation,request_id) DO NOTHING
                """).param("id",receipt.id()).param("org",receipt.organizationId()).param("store",receipt.storeId())
                .param("actor",receipt.actorId()).param("member",receipt.membershipId()).param("operation",receipt.operation().name())
                .param("request",receipt.requestId()).param("digest",receipt.payloadDigest()).param("outcome",receipt.outcome()).update()==1;
    }

    public void recordMovement(UUID id, CommandReceipt command, String reason, String evidenceReference) {
        requireWriteTransaction();
        jdbc.sql("""
                INSERT INTO stockrescue.stock_movement(id,org_id,store_id,command_id,operation,reason,evidence_reference)
                VALUES (:id,:org,:store,:command,:operation,:reason,:evidence)
                """).param("id",id).param("org",command.organizationId()).param("store",command.storeId())
                .param("command",command.id()).param("operation",command.operation().name()).param("reason",reason)
                .param("evidence",evidenceReference).update();
    }

    /** Posts signed evidence, its CAS projection and pending event in the caller's transaction. */
    public StockPosition postLine(UUID org, UUID store, UUID movement, UUID positionId, long expectedVersion,
            StockBuckets.Delta delta, Trust trust) {
        requireWriteTransaction();
        var before=position(org,store,positionId,true).orElseThrow(InventoryWriteConflict::new);
        if (before.version()!=expectedVersion) throw new InventoryWriteConflict();
        var next=before.buckets().apply(delta);
        long version=Math.incrementExact(before.version());
        jdbc.sql("""
                INSERT INTO stockrescue.stock_movement_line(org_id,store_id,movement_id,position_id,position_version,
                    delta_on_hand,delta_reserved,delta_quarantined,delta_other_blocked,initialization_after,trust_after)
                VALUES (:org,:store,:movement,:position,:version,:onHand,:reserved,:quarantined,:blocked,'INITIALIZED',:trust)
                """).param("org",org).param("store",store).param("movement",movement).param("position",positionId)
                .param("version",version).param("onHand",delta.onHand()).param("reserved",delta.reserved())
                .param("quarantined",delta.quarantined()).param("blocked",delta.otherBlocked()).param("trust",trust.name()).update();
        int changed=jdbc.sql("""
                UPDATE stockrescue.stock_position SET on_hand=:onHand,reserved=:reserved,quarantined=:quarantined,
                    other_blocked=:blocked,initialization_state='INITIALIZED',trust_state=:trust,version=:version
                WHERE org_id=:org AND store_id=:store AND id=:id AND version=:expected
                """).param("onHand",next.onHand()).param("reserved",next.reserved()).param("quarantined",next.quarantined())
                .param("blocked",next.otherBlocked()).param("trust",trust.name()).param("version",version)
                .param("org",org).param("store",store).param("id",positionId).param("expected",expectedVersion).update();
        if (changed!=1) throw new InventoryWriteConflict();
        var after=new StockPosition(positionId,org,store,before.locationId(),before.productId(),before.lotId(),
                Initialization.INITIALIZED,trust,next,version);
        recordEvent(movement,after,delta);
        return after;
    }

    /** Rebuilds only the derived position projection from immutable evidence under a repair record. */
    public StockPosition rebuildProjection(UUID org,UUID store,UUID movement,UUID runId,UUID repairId,
            UUID actor,UUID membership,UUID positionId,long expectedVersion,StockBuckets rebuilt,String reason,String evidence) {
        requireWriteTransaction();
        var before=position(org,store,positionId,true).orElseThrow(InventoryWriteConflict::new);
        if(before.version()!=expectedVersion || before.trust()!=Trust.FROZEN) throw new InventoryWriteConflict();
        long version=Math.incrementExact(before.version());
        jdbc.sql("""
                INSERT INTO stockrescue.inventory_projection_repair(id,org_id,store_id,run_id,position_id,actor_user_id,membership_id,
                    prior_version,repaired_version,on_hand,reserved,quarantined,other_blocked,trust_state,reason,evidence_reference)
                VALUES (:id,:org,:store,:run,:position,:actor,:member,:prior,:version,:onHand,:reserved,:quarantined,:blocked,'CLEAN',:reason,:evidence)
                """).param("id",repairId).param("org",org).param("store",store).param("run",runId).param("position",positionId)
                .param("actor",actor).param("member",membership).param("prior",expectedVersion).param("version",version)
                .param("onHand",rebuilt.onHand()).param("reserved",rebuilt.reserved()).param("quarantined",rebuilt.quarantined())
                .param("blocked",rebuilt.otherBlocked()).param("reason",reason).param("evidence",evidence).update();
        jdbc.sql("""
                INSERT INTO stockrescue.stock_movement_line(org_id,store_id,movement_id,position_id,position_version,
                    delta_on_hand,delta_reserved,delta_quarantined,delta_other_blocked,initialization_after,trust_after)
                VALUES (:org,:store,:movement,:position,:version,0,0,0,0,'INITIALIZED','CLEAN')
                """).param("org",org).param("store",store).param("movement",movement).param("position",positionId).param("version",version).update();
        int changed=jdbc.sql("""
                UPDATE stockrescue.stock_position SET on_hand=:onHand,reserved=:reserved,quarantined=:quarantined,
                    other_blocked=:blocked,initialization_state='INITIALIZED',trust_state='CLEAN',version=:version
                WHERE org_id=:org AND store_id=:store AND id=:id AND version=:expected AND trust_state='FROZEN'
                """).param("onHand",rebuilt.onHand()).param("reserved",rebuilt.reserved()).param("quarantined",rebuilt.quarantined())
                .param("blocked",rebuilt.otherBlocked()).param("version",version).param("org",org).param("store",store)
                .param("id",positionId).param("expected",expectedVersion).update();
        if(changed!=1) throw new InventoryWriteConflict();
        var after=new StockPosition(before.id(),org,store,before.locationId(),before.productId(),before.lotId(),Initialization.INITIALIZED,
                Trust.CLEAN,rebuilt,version);
        recordEvent(movement,after,new StockBuckets.Delta(0,0,0,0));
        return after;
    }

    private void recordEvent(UUID movement, StockPosition position, StockBuckets.Delta delta) {
        var event=UUID.randomUUID();
        var recordedAt=jdbc.sql("SELECT recorded_at FROM stockrescue.stock_movement WHERE org_id=:org AND store_id=:store AND id=:id")
                .param("org",position.organizationId()).param("store",position.storeId()).param("id",movement)
                .query((row,index)->row.getTimestamp(1).toInstant()).single();
        var envelope=new LinkedHashMap<String,Object>(Map.of("eventId",event,"schemaVersion",1,"eventType","InventoryStockChanged",
                "organizationId",position.organizationId(),"storeId",position.storeId(),"positionId",position.id(),
                "aggregateVersion",position.version(),"movementId",movement,"occurredAt",recordedAt,
                "change",Map.of("unit","EA","delta",delta,"position",position)));
        envelope.put("correlationId",MDC.get("correlationId"));
        var payload=json.writeValueAsString(envelope);
        jdbc.sql("""
                INSERT INTO stockrescue.inventory_outbox_event(id,org_id,store_id,movement_id,position_id,aggregate_version,
                    event_type,schema_version,payload,correlation_id)
                VALUES (:id,:org,:store,:movement,:position,:version,'InventoryStockChanged',1,CAST(:payload AS jsonb),:correlation)
                """).param("id",event).param("org",position.organizationId()).param("store",position.storeId())
                .param("movement",movement).param("position",position.id()).param("version",position.version()).param("payload",payload)
                .param("correlation",MDC.get("correlationId"),java.sql.Types.VARCHAR).update();
        jdbc.sql("INSERT INTO stockrescue.inventory_outbox_delivery(event_id,org_id,store_id) VALUES (:id,:org,:store)")
                .param("id",event).param("org",position.organizationId()).param("store",position.storeId()).update();
    }

    private StockPosition position(ResultSet row) throws SQLException {
        return new StockPosition(row.getObject("id",UUID.class),row.getObject("org_id",UUID.class),row.getObject("store_id",UUID.class),
                row.getObject("location_id",UUID.class),row.getObject("product_id",UUID.class),row.getObject("lot_id",UUID.class),
                Initialization.valueOf(row.getString("initialization_state")),Trust.valueOf(row.getString("trust_state")),
                new StockBuckets(row.getLong("on_hand"),row.getLong("reserved"),row.getLong("quarantined"),row.getLong("other_blocked")),row.getLong("version"));
    }

    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Inventory writes and locks require the command transaction");
        }
    }
}
