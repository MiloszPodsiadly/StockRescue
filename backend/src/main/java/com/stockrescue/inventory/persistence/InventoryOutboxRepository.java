package com.stockrescue.inventory.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InventoryOutboxRepository {
    public record OutboxEvent(UUID id, UUID organizationId, UUID storeId, UUID movementId, UUID positionId,
            long aggregateVersion, String eventType, int schemaVersion, String payload, String correlationId,
            Instant occurredAt, UUID leaseOwner, long claimVersion, int attemptCount, Instant leaseUntil) { }

    private final JdbcClient jdbc;

    public InventoryOutboxRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Transactional
    public List<OutboxEvent> claimDue(UUID owner, int limit, Duration lease) {
        if(limit<1 || lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("A positive bounded claim is required");
        return jdbc.sql("""
                WITH due AS (
                    SELECT d.event_id
                    FROM stockrescue.inventory_outbox_delivery d
                    JOIN stockrescue.inventory_outbox_event e ON e.id=d.event_id
                    JOIN stockrescue.stock_movement m ON m.org_id=e.org_id AND m.store_id=e.store_id AND m.id=e.movement_id
                    WHERE d.published_at IS NULL AND d.parked_at IS NULL AND d.next_attempt_at<=clock_timestamp()
                        AND (d.lease_until IS NULL OR d.lease_until<=clock_timestamp())
                        AND NOT EXISTS (
                            SELECT 1 FROM stockrescue.inventory_outbox_event prior
                            JOIN stockrescue.inventory_outbox_delivery prior_delivery ON prior_delivery.event_id=prior.id
                            WHERE prior.org_id=e.org_id AND prior.store_id=e.store_id AND prior.position_id=e.position_id
                                AND prior.aggregate_version<e.aggregate_version AND prior_delivery.published_at IS NULL
                        )
                    ORDER BY m.recorded_at,e.id
                    LIMIT :limit
                    FOR UPDATE OF d SKIP LOCKED
                ), claimed AS (
                    UPDATE stockrescue.inventory_outbox_delivery d
                    SET lease_owner=:owner,lease_until=clock_timestamp()+(:leaseMillis * interval '1 millisecond'),
                        claim_version=d.claim_version+1,attempt_count=d.attempt_count+1
                    FROM due WHERE d.event_id=due.event_id
                    RETURNING d.event_id,d.lease_owner,d.claim_version,d.attempt_count,d.lease_until
                )
                SELECT e.id,e.org_id,e.store_id,e.movement_id,e.position_id,e.aggregate_version,e.event_type,e.schema_version,
                    e.payload::text AS payload,e.correlation_id,m.recorded_at AS occurred_at,c.lease_owner,c.claim_version,
                    c.attempt_count,c.lease_until
                FROM claimed c
                JOIN stockrescue.inventory_outbox_event e ON e.id=c.event_id
                JOIN stockrescue.stock_movement m ON m.org_id=e.org_id AND m.store_id=e.store_id AND m.id=e.movement_id
                ORDER BY m.recorded_at,e.id
                """).param("owner",owner).param("limit",limit).param("leaseMillis",lease.toMillis())
                .query(this::event).list();
    }

    @Transactional
    public boolean markPublished(OutboxEvent claim) {
        return jdbc.sql("""
                UPDATE stockrescue.inventory_outbox_delivery
                SET published_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,last_error_code=NULL
                WHERE event_id=:event AND lease_owner=:owner AND claim_version=:version AND lease_until>clock_timestamp()
                    AND published_at IS NULL AND parked_at IS NULL
                """).param("event",claim.id()).param("owner",claim.leaseOwner()).param("version",claim.claimVersion())
                .update()==1;
    }

    @Transactional
    public boolean scheduleRetry(OutboxEvent claim, String code, Duration delay) {
        return jdbc.sql("""
                UPDATE stockrescue.inventory_outbox_delivery
                SET next_attempt_at=clock_timestamp()+(:delayMillis * interval '1 millisecond'),
                    lease_owner=NULL,lease_until=NULL,last_error_code=:code,last_error_at=clock_timestamp()
                WHERE event_id=:event AND lease_owner=:owner AND claim_version=:version AND lease_until>clock_timestamp()
                    AND published_at IS NULL AND parked_at IS NULL
                """).param("delayMillis",delay.toMillis()).param("code",code).param("event",claim.id())
                .param("owner",claim.leaseOwner()).param("version",claim.claimVersion()).update()==1;
    }

    @Transactional
    public boolean park(OutboxEvent claim, String code) {
        return jdbc.sql("""
                UPDATE stockrescue.inventory_outbox_delivery
                SET parked_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,last_error_code=:code,last_error_at=clock_timestamp()
                WHERE event_id=:event AND lease_owner=:owner AND claim_version=:version AND lease_until>clock_timestamp()
                    AND published_at IS NULL AND parked_at IS NULL
                """).param("code",code).param("event",claim.id()).param("owner",claim.leaseOwner())
                .param("version",claim.claimVersion()).update()==1;
    }

    public OutboxEvent find(UUID eventId) {
        return jdbc.sql("""
                SELECT e.id,e.org_id,e.store_id,e.movement_id,e.position_id,e.aggregate_version,e.event_type,e.schema_version,
                    e.payload::text AS payload,e.correlation_id,m.recorded_at AS occurred_at,
                    d.lease_owner,d.claim_version,d.attempt_count,d.lease_until
                FROM stockrescue.inventory_outbox_event e
                JOIN stockrescue.inventory_outbox_delivery d ON d.event_id=e.id
                JOIN stockrescue.stock_movement m ON m.org_id=e.org_id AND m.store_id=e.store_id AND m.id=e.movement_id
                WHERE e.id=:event
                """).param("event",eventId).query(this::event).optional().orElse(null);
    }

    public long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM stockrescue.inventory_outbox_delivery WHERE published_at IS NULL")
                .query(Long.class).single();
    }

    public double oldestPendingSeconds() {
        return jdbc.sql("""
                SELECT coalesce(greatest(extract(epoch FROM clock_timestamp()-min(m.recorded_at)),0),0)
                FROM stockrescue.inventory_outbox_delivery d
                JOIN stockrescue.inventory_outbox_event e ON e.id=d.event_id
                JOIN stockrescue.stock_movement m ON m.org_id=e.org_id AND m.store_id=e.store_id AND m.id=e.movement_id
                WHERE d.published_at IS NULL
                """).query(Double.class).single();
    }

    public long parkedCount() {
        return jdbc.sql("SELECT count(*) FROM stockrescue.inventory_outbox_delivery WHERE parked_at IS NOT NULL")
                .query(Long.class).single();
    }

    public long totalAttempts() {
        return jdbc.sql("SELECT coalesce(sum(attempt_count),0) FROM stockrescue.inventory_outbox_delivery")
                .query(Long.class).single();
    }

    public long deliveriesWithErrors() {
        return jdbc.sql("SELECT count(*) FROM stockrescue.inventory_outbox_delivery WHERE last_error_code IS NOT NULL")
                .query(Long.class).single();
    }

    public double projectionStalenessSeconds(String consumerName) {
        return jdbc.sql("""
                WITH latest AS (
                    SELECT DISTINCT ON (e.org_id,e.store_id,e.position_id) e.org_id,e.store_id,e.position_id,
                        e.aggregate_version,m.recorded_at
                    FROM stockrescue.inventory_outbox_event e
                    JOIN stockrescue.stock_movement m ON m.org_id=e.org_id AND m.store_id=e.store_id AND m.id=e.movement_id
                    ORDER BY e.org_id,e.store_id,e.position_id,e.aggregate_version DESC
                )
                SELECT coalesce(max(greatest(extract(epoch FROM clock_timestamp()-latest.recorded_at),0)),0)
                FROM latest LEFT JOIN stockrescue.inventory_change_projection p
                    ON p.consumer_name=:consumer AND p.org_id=latest.org_id AND p.store_id=latest.store_id
                    AND p.position_id=latest.position_id
                WHERE p.aggregate_version IS NULL OR p.aggregate_version<latest.aggregate_version
                """).param("consumer",consumerName).query(Double.class).single();
    }

    private OutboxEvent event(ResultSet row, int index) throws SQLException {
        return new OutboxEvent(row.getObject("id",UUID.class),row.getObject("org_id",UUID.class),
                row.getObject("store_id",UUID.class),row.getObject("movement_id",UUID.class),
                row.getObject("position_id",UUID.class),row.getLong("aggregate_version"),row.getString("event_type"),
                row.getInt("schema_version"),row.getString("payload"),row.getString("correlation_id"),
                row.getTimestamp("occurred_at").toInstant(),row.getObject("lease_owner",UUID.class),row.getLong("claim_version"),
                row.getInt("attempt_count"),row.getTimestamp("lease_until")==null?null:row.getTimestamp("lease_until").toInstant());
    }
}
