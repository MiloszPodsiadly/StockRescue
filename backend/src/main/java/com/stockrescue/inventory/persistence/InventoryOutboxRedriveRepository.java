package com.stockrescue.inventory.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.stockrescue.inventory.persistence.InventoryOutboxRepository.OutboxEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InventoryOutboxRedriveRepository {
    public record SourceEvidence(long deltaOnHand,long deltaReserved,long deltaQuarantined,long deltaOtherBlocked,
            String initializationAfter,String trustAfter,long sourceVersionCount,BigDecimal onHand,BigDecimal reserved,
            BigDecimal quarantined,BigDecimal otherBlocked,String fingerprint) { }

    public record Attempt(UUID id,UUID organizationId,UUID storeId,UUID eventId,long generation,UUID requestId,
            UUID actorUserId,UUID membershipId,byte[] requestFingerprint,byte[] sourceFingerprint,
            long sourceClaimVersion,int sourceAttemptCount,String reason,String evidenceReference,String acceptedState,
            Instant acceptedNextAttemptAt,Instant acceptedBlockedAt,String acceptedErrorCode,Instant recordedAt,
            int attemptCount,long claimVersion,Instant nextAttemptAt,UUID leaseOwner,Instant leaseUntil,Instant publishedAt,
            Instant blockedAt,String lastErrorCode,Instant lastErrorAt) {
        public Attempt { requestFingerprint=requestFingerprint.clone();sourceFingerprint=sourceFingerprint.clone(); }
        @Override public byte[] requestFingerprint() { return requestFingerprint.clone(); }
        @Override public byte[] sourceFingerprint() { return sourceFingerprint.clone(); }
        public String sourceFingerprintHex() { return HexFormat.of().formatHex(sourceFingerprint); }
    }

    public record ParkedEvent(OutboxEvent event,SourceEvidence source,Instant parkedAt,long claimVersion,
            int originalAttemptCount,long latestGeneration,String state,int redriveAttemptCount,
            Instant latestAttemptAt,String lastErrorCode) { }

    public record RedriveLease(Attempt attempt,OutboxEvent event,SourceEvidence source) { }

    private final JdbcClient jdbc;
    private final InventoryOutboxRepository outbox;

    public InventoryOutboxRedriveRepository(JdbcClient jdbc,InventoryOutboxRepository outbox) {
        this.jdbc=jdbc;this.outbox=outbox;
    }

    public boolean storeExists(UUID organizationId,UUID storeId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM stockrescue.store WHERE org_id=:org AND id=:store)")
                .param("org",organizationId).param("store",storeId).query(Boolean.class).single();
    }

    public List<ParkedEvent> parked(UUID organizationId,UUID storeId,int offset,int limit) {
        var rows=jdbc.sql("""
                SELECT e.id,d.parked_at,d.claim_version,d.attempt_count,
                    coalesce(a.generation,0) AS generation,
                    CASE WHEN a.published_at IS NOT NULL THEN 'PUBLISHED'
                         WHEN a.blocked_at IS NOT NULL THEN 'BLOCKED'
                         WHEN a.lease_owner IS NOT NULL AND a.lease_until>clock_timestamp() THEN 'REDRIVING'
                         WHEN a.id IS NOT NULL AND a.attempt_count>0 THEN 'RETRYING'
                         WHEN a.id IS NOT NULL THEN 'REDRIVE_QUEUED'
                         ELSE 'PARKED' END AS state,
                    coalesce(a.attempt_count,0) AS redrive_attempt_count,
                    coalesce(a.recorded_at,d.parked_at) AS latest_attempt_at,a.last_error_code
                FROM stockrescue.inventory_outbox_delivery d
                JOIN stockrescue.inventory_outbox_event e ON e.id=d.event_id
                LEFT JOIN LATERAL (SELECT latest.* FROM stockrescue.inventory_outbox_redrive_attempt latest
                    WHERE latest.event_id=e.id ORDER BY latest.generation DESC LIMIT 1) a ON true
                WHERE d.org_id=:org AND d.store_id=:store AND d.parked_at IS NOT NULL
                ORDER BY d.parked_at,e.id OFFSET :offset LIMIT :limit
                """).param("org",organizationId).param("store",storeId).param("offset",offset)
                .param("limit",limit).query((row,index)->new ParkedRow(row.getObject("id",UUID.class),
                        row.getTimestamp("parked_at").toInstant(),row.getLong("claim_version"),row.getInt("attempt_count"),
                        row.getLong("generation"),row.getString("state"),row.getInt("redrive_attempt_count"),
                        row.getTimestamp("latest_attempt_at").toInstant(),row.getString("last_error_code"))).list();
        return rows.stream().map(this::parkedEvent).toList();
    }

    @Transactional
    public ParkedEvent lockParked(UUID organizationId,UUID storeId,UUID eventId) {
        var row=jdbc.sql("""
                SELECT d.parked_at,d.claim_version,d.attempt_count
                FROM stockrescue.inventory_outbox_delivery d
                WHERE d.org_id=:org AND d.store_id=:store AND d.event_id=:event
                    AND d.parked_at IS NOT NULL AND d.published_at IS NULL
                FOR UPDATE
                """).param("org",organizationId).param("store",storeId).param("event",eventId)
                .query((result,index)->new ParkedLock(result.getTimestamp("parked_at").toInstant(),
                        result.getLong("claim_version"),result.getInt("attempt_count"))).optional().orElse(null);
        if(row==null) return null;
        return parkedEvent(new ParkedRow(eventId,row.parkedAt(),row.claimVersion(),row.attemptCount(),0,"PARKED",0,row.parkedAt(),null));
    }

    public Attempt findRequest(UUID organizationId,UUID actorUserId,UUID requestId) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.inventory_outbox_redrive_attempt
                WHERE org_id=:org AND actor_user_id=:actor AND request_id=:request
                """).param("org",organizationId).param("actor",actorUserId).param("request",requestId)
                .query(this::attempt).optional().orElse(null);
    }

    @Transactional
    public void lockRequestScope(UUID organizationId,UUID actorUserId,UUID requestId) {
        String key=organizationId+":"+actorUserId+":"+requestId;
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,72051))")
                .param("key",key).query((row,index)->Boolean.TRUE).single();
    }

    public boolean hasAcknowledgedOrActiveAttempt(UUID eventId) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM stockrescue.inventory_outbox_redrive_attempt
                    WHERE event_id=:event AND (published_at IS NOT NULL OR (published_at IS NULL AND blocked_at IS NULL)))
                """).param("event",eventId).query(Boolean.class).single();
    }

    public long nextGeneration(UUID eventId) {
        return jdbc.sql("SELECT coalesce(max(generation),0)+1 FROM stockrescue.inventory_outbox_redrive_attempt WHERE event_id=:event")
                .param("event",eventId).query(Long.class).single();
    }

    @Transactional
    public Attempt insert(UUID id,ParkedEvent parked,UUID requestId,UUID actorUserId,UUID membershipId,
            byte[] requestFingerprint,String reason,String evidenceReference,String blockedCode) {
        long generation=nextGeneration(parked.event().id());
        jdbc.sql("""
                INSERT INTO stockrescue.inventory_outbox_redrive_attempt(id,org_id,store_id,event_id,generation,
                    request_id,actor_user_id,membership_id,request_fingerprint,source_fingerprint,
                    source_claim_version,source_attempt_count,reason,evidence_reference,accepted_state,accepted_next_attempt_at,
                    accepted_blocked_at,accepted_error_code,next_attempt_at,blocked_at,last_error_code,last_error_at)
                VALUES (:id,:org,:store,:event,:generation,:request,:actor,:membership,:requestFingerprint,:sourceFingerprint,
                    :claimVersion,:attemptCount,:reason,:evidence,:acceptedState,statement_timestamp(),
                    CASE WHEN :blockedCode IS NULL THEN NULL ELSE statement_timestamp() END,
                    :blockedCode,statement_timestamp(),CASE WHEN :blockedCode IS NULL THEN NULL ELSE statement_timestamp() END,
                    :blockedCode,CASE WHEN :blockedCode IS NULL THEN NULL ELSE statement_timestamp() END)
                """).param("id",id).param("org",parked.event().organizationId()).param("store",parked.event().storeId())
                .param("event",parked.event().id()).param("generation",generation).param("request",requestId)
                .param("actor",actorUserId).param("membership",membershipId).param("requestFingerprint",requestFingerprint)
                .param("sourceFingerprint",HexFormat.of().parseHex(parked.source().fingerprint())).param("claimVersion",parked.claimVersion())
                .param("attemptCount",parked.originalAttemptCount()).param("reason",reason).param("evidence",evidenceReference)
                .param("acceptedState",blockedCode==null?"REDRIVE_QUEUED":"BLOCKED")
                .param("blockedCode",blockedCode,java.sql.Types.VARCHAR).update();
        return findRequest(parked.event().organizationId(),actorUserId,requestId);
    }

    @Transactional
    public List<RedriveLease> claimDue(UUID owner,int limit,Duration lease) {
        if(limit<1 || lease.isNegative() || lease.isZero() || lease.compareTo(Duration.ofMinutes(5))>0)
            throw new IllegalArgumentException("A positive bounded redrive claim is required");
        var ids=jdbc.sql("""
                WITH due AS (
                    SELECT a.id
                    FROM stockrescue.inventory_outbox_redrive_attempt a
                    JOIN stockrescue.inventory_outbox_event e ON e.id=a.event_id
                    JOIN stockrescue.inventory_outbox_delivery d ON d.event_id=e.id
                    JOIN stockrescue.stock_movement m ON m.org_id=e.org_id AND m.store_id=e.store_id AND m.id=e.movement_id
                    WHERE d.parked_at IS NOT NULL AND d.published_at IS NULL
                        AND a.published_at IS NULL AND a.blocked_at IS NULL
                        AND a.next_attempt_at<=clock_timestamp()
                        AND (a.lease_until IS NULL OR a.lease_until<=clock_timestamp())
                        AND NOT EXISTS (
                            SELECT 1 FROM stockrescue.inventory_outbox_event prior
                            JOIN stockrescue.inventory_outbox_delivery prior_delivery ON prior_delivery.event_id=prior.id
                            WHERE prior.org_id=e.org_id AND prior.store_id=e.store_id AND prior.position_id=e.position_id
                                AND prior.aggregate_version<e.aggregate_version
                                AND prior_delivery.published_at IS NULL
                                AND NOT EXISTS (SELECT 1 FROM stockrescue.inventory_outbox_redrive_attempt ack
                                    WHERE ack.event_id=prior.id AND ack.published_at IS NOT NULL)
                        )
                    ORDER BY m.recorded_at,e.id,a.generation
                    LIMIT :limit FOR UPDATE OF a SKIP LOCKED
                ), claimed AS (
                    UPDATE stockrescue.inventory_outbox_redrive_attempt a
                    SET lease_owner=:owner,lease_until=clock_timestamp()+(:leaseMillis * interval '1 millisecond'),
                        claim_version=a.claim_version+1,attempt_count=a.attempt_count+1
                    FROM due WHERE a.id=due.id
                    RETURNING a.id
                ) SELECT id FROM claimed
                """).param("owner",owner).param("limit",limit).param("leaseMillis",lease.toMillis())
                .query(UUID.class).list();
        var result=new ArrayList<RedriveLease>(ids.size());
        for(var id:ids) {
            var attempt=findAttempt(id);
            var event=outbox.find(attempt.eventId());
            if(event!=null) result.add(new RedriveLease(attempt,event,sourceEvidence(event)));
        }
        return List.copyOf(result);
    }

    @Transactional
    public boolean markPublished(RedriveLease claim) {
        return jdbc.sql("""
                UPDATE stockrescue.inventory_outbox_redrive_attempt
                SET published_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,last_error_code=NULL
                WHERE id=:id AND lease_owner=:owner AND claim_version=:version AND lease_until>clock_timestamp()
                    AND published_at IS NULL AND blocked_at IS NULL
                """).param("id",claim.attempt().id()).param("owner",claim.attempt().leaseOwner())
                .param("version",claim.attempt().claimVersion()).update()==1;
    }

    @Transactional
    public boolean scheduleRetry(RedriveLease claim,String code,Duration delay) {
        if(delay.isNegative() || delay.isZero() || delay.compareTo(Duration.ofMinutes(5))>0)
            throw new IllegalArgumentException("Redrive retry delay is outside its bound");
        return jdbc.sql("""
                UPDATE stockrescue.inventory_outbox_redrive_attempt
                SET next_attempt_at=clock_timestamp()+(:delayMillis * interval '1 millisecond'),
                    lease_owner=NULL,lease_until=NULL,last_error_code=:code,last_error_at=clock_timestamp()
                WHERE id=:id AND lease_owner=:owner AND claim_version=:version AND lease_until>clock_timestamp()
                    AND published_at IS NULL AND blocked_at IS NULL
                """).param("delayMillis",delay.toMillis()).param("code",code).param("id",claim.attempt().id())
                .param("owner",claim.attempt().leaseOwner()).param("version",claim.attempt().claimVersion()).update()==1;
    }

    @Transactional
    public boolean block(RedriveLease claim,String code) {
        return jdbc.sql("""
                UPDATE stockrescue.inventory_outbox_redrive_attempt
                SET blocked_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,
                    last_error_code=:code,last_error_at=clock_timestamp()
                WHERE id=:id AND lease_owner=:owner AND claim_version=:version AND lease_until>clock_timestamp()
                    AND published_at IS NULL AND blocked_at IS NULL
                """).param("code",code).param("id",claim.attempt().id()).param("owner",claim.attempt().leaseOwner())
                .param("version",claim.attempt().claimVersion()).update()==1;
    }

    public SourceEvidence sourceEvidence(OutboxEvent event) {
        var raw=jdbc.sql("""
                SELECT l.delta_on_hand,l.delta_reserved,l.delta_quarantined,l.delta_other_blocked,
                    l.initialization_after,l.trust_after,
                    (SELECT count(*) FROM stockrescue.stock_movement_line h WHERE h.org_id=l.org_id AND h.store_id=l.store_id
                        AND h.position_id=l.position_id AND h.position_version<=:version) AS source_version_count,
                    coalesce((SELECT sum(h.delta_on_hand) FROM stockrescue.stock_movement_line h WHERE h.org_id=l.org_id AND h.store_id=l.store_id
                        AND h.position_id=l.position_id AND h.position_version<=:version),0) AS on_hand,
                    coalesce((SELECT sum(h.delta_reserved) FROM stockrescue.stock_movement_line h WHERE h.org_id=l.org_id AND h.store_id=l.store_id
                        AND h.position_id=l.position_id AND h.position_version<=:version),0) AS reserved,
                    coalesce((SELECT sum(h.delta_quarantined) FROM stockrescue.stock_movement_line h WHERE h.org_id=l.org_id AND h.store_id=l.store_id
                        AND h.position_id=l.position_id AND h.position_version<=:version),0) AS quarantined,
                    coalesce((SELECT sum(h.delta_other_blocked) FROM stockrescue.stock_movement_line h WHERE h.org_id=l.org_id AND h.store_id=l.store_id
                        AND h.position_id=l.position_id AND h.position_version<=:version),0) AS other_blocked
                FROM stockrescue.stock_movement_line l
                WHERE l.org_id=:org AND l.store_id=:store AND l.movement_id=:movement AND l.position_id=:position
                    AND l.position_version=:version
                """).param("org",event.organizationId()).param("store",event.storeId()).param("movement",event.movementId())
                .param("position",event.positionId()).param("version",event.aggregateVersion())
                .query((row,index)->new RawEvidence(row.getLong("delta_on_hand"),row.getLong("delta_reserved"),
                        row.getLong("delta_quarantined"),row.getLong("delta_other_blocked"),row.getString("initialization_after"),
                        row.getString("trust_after"),row.getLong("source_version_count"),row.getBigDecimal("on_hand"),
                        row.getBigDecimal("reserved"),row.getBigDecimal("quarantined"),row.getBigDecimal("other_blocked")))
                .optional().orElse(new RawEvidence(0,0,0,0,"MISSING","MISSING",0,BigDecimal.ZERO,
                        BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));
        String fingerprint=InventoryOutboxFingerprint.hex("source",event.id(),event.organizationId(),event.storeId(),event.movementId(),
                event.positionId(),event.aggregateVersion(),event.eventType(),event.schemaVersion(),event.payload(),event.correlationId(),
                event.occurredAt(),raw.deltaOnHand(),raw.deltaReserved(),raw.deltaQuarantined(),raw.deltaOtherBlocked(),
                raw.initializationAfter(),raw.trustAfter(),raw.sourceVersionCount(),raw.onHand(),raw.reserved(),raw.quarantined(),raw.otherBlocked());
        return new SourceEvidence(raw.deltaOnHand(),raw.deltaReserved(),raw.deltaQuarantined(),raw.deltaOtherBlocked(),
                raw.initializationAfter(),raw.trustAfter(),raw.sourceVersionCount(),raw.onHand(),raw.reserved(),
                raw.quarantined(),raw.otherBlocked(),fingerprint);
    }

    public long unresolvedParkedCount() {
        return jdbc.sql("""
                SELECT count(*) FROM stockrescue.inventory_outbox_delivery d
                WHERE d.parked_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stockrescue.inventory_outbox_redrive_attempt a
                    WHERE a.event_id=d.event_id AND a.published_at IS NOT NULL)
                """).query(Long.class).single();
    }

    public double oldestUnresolvedParkedSeconds() {
        return jdbc.sql("""
                SELECT coalesce(greatest(extract(epoch FROM clock_timestamp()-min(d.parked_at)),0),0)
                FROM stockrescue.inventory_outbox_delivery d
                WHERE d.parked_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM stockrescue.inventory_outbox_redrive_attempt a
                    WHERE a.event_id=d.event_id AND a.published_at IS NOT NULL)
                """).query(Double.class).single();
    }

    public long totalRedriveAttempts() {
        return jdbc.sql("SELECT coalesce(sum(attempt_count),0) FROM stockrescue.inventory_outbox_redrive_attempt")
                .query(Long.class).single();
    }

    private ParkedEvent parkedEvent(ParkedRow row) {
        var event=outbox.find(row.eventId());
        if(event==null) throw new IllegalStateException("Parked outbox event disappeared from immutable history");
        return new ParkedEvent(event,sourceEvidence(event),row.parkedAt(),row.claimVersion(),row.attemptCount(),
                row.generation(),row.state(),row.redriveAttemptCount(),row.latestAttemptAt(),row.lastErrorCode());
    }

    private Attempt findAttempt(UUID id) {
        return jdbc.sql("SELECT * FROM stockrescue.inventory_outbox_redrive_attempt WHERE id=:id")
                .param("id",id).query(this::attempt).single();
    }

    private Attempt attempt(ResultSet row,int index) throws SQLException {
        return new Attempt(row.getObject("id",UUID.class),row.getObject("org_id",UUID.class),row.getObject("store_id",UUID.class),
                row.getObject("event_id",UUID.class),row.getLong("generation"),row.getObject("request_id",UUID.class),
                row.getObject("actor_user_id",UUID.class),row.getObject("membership_id",UUID.class),row.getBytes("request_fingerprint"),
                row.getBytes("source_fingerprint"),row.getLong("source_claim_version"),row.getInt("source_attempt_count"),
                row.getString("reason"),row.getString("evidence_reference"),row.getString("accepted_state"),
                instant(row,"accepted_next_attempt_at"),instant(row,"accepted_blocked_at"),row.getString("accepted_error_code"),
                instant(row,"recorded_at"),row.getInt("attempt_count"),
                row.getLong("claim_version"),instant(row,"next_attempt_at"),row.getObject("lease_owner",UUID.class),instant(row,"lease_until"),
                instant(row,"published_at"),instant(row,"blocked_at"),row.getString("last_error_code"),instant(row,"last_error_at"));
    }

    private static Instant instant(ResultSet row,String column) throws SQLException {
        var value=row.getTimestamp(column);return value==null?null:value.toInstant();
    }
    private record RawEvidence(long deltaOnHand,long deltaReserved,long deltaQuarantined,long deltaOtherBlocked,
            String initializationAfter,String trustAfter,long sourceVersionCount,BigDecimal onHand,BigDecimal reserved,
            BigDecimal quarantined,BigDecimal otherBlocked) { }
    private record ParkedRow(UUID eventId,Instant parkedAt,long claimVersion,int attemptCount,long generation,
            String state,int redriveAttemptCount,Instant latestAttemptAt,String lastErrorCode) { }
    private record ParkedLock(Instant parkedAt,long claimVersion,int attemptCount) { }
}
