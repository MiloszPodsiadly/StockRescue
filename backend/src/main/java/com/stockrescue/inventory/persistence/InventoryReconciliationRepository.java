package com.stockrescue.inventory.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryReconciliationRepository {
    public record JournalLine(UUID positionId,long version,long onHand,long reserved,long quarantined,long otherBlocked,String trust) {}
    public record Allocation(UUID id,UUID positionId,String bucket,long initialQuantity,long quantity,String state,long version) {}
    public record AllocationTransition(UUID id,UUID positionId,String bucket,long version,long before,long after,String state,String transition) {}
    public record Run(UUID id,UUID organizationId,UUID storeId,UUID actorId,UUID membershipId,String severity,int checkedPositions,
            Instant startedAt,Instant completedAt) {}
    public record Finding(UUID positionId,long positionVersion,String severity,String code,String expected,String observed) {}

    private final JdbcClient jdbc;

    public InventoryReconciliationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    public List<JournalLine> journalLines(UUID org,UUID store) {
        return jdbc.sql("""
                SELECT l.position_id,l.position_version,l.delta_on_hand,l.delta_reserved,l.delta_quarantined,l.delta_other_blocked,l.trust_after
                FROM stockrescue.stock_movement_line l
                WHERE l.org_id=:org AND l.store_id=:store ORDER BY l.position_id,l.position_version
                """).param("org",org).param("store",store).query((row,index)->new JournalLine(row.getObject("position_id",UUID.class),
                        row.getLong("position_version"),row.getLong("delta_on_hand"),row.getLong("delta_reserved"),
                        row.getLong("delta_quarantined"),row.getLong("delta_other_blocked"),row.getString("trust_after"))).list();
    }

    public List<JournalLine> journalLines(UUID org,UUID store,UUID position) {
        return jdbc.sql("""
                SELECT l.position_id,l.position_version,l.delta_on_hand,l.delta_reserved,l.delta_quarantined,l.delta_other_blocked,l.trust_after
                FROM stockrescue.stock_movement_line l
                WHERE l.org_id=:org AND l.store_id=:store AND l.position_id=:position ORDER BY l.position_version
                """).param("org",org).param("store",store).param("position",position).query((row,index)->new JournalLine(
                        row.getObject("position_id",UUID.class),row.getLong("position_version"),row.getLong("delta_on_hand"),
                        row.getLong("delta_reserved"),row.getLong("delta_quarantined"),row.getLong("delta_other_blocked"),
                        row.getString("trust_after"))).list();
    }

    public List<Allocation> allocations(UUID org,UUID store) {
        return jdbc.sql("""
                SELECT id,position_id,bucket,initial_quantity,remaining_quantity,state,version FROM stockrescue.stock_allocation
                WHERE org_id=:org AND store_id=:store ORDER BY position_id,id
                """).param("org",org).param("store",store).query((row,index)->new Allocation(row.getObject("id",UUID.class),
                        row.getObject("position_id",UUID.class),row.getString("bucket"),row.getLong("initial_quantity"),row.getLong("remaining_quantity"),
                        row.getString("state"),row.getLong("version"))).list();
    }

    public List<Allocation> allocations(UUID org,UUID store,UUID position) {
        return jdbc.sql("""
                SELECT id,position_id,bucket,initial_quantity,remaining_quantity,state,version FROM stockrescue.stock_allocation
                WHERE org_id=:org AND store_id=:store AND position_id=:position ORDER BY id
                """).param("org",org).param("store",store).param("position",position).query((row,index)->new Allocation(
                        row.getObject("id",UUID.class),row.getObject("position_id",UUID.class),row.getString("bucket"),row.getLong("initial_quantity"),
                        row.getLong("remaining_quantity"),row.getString("state"),row.getLong("version"))).list();
    }

    public List<AllocationTransition> allocationTransitions(UUID org,UUID store) {
        return jdbc.sql("""
                SELECT allocation_id,position_id,bucket,version,remaining_before,remaining_after,state_after,transition
                FROM stockrescue.stock_allocation_transition WHERE org_id=:org AND store_id=:store
                ORDER BY position_id,allocation_id,version
                """).param("org",org).param("store",store).query((row,index)->new AllocationTransition(
                        row.getObject("allocation_id",UUID.class),row.getObject("position_id",UUID.class),row.getString("bucket"),
                        row.getLong("version"),row.getLong("remaining_before"),row.getLong("remaining_after"),
                        row.getString("state_after"),row.getString("transition"))).list();
    }

    public List<AllocationTransition> allocationTransitions(UUID org,UUID store,UUID position) {
        return jdbc.sql("""
                SELECT allocation_id,position_id,bucket,version,remaining_before,remaining_after,state_after,transition
                FROM stockrescue.stock_allocation_transition WHERE org_id=:org AND store_id=:store AND position_id=:position
                ORDER BY allocation_id,version
                """).param("org",org).param("store",store).param("position",position).query((row,index)->new AllocationTransition(
                        row.getObject("allocation_id",UUID.class),row.getObject("position_id",UUID.class),row.getString("bucket"),
                        row.getLong("version"),row.getLong("remaining_before"),row.getLong("remaining_after"),
                        row.getString("state_after"),row.getString("transition"))).list();
    }

    public void recordRun(Run run) {
        jdbc.sql("""
                INSERT INTO stockrescue.inventory_reconciliation_run(id,org_id,store_id,actor_user_id,membership_id,severity,
                    checked_positions,started_at,completed_at)
                VALUES (:id,:org,:store,:actor,:member,:severity,:checked,:started,:completed)
                """).param("id",run.id()).param("org",run.organizationId()).param("store",run.storeId())
                .param("actor",run.actorId()).param("member",run.membershipId()).param("severity",run.severity())
                .param("checked",run.checkedPositions()).param("started",java.sql.Timestamp.from(run.startedAt()))
                .param("completed",java.sql.Timestamp.from(run.completedAt())).update();
    }

    public void recordFinding(UUID org,UUID store,UUID run,Finding finding) {
        jdbc.sql("""
                INSERT INTO stockrescue.inventory_reconciliation_result(org_id,store_id,run_id,position_id,position_version,severity,
                    finding_code,expected_buckets,observed_buckets)
                VALUES (:org,:store,:run,:position,:version,:severity,:code,CAST(:expected AS jsonb),CAST(:observed AS jsonb))
                """).param("org",org).param("store",store).param("run",run).param("position",finding.positionId())
                .param("version",finding.positionVersion()).param("severity",finding.severity()).param("code",finding.code())
                .param("expected",finding.expected()).param("observed",finding.observed()).update();
    }

    public boolean hasCriticalFinding(UUID org,UUID store,UUID run,UUID position) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM stockrescue.inventory_reconciliation_result
                    WHERE org_id=:org AND store_id=:store AND run_id=:run AND position_id=:position AND severity='CRITICAL')
                """).param("org",org).param("store",store).param("run",run).param("position",position).query(Boolean.class).single();
    }

    public Optional<Run> run(UUID org,UUID store,UUID run) {
        return jdbc.sql("SELECT * FROM stockrescue.inventory_reconciliation_run WHERE org_id=:org AND store_id=:store AND id=:id")
                .param("org",org).param("store",store).param("id",run).query((row,index)->mapRun(row)).optional();
    }

    public List<Run> runs(UUID org,UUID store,int offset,int limit) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.inventory_reconciliation_run WHERE org_id=:org AND store_id=:store
                ORDER BY completed_at DESC,id DESC LIMIT :limit OFFSET :offset
                """).param("org",org).param("store",store).param("offset",offset).param("limit",limit)
                .query((row,index)->mapRun(row)).list();
    }

    public List<Finding> findings(UUID org,UUID store,UUID run,int offset,int limit) {
        return jdbc.sql("""
                SELECT position_id,position_version,severity,finding_code,expected_buckets::text,observed_buckets::text
                FROM stockrescue.inventory_reconciliation_result WHERE org_id=:org AND store_id=:store AND run_id=:run
                ORDER BY position_id LIMIT :limit OFFSET :offset
                """).param("org",org).param("store",store).param("run",run).param("offset",offset).param("limit",limit)
                .query((row,index)->new Finding(row.getObject("position_id",UUID.class),row.getLong("position_version"),
                        row.getString("severity"),row.getString("finding_code"),row.getString("expected_buckets"),
                        row.getString("observed_buckets"))).list();
    }

    private static Run mapRun(ResultSet row) throws SQLException {
        return new Run(row.getObject("id",UUID.class),row.getObject("org_id",UUID.class),row.getObject("store_id",UUID.class),
                row.getObject("actor_user_id",UUID.class),row.getObject("membership_id",UUID.class),row.getString("severity"),
                row.getInt("checked_positions"),row.getTimestamp("started_at").toInstant(),row.getTimestamp("completed_at").toInstant());
    }
}
