package com.stockrescue.transfers.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.stockrescue.transfers.persistence.TransferOutboxRepository.OutboxEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TransferOutboxRedriveRepository {
    public record Attempt(UUID id,UUID organizationId,UUID transferId,UUID eventId,long generation,UUID requestId,
            UUID actorId,UUID membershipId,byte[] requestFingerprint,byte[] sourceFingerprint,
            long sourceClaimVersion,int sourceAttemptCount,String reason,String evidenceReference,String acceptedState,
            Instant acceptedAt,Instant acceptedBlockedAt,String acceptedErrorCode,Instant recordedAt,
            int attemptCount,long claimVersion,Instant nextAttemptAt,UUID leaseOwner,Instant leaseUntil,
            Instant publishedAt,Instant blockedAt,String lastErrorCode,Instant lastErrorAt) {
        public Attempt { requestFingerprint=requestFingerprint.clone();sourceFingerprint=sourceFingerprint.clone(); }
        @Override public byte[] requestFingerprint() { return requestFingerprint.clone(); }
        @Override public byte[] sourceFingerprint() { return sourceFingerprint.clone(); }
        public String sourceFingerprintHex() { return HexFormat.of().formatHex(sourceFingerprint); }
    }
    public record ParkedEvent(OutboxEvent event,Instant parkedAt,long claimVersion,int originalAttemptCount,
            long latestGeneration,String state,int redriveAttemptCount,Instant latestAttemptAt,
            String lastErrorCode,String sourceFingerprint) { }
    public record RedriveLease(Attempt attempt,OutboxEvent event) { }

    private final JdbcClient jdbc;
    private final TransferOutboxRepository outbox;
    public TransferOutboxRedriveRepository(JdbcClient jdbc,TransferOutboxRepository outbox) {
        this.jdbc=jdbc;this.outbox=outbox;
    }

    public List<ParkedEvent> parked(UUID organizationId,int offset,int limit) {
        return jdbc.sql("""
                SELECT e.id,e.org_id,e.transfer_id,e.aggregate_version,e.event_type,e.schema_version,e.payload::text payload,
                    e.correlation_id,e.recorded_at,d.lease_owner,d.claim_version,d.attempt_count,d.lease_until,d.parked_at,
                    coalesce(a.generation,0) generation,
                    CASE WHEN a.published_at IS NOT NULL THEN 'PUBLISHED'
                         WHEN a.blocked_at IS NOT NULL THEN 'BLOCKED'
                         WHEN a.lease_owner IS NOT NULL AND a.lease_until>clock_timestamp() THEN 'REDRIVING'
                         WHEN a.id IS NOT NULL AND a.attempt_count>0 THEN 'RETRYING'
                         WHEN a.id IS NOT NULL THEN 'REDRIVE_QUEUED' ELSE 'PARKED' END state,
                    coalesce(a.attempt_count,0) redrive_attempt_count,coalesce(a.recorded_at,d.parked_at) latest_attempt_at,
                    a.last_error_code
                FROM stockrescue.transfer_outbox_delivery d
                JOIN stockrescue.transfer_outbox_event e ON e.org_id=d.org_id AND e.transfer_id=d.transfer_id AND e.id=d.event_id
                LEFT JOIN LATERAL (SELECT latest.* FROM stockrescue.transfer_outbox_redrive_attempt latest
                    WHERE latest.event_id=e.id ORDER BY latest.generation DESC LIMIT 1) a ON true
                WHERE d.org_id=:org AND d.parked_at IS NOT NULL
                ORDER BY d.parked_at,e.id OFFSET :offset LIMIT :limit
                """).param("org",organizationId).param("offset",offset).param("limit",limit)
                .query((row,index)->parkedEvent(row)).list();
    }

    @Transactional
    public ParkedEvent lockParked(UUID organizationId,UUID eventId) {
        var row=jdbc.sql("""
                SELECT d.parked_at,d.claim_version,d.attempt_count
                FROM stockrescue.transfer_outbox_delivery d
                WHERE d.org_id=:org AND d.event_id=:event AND d.parked_at IS NOT NULL AND d.published_at IS NULL
                FOR UPDATE
                """).param("org",organizationId).param("event",eventId)
                .query((result,index)->new ParkedLock(result.getTimestamp("parked_at").toInstant(),
                        result.getLong("claim_version"),result.getInt("attempt_count"))).optional().orElse(null);
        if(row==null) return null;
        var event=outbox.find(eventId);
        if(event==null) throw new IllegalStateException("Parked transfer event disappeared from immutable history");
        return new ParkedEvent(event,row.parkedAt(),row.claimVersion(),row.attemptCount(),0,"PARKED",0,
                row.parkedAt(),null,sourceFingerprint(event));
    }

    public Attempt findRequest(UUID organizationId,UUID actorId,UUID requestId) {
        return jdbc.sql("SELECT * FROM stockrescue.transfer_outbox_redrive_attempt WHERE org_id=:org AND actor_user_id=:actor AND request_id=:request")
                .param("org",organizationId).param("actor",actorId).param("request",requestId)
                .query(this::attempt).optional().orElse(null);
    }
    @Transactional
    public void lockRequestScope(UUID organizationId,UUID actorId,UUID requestId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,73119))")
                .param("key",organizationId+":"+actorId+":"+requestId).query((row,index)->Boolean.TRUE).single();
    }
    public boolean hasAcknowledgedOrActiveAttempt(UUID eventId) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM stockrescue.transfer_outbox_redrive_attempt
                WHERE event_id=:event AND (published_at IS NOT NULL OR (published_at IS NULL AND blocked_at IS NULL)))
                """).param("event",eventId).query(Boolean.class).single();
    }
    public long nextGeneration(UUID eventId) {
        return jdbc.sql("SELECT coalesce(max(generation),0)+1 FROM stockrescue.transfer_outbox_redrive_attempt WHERE event_id=:event")
                .param("event",eventId).query(Long.class).single();
    }

    @Transactional
    public Attempt insert(UUID id,ParkedEvent parked,UUID requestId,UUID actor,UUID membership,
            byte[] requestFingerprint,String reason,String evidence,String blockedCode) {
        long generation=nextGeneration(parked.event().id());
        jdbc.sql("""
                INSERT INTO stockrescue.transfer_outbox_redrive_attempt(id,org_id,transfer_id,event_id,generation,
                    request_id,actor_user_id,membership_id,request_fingerprint,source_fingerprint,source_claim_version,
                    source_attempt_count,reason,evidence_reference,accepted_state,accepted_next_attempt_at,accepted_blocked_at,
                    accepted_error_code,next_attempt_at,blocked_at,last_error_code,last_error_at)
                VALUES (:id,:org,:transfer,:event,:generation,:request,:actor,:membership,:requestFingerprint,:sourceFingerprint,
                    :claimVersion,:attemptCount,:reason,:evidence,:state,statement_timestamp(),
                    CASE WHEN :blocked IS NULL THEN NULL ELSE statement_timestamp() END,:blocked,statement_timestamp(),
                    CASE WHEN :blocked IS NULL THEN NULL ELSE statement_timestamp() END,:blocked,
                    CASE WHEN :blocked IS NULL THEN NULL ELSE statement_timestamp() END)
                """).param("id",id).param("org",parked.event().organizationId()).param("transfer",parked.event().transferId())
                .param("event",parked.event().id()).param("generation",generation).param("request",requestId)
                .param("actor",actor).param("membership",membership).param("requestFingerprint",requestFingerprint)
                .param("sourceFingerprint",HexFormat.of().parseHex(parked.sourceFingerprint()))
                .param("claimVersion",parked.claimVersion()).param("attemptCount",parked.originalAttemptCount())
                .param("reason",reason).param("evidence",evidence).param("state",blockedCode==null?"REDRIVE_QUEUED":"BLOCKED")
                .param("blocked",blockedCode,java.sql.Types.VARCHAR).update();
        return findRequest(parked.event().organizationId(),actor,requestId);
    }

    @Transactional
    public List<RedriveLease> claimDue(UUID owner,int limit,Duration lease) {
        if(limit<1||lease.isNegative()||lease.isZero()||lease.compareTo(Duration.ofMinutes(5))>0)
            throw new IllegalArgumentException("A bounded transfer redrive claim is required");
        var ids=jdbc.sql("""
                WITH due AS (
                    SELECT a.id FROM stockrescue.transfer_outbox_redrive_attempt a
                    JOIN stockrescue.transfer_outbox_delivery d ON d.org_id=a.org_id AND d.transfer_id=a.transfer_id AND d.event_id=a.event_id
                    JOIN stockrescue.transfer_outbox_event e ON e.org_id=a.org_id AND e.transfer_id=a.transfer_id AND e.id=a.event_id
                    WHERE d.parked_at IS NOT NULL AND d.published_at IS NULL AND a.published_at IS NULL AND a.blocked_at IS NULL
                        AND a.next_attempt_at<=clock_timestamp() AND (a.lease_until IS NULL OR a.lease_until<=clock_timestamp())
                        AND NOT EXISTS (
                            SELECT 1 FROM stockrescue.transfer_outbox_event prior
                            JOIN stockrescue.transfer_outbox_delivery pd ON pd.org_id=prior.org_id AND pd.transfer_id=prior.transfer_id AND pd.event_id=prior.id
                            WHERE prior.org_id=e.org_id AND prior.transfer_id=e.transfer_id AND prior.aggregate_version<e.aggregate_version
                                AND pd.published_at IS NULL AND NOT EXISTS (
                                    SELECT 1 FROM stockrescue.transfer_outbox_redrive_attempt ack
                                    WHERE ack.event_id=prior.id AND ack.published_at IS NOT NULL))
                    ORDER BY e.recorded_at,e.id,a.generation LIMIT :limit FOR UPDATE OF a SKIP LOCKED
                ), claimed AS (
                    UPDATE stockrescue.transfer_outbox_redrive_attempt a
                    SET lease_owner=:owner,lease_until=clock_timestamp()+(:leaseMillis * interval '1 millisecond'),
                        claim_version=a.claim_version+1,attempt_count=a.attempt_count+1
                    FROM due WHERE a.id=due.id RETURNING a.id
                ) SELECT id FROM claimed
                """).param("owner",owner).param("limit",limit).param("leaseMillis",lease.toMillis())
                .query(UUID.class).list();
        var result=new ArrayList<RedriveLease>(ids.size());
        for(UUID id:ids) {
            var attempt=findAttempt(id);
            var event=outbox.find(attempt.eventId());
            if(event!=null) result.add(new RedriveLease(attempt,event));
        }
        return List.copyOf(result);
    }

    @Transactional
    public boolean markPublished(RedriveLease claim) {
        return jdbc.sql("""
                UPDATE stockrescue.transfer_outbox_redrive_attempt
                SET published_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,last_error_code=NULL,last_error_at=NULL
                WHERE id=:id AND lease_owner=:owner AND claim_version=:version AND lease_until>clock_timestamp()
                    AND published_at IS NULL AND blocked_at IS NULL
                """).param("id",claim.attempt().id()).param("owner",claim.attempt().leaseOwner())
                .param("version",claim.attempt().claimVersion()).update()==1;
    }
    @Transactional
    public boolean scheduleRetry(RedriveLease claim,String code,Duration delay) {
        return jdbc.sql("""
                UPDATE stockrescue.transfer_outbox_redrive_attempt
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
                UPDATE stockrescue.transfer_outbox_redrive_attempt
                SET blocked_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,last_error_code=:code,last_error_at=clock_timestamp()
                WHERE id=:id AND lease_owner=:owner AND claim_version=:version AND lease_until>clock_timestamp()
                    AND published_at IS NULL AND blocked_at IS NULL
                """).param("code",code).param("id",claim.attempt().id()).param("owner",claim.attempt().leaseOwner())
                .param("version",claim.attempt().claimVersion()).update()==1;
    }

    public long unresolvedParkedCount() {
        return jdbc.sql("""
                SELECT count(*) FROM stockrescue.transfer_outbox_delivery d WHERE d.parked_at IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM stockrescue.transfer_outbox_redrive_attempt a
                        WHERE a.event_id=d.event_id AND a.published_at IS NOT NULL)
                """).query(Long.class).single();
    }
    public double oldestUnresolvedParkedSeconds() {
        return jdbc.sql("""
                SELECT coalesce(greatest(extract(epoch FROM clock_timestamp()-min(d.parked_at)),0),0)
                FROM stockrescue.transfer_outbox_delivery d WHERE d.parked_at IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM stockrescue.transfer_outbox_redrive_attempt a
                        WHERE a.event_id=d.event_id AND a.published_at IS NOT NULL)
                """).query(Double.class).single();
    }
    public long totalRedriveAttempts() {
        return jdbc.sql("SELECT coalesce(sum(attempt_count),0) FROM stockrescue.transfer_outbox_redrive_attempt")
                .query(Long.class).single();
    }

    public String sourceFingerprint(OutboxEvent event) {
        return sourceFingerprint(event.id(),event.organizationId(),event.transferId(),event.aggregateVersion(),
                event.eventType(),event.schemaVersion(),event.payload(),event.correlationId(),event.occurredAt());
    }
    private static String sourceFingerprint(Object... values) {
        var all=new Object[values.length+1];all[0]="source";System.arraycopy(values,0,all,1,values.length);
        return TransferOutboxFingerprint.hex(all);
    }
    private Attempt findAttempt(UUID id) {
        return jdbc.sql("SELECT * FROM stockrescue.transfer_outbox_redrive_attempt WHERE id=:id")
                .param("id",id).query(this::attempt).single();
    }
    private Attempt attempt(ResultSet row,int index) throws SQLException {
        return new Attempt(row.getObject("id",UUID.class),row.getObject("org_id",UUID.class),row.getObject("transfer_id",UUID.class),
                row.getObject("event_id",UUID.class),row.getLong("generation"),row.getObject("request_id",UUID.class),
                row.getObject("actor_user_id",UUID.class),row.getObject("membership_id",UUID.class),row.getBytes("request_fingerprint"),
                row.getBytes("source_fingerprint"),row.getLong("source_claim_version"),row.getInt("source_attempt_count"),
                row.getString("reason"),row.getString("evidence_reference"),row.getString("accepted_state"),
                instant(row,"accepted_next_attempt_at"),instant(row,"accepted_blocked_at"),row.getString("accepted_error_code"),
                instant(row,"recorded_at"),row.getInt("attempt_count"),row.getLong("claim_version"),instant(row,"next_attempt_at"),
                row.getObject("lease_owner",UUID.class),instant(row,"lease_until"),instant(row,"published_at"),instant(row,"blocked_at"),
                row.getString("last_error_code"),instant(row,"last_error_at"));
    }
    private ParkedEvent parkedEvent(ResultSet row) throws SQLException {
        Instant leaseUntil=instant(row,"lease_until");
        var event=new OutboxEvent(row.getObject("id",UUID.class),row.getObject("org_id",UUID.class),
                row.getObject("transfer_id",UUID.class),row.getLong("aggregate_version"),row.getString("event_type"),
                row.getInt("schema_version"),row.getString("payload"),row.getString("correlation_id"),instant(row,"recorded_at"),
                row.getObject("lease_owner",UUID.class),row.getLong("claim_version"),row.getInt("attempt_count"),leaseUntil);
        return new ParkedEvent(event,instant(row,"parked_at"),row.getLong("claim_version"),row.getInt("attempt_count"),
                row.getLong("generation"),row.getString("state"),row.getInt("redrive_attempt_count"),
                instant(row,"latest_attempt_at"),row.getString("last_error_code"),sourceFingerprint(event));
    }
    private static Instant instant(ResultSet row,String column) throws SQLException {
        var value=row.getTimestamp(column);return value==null?null:value.toInstant();
    }
    private record ParkedLock(Instant parkedAt,long claimVersion,int attemptCount) { }
}
