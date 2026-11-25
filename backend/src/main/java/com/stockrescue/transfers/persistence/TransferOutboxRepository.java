package com.stockrescue.transfers.persistence;

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
public class TransferOutboxRepository {
    public record OutboxEvent(UUID id, UUID organizationId, UUID transferId, long aggregateVersion,
            String eventType, int schemaVersion, String payload, String correlationId, Instant occurredAt,
            UUID leaseOwner, long claimVersion, int attemptCount, Instant leaseUntil) { }

    private final JdbcClient jdbc;

    public TransferOutboxRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Transactional
    public List<OutboxEvent> claimDue(UUID owner,int limit,Duration lease) {
        if(limit<1 || lease.isNegative() || lease.isZero() || lease.compareTo(Duration.ofMinutes(5))>0)
            throw new IllegalArgumentException("A bounded transfer outbox claim is required");
        return jdbc.sql("""
                WITH due AS (
                    SELECT d.event_id
                    FROM stockrescue.transfer_outbox_delivery d
                    JOIN stockrescue.transfer_outbox_event e ON e.org_id=d.org_id AND e.transfer_id=d.transfer_id AND e.id=d.event_id
                    WHERE d.published_at IS NULL AND d.parked_at IS NULL AND d.next_attempt_at<=clock_timestamp()
                        AND (d.lease_until IS NULL OR d.lease_until<=clock_timestamp())
                        AND NOT EXISTS (
                            SELECT 1 FROM stockrescue.transfer_outbox_event prior
                            JOIN stockrescue.transfer_outbox_delivery prior_delivery
                              ON prior_delivery.org_id=prior.org_id AND prior_delivery.transfer_id=prior.transfer_id
                             AND prior_delivery.event_id=prior.id
                            WHERE prior.org_id=e.org_id AND prior.transfer_id=e.transfer_id
                                AND prior.aggregate_version<e.aggregate_version AND prior_delivery.published_at IS NULL
                                AND NOT EXISTS (SELECT 1 FROM stockrescue.transfer_outbox_redrive_attempt ack
                                    WHERE ack.event_id=prior.id AND ack.published_at IS NOT NULL)
                        )
                    ORDER BY e.recorded_at,e.id LIMIT :limit FOR UPDATE OF d SKIP LOCKED
                ), claimed AS (
                    UPDATE stockrescue.transfer_outbox_delivery d
                    SET lease_owner=:owner,lease_until=clock_timestamp()+(:leaseMillis * interval '1 millisecond'),
                        claim_version=d.claim_version+1,attempt_count=d.attempt_count+1
                    FROM due WHERE d.event_id=due.event_id
                    RETURNING d.event_id,d.lease_owner,d.claim_version,d.attempt_count,d.lease_until
                )
                SELECT e.id,e.org_id,e.transfer_id,e.aggregate_version,e.event_type,e.schema_version,e.payload::text payload,
                    e.correlation_id,e.recorded_at,c.lease_owner,c.claim_version,c.attempt_count,c.lease_until
                FROM claimed c JOIN stockrescue.transfer_outbox_event e ON e.id=c.event_id
                ORDER BY e.recorded_at,e.id
                """).param("owner",owner).param("limit",limit).param("leaseMillis",lease.toMillis())
                .query(this::event).list();
    }

    @Transactional
    public boolean markPublished(OutboxEvent claim) {
        return jdbc.sql("""
                UPDATE stockrescue.transfer_outbox_delivery
                SET published_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,last_error_code=NULL,last_error_at=NULL
                WHERE event_id=:event AND lease_owner=:owner AND claim_version=:version AND lease_until>clock_timestamp()
                    AND published_at IS NULL AND parked_at IS NULL
                """).param("event",claim.id()).param("owner",claim.leaseOwner()).param("version",claim.claimVersion()).update()==1;
    }

    @Transactional
    public boolean scheduleRetry(OutboxEvent claim,String code,Duration delay) {
        if(delay.isNegative() || delay.isZero() || delay.compareTo(Duration.ofMinutes(5))>0)
            throw new IllegalArgumentException("Transfer outbox retry delay is outside its bound");
        return jdbc.sql("""
                UPDATE stockrescue.transfer_outbox_delivery
                SET next_attempt_at=clock_timestamp()+(:delayMillis * interval '1 millisecond'),
                    lease_owner=NULL,lease_until=NULL,last_error_code=:code,last_error_at=clock_timestamp()
                WHERE event_id=:event AND lease_owner=:owner AND claim_version=:version AND lease_until>clock_timestamp()
                    AND published_at IS NULL AND parked_at IS NULL
                """).param("delayMillis",delay.toMillis()).param("code",code).param("event",claim.id())
                .param("owner",claim.leaseOwner()).param("version",claim.claimVersion()).update()==1;
    }

    @Transactional
    public boolean park(OutboxEvent claim,String code) {
        return jdbc.sql("""
                UPDATE stockrescue.transfer_outbox_delivery
                SET parked_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,last_error_code=:code,last_error_at=clock_timestamp()
                WHERE event_id=:event AND lease_owner=:owner AND claim_version=:version AND lease_until>clock_timestamp()
                    AND published_at IS NULL AND parked_at IS NULL
                """).param("code",code).param("event",claim.id()).param("owner",claim.leaseOwner())
                .param("version",claim.claimVersion()).update()==1;
    }

    public OutboxEvent find(UUID eventId) {
        return jdbc.sql("""
                SELECT e.id,e.org_id,e.transfer_id,e.aggregate_version,e.event_type,e.schema_version,e.payload::text payload,
                    e.correlation_id,e.recorded_at,d.lease_owner,d.claim_version,d.attempt_count,d.lease_until
                FROM stockrescue.transfer_outbox_event e JOIN stockrescue.transfer_outbox_delivery d
                  ON d.org_id=e.org_id AND d.transfer_id=e.transfer_id AND d.event_id=e.id WHERE e.id=:event
                """).param("event",eventId).query(this::event).optional().orElse(null);
    }

    public long pendingCount() {
        return jdbc.sql("""
                SELECT count(*) FROM stockrescue.transfer_outbox_delivery d WHERE d.published_at IS NULL
                    AND NOT EXISTS (SELECT 1 FROM stockrescue.transfer_outbox_redrive_attempt a
                        WHERE a.event_id=d.event_id AND a.published_at IS NOT NULL)
                """)
                .query(Long.class).single();
    }
    public double oldestPendingSeconds() {
        return jdbc.sql("""
                SELECT coalesce(greatest(extract(epoch FROM clock_timestamp()-min(e.recorded_at)),0),0)
                FROM stockrescue.transfer_outbox_delivery d JOIN stockrescue.transfer_outbox_event e ON e.id=d.event_id
                WHERE d.published_at IS NULL AND NOT EXISTS (SELECT 1 FROM stockrescue.transfer_outbox_redrive_attempt a
                    WHERE a.event_id=d.event_id AND a.published_at IS NOT NULL)
                """).query(Double.class).single();
    }
    public long parkedCount() {
        return jdbc.sql("""
                SELECT count(*) FROM stockrescue.transfer_outbox_delivery d WHERE d.parked_at IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM stockrescue.transfer_outbox_redrive_attempt a
                        WHERE a.event_id=d.event_id AND a.published_at IS NOT NULL)
                """)
                .query(Long.class).single();
    }
    public long totalAttempts() {
        return jdbc.sql("SELECT coalesce(sum(attempt_count),0) FROM stockrescue.transfer_outbox_delivery")
                .query(Long.class).single();
    }

    private OutboxEvent event(ResultSet row,int index) throws SQLException {
        var until=row.getTimestamp("lease_until");
        return new OutboxEvent(row.getObject("id",UUID.class),row.getObject("org_id",UUID.class),
                row.getObject("transfer_id",UUID.class),row.getLong("aggregate_version"),row.getString("event_type"),
                row.getInt("schema_version"),row.getString("payload"),row.getString("correlation_id"),
                row.getTimestamp("recorded_at").toInstant(),row.getObject("lease_owner",UUID.class),
                row.getLong("claim_version"),row.getInt("attempt_count"),until==null?null:until.toInstant());
    }
}
