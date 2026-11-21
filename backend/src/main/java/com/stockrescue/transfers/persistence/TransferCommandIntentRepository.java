package com.stockrescue.transfers.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferCommandIntentRepository {
    public record Intent(UUID id, UUID organizationId, UUID transferId, UUID actorId, UUID membershipId,
            String operation, UUID requestId, byte[] fingerprint, String payload, String state,
            Instant preparedAt, Instant executedAt, Instant resolvedAt) { }

    public record Pending(UUID id, String operation, UUID transferId, UUID requestId, String state, Instant preparedAt) { }

    private final JdbcClient jdbc;

    public TransferCommandIntentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean insert(Intent intent) {
        return jdbc.sql("""
                INSERT INTO stockrescue.transfer_command_intent
                    (id,org_id,transfer_id,actor_user_id,membership_id,operation,request_id,payload_fingerprint,payload)
                VALUES (:id,:org,:transfer,:actor,:membership,:operation,:request,:fingerprint,CAST(:payload AS jsonb))
                ON CONFLICT DO NOTHING
                """)
                .param("id", intent.id()).param("org", intent.organizationId()).param("transfer", intent.transferId())
                .param("actor", intent.actorId()).param("membership", intent.membershipId()).param("operation", intent.operation())
                .param("request", intent.requestId()).param("fingerprint", intent.fingerprint()).param("payload", intent.payload())
                .update() == 1;
    }

    public Optional<Intent> byId(UUID organizationId, UUID actorId, UUID id, boolean lock) {
        return jdbc.sql("SELECT * FROM stockrescue.transfer_command_intent WHERE org_id=:org AND actor_user_id=:actor AND id=:id"
                        + (lock ? " FOR UPDATE" : ""))
                .param("org", organizationId).param("actor", actorId).param("id", id)
                .query((row, index) -> map(row)).optional();
    }

    public Optional<Intent> byRequest(UUID organizationId, UUID actorId, String operation, UUID requestId) {
        return jdbc.sql("SELECT * FROM stockrescue.transfer_command_intent WHERE org_id=:org AND actor_user_id=:actor AND operation=:operation AND request_id=:request")
                .param("org", organizationId).param("actor", actorId).param("operation", operation).param("request", requestId)
                .query((row, index) -> map(row)).optional();
    }

    public Optional<Intent> activeByActor(UUID organizationId, UUID actorId) {
        return jdbc.sql("SELECT * FROM stockrescue.transfer_command_intent WHERE org_id=:org AND actor_user_id=:actor AND state IN ('PREPARED','SUBMITTED')")
                .param("org", organizationId).param("actor", actorId)
                .query((row, index) -> map(row)).optional();
    }

    public List<Pending> pending(UUID organizationId, UUID actorId) {
        return jdbc.sql("""
                SELECT id,operation,transfer_id,request_id,state,prepared_at
                FROM stockrescue.transfer_command_intent
                WHERE org_id=:org AND actor_user_id=:actor AND state IN ('PREPARED','SUBMITTED')
                ORDER BY prepared_at,id LIMIT 2
                """)
                .param("org", organizationId).param("actor", actorId)
                .query((row, index) -> new Pending(row.getObject("id", UUID.class), row.getString("operation"),
                        row.getObject("transfer_id", UUID.class), row.getObject("request_id", UUID.class),
                        row.getString("state"), row.getTimestamp("prepared_at").toInstant())).list();
    }

    public boolean hasCommittedCommand(Intent intent) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM stockrescue.transfer_command_receipt
                WHERE org_id=:org AND actor_user_id=:actor AND operation=:operation AND request_id=:request)
                """)
                .param("org", intent.organizationId()).param("actor", intent.actorId()).param("operation", intent.operation())
                .param("request", intent.requestId()).query(Boolean.class).single();
    }

    public boolean hasCommittedCommand(UUID organizationId, UUID actorId, String operation, UUID requestId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM stockrescue.transfer_command_receipt WHERE org_id=:org AND actor_user_id=:actor AND operation=:operation AND request_id=:request)")
                .param("org", organizationId).param("actor", actorId).param("operation", operation).param("request", requestId)
                .query(Boolean.class).single();
    }

    public boolean markSubmitted(UUID organizationId, UUID actorId, UUID id) {
        return jdbc.sql("UPDATE stockrescue.transfer_command_intent SET state='SUBMITTED' WHERE org_id=:org AND actor_user_id=:actor AND id=:id AND state IN ('PREPARED','SUBMITTED')")
                .param("org", organizationId).param("actor", actorId).param("id", id).update() == 1;
    }

    public boolean acknowledge(UUID organizationId, UUID actorId, UUID id) {
        return jdbc.sql("""
                UPDATE stockrescue.transfer_command_intent i
                SET state='ACKNOWLEDGED',executed_at=clock_timestamp(),resolved_at=clock_timestamp()
                WHERE i.org_id=:org AND i.actor_user_id=:actor AND i.id=:id AND i.state='SUBMITTED'
                  AND EXISTS (SELECT 1 FROM stockrescue.transfer_command_receipt c
                    WHERE c.org_id=i.org_id AND c.actor_user_id=i.actor_user_id
                      AND c.operation=i.operation AND c.request_id=i.request_id
                      AND (i.transfer_id IS NULL OR c.transfer_id=i.transfer_id))
                """)
                .param("org", organizationId).param("actor", actorId).param("id", id).update() == 1;
    }

    public boolean resolveFailure(UUID organizationId, UUID actorId, UUID id, String state) {
        return jdbc.sql("""
                UPDATE stockrescue.transfer_command_intent i
                SET state=:state,resolved_at=clock_timestamp()
                WHERE i.org_id=:org AND i.actor_user_id=:actor AND i.id=:id AND i.state IN ('PREPARED','SUBMITTED')
                  AND NOT EXISTS (SELECT 1 FROM stockrescue.transfer_command_receipt c
                    WHERE c.org_id=i.org_id AND c.actor_user_id=i.actor_user_id
                      AND c.operation=i.operation AND c.request_id=i.request_id)
                """)
                .param("org", organizationId).param("actor", actorId).param("id", id).param("state", state).update() == 1;
    }

    public int expirePendingWithoutOutcome() {
        int acknowledged = jdbc.sql("""
                UPDATE stockrescue.transfer_command_intent i
                SET state='ACKNOWLEDGED',executed_at=c.recorded_at,resolved_at=clock_timestamp()
                FROM stockrescue.transfer_command_receipt c
                WHERE i.state='SUBMITTED' AND i.prepared_at<clock_timestamp()-interval '30 days'
                  AND c.org_id=i.org_id AND c.actor_user_id=i.actor_user_id
                  AND c.operation=i.operation AND c.request_id=i.request_id
                  AND (i.transfer_id IS NULL OR c.transfer_id=i.transfer_id)
                """).update();
        int preparedExpired = jdbc.sql("""
                UPDATE stockrescue.transfer_command_intent i
                SET state='REJECTED',resolved_at=clock_timestamp()
                WHERE i.state='PREPARED' AND i.prepared_at<clock_timestamp()-interval '30 days'
                  AND NOT EXISTS (SELECT 1 FROM stockrescue.transfer_command_receipt c
                    WHERE c.org_id=i.org_id AND c.actor_user_id=i.actor_user_id
                      AND c.operation=i.operation AND c.request_id=i.request_id)
                """).update();
        int submittedExpired = jdbc.sql("""
                UPDATE stockrescue.transfer_command_intent i
                SET state='CONFLICTED',resolved_at=clock_timestamp()
                WHERE i.state='SUBMITTED' AND i.prepared_at<clock_timestamp()-interval '30 days'
                  AND NOT EXISTS (SELECT 1 FROM stockrescue.transfer_command_receipt c
                    WHERE c.org_id=i.org_id AND c.actor_user_id=i.actor_user_id
                    AND c.operation=i.operation AND c.request_id=i.request_id)
                """).update();
        return acknowledged + preparedExpired + submittedExpired;
    }

    public int purgeExpiredResolved() {
        return jdbc.sql("DELETE FROM stockrescue.transfer_command_intent WHERE state IN ('ACKNOWLEDGED','REJECTED','CONFLICTED') AND resolved_at<clock_timestamp()-interval '90 days'")
                .update();
    }

    private static Intent map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Intent(row.getObject("id", UUID.class), row.getObject("org_id", UUID.class),
                row.getObject("transfer_id", UUID.class), row.getObject("actor_user_id", UUID.class),
                row.getObject("membership_id", UUID.class), row.getString("operation"), row.getObject("request_id", UUID.class),
                row.getBytes("payload_fingerprint"), row.getString("payload"), row.getString("state"),
                row.getTimestamp("prepared_at").toInstant(), row.getTimestamp("executed_at") == null ? null : row.getTimestamp("executed_at").toInstant(),
                row.getTimestamp("resolved_at") == null ? null : row.getTimestamp("resolved_at").toInstant());
    }
}
