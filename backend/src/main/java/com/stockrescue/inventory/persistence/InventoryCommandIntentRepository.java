package com.stockrescue.inventory.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryCommandIntentRepository {
    public record Intent(UUID id,UUID organizationId,UUID storeId,UUID actorId,UUID membershipId,String operation,
            String subject,UUID requestId,byte[] fingerprint,String payload,String state,Instant preparedAt,Instant executedAt,Instant resolvedAt) { }
    public record Pending(UUID id,String operation,String subject,UUID requestId,Instant preparedAt) { }

    private final JdbcClient jdbc;
    public InventoryCommandIntentRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    public boolean insert(Intent intent) {
        return jdbc.sql("""
                INSERT INTO stockrescue.inventory_command_intent
                    (id,org_id,store_id,actor_user_id,membership_id,operation,subject,request_id,payload_fingerprint,payload)
                VALUES (:id,:org,:store,:actor,:membership,:operation,:subject,:request,:fingerprint,CAST(:payload AS jsonb))
                ON CONFLICT DO NOTHING
                """).param("id",intent.id()).param("org",intent.organizationId()).param("store",intent.storeId())
                .param("actor",intent.actorId()).param("membership",intent.membershipId()).param("operation",intent.operation())
                .param("subject",intent.subject()).param("request",intent.requestId()).param("fingerprint",intent.fingerprint())
                .param("payload",intent.payload()).update()==1;
    }

    public Optional<Intent> byId(UUID organizationId,UUID storeId,UUID actorId,UUID id,boolean lock) {
        return jdbc.sql("SELECT * FROM stockrescue.inventory_command_intent WHERE org_id=:org AND store_id=:store AND actor_user_id=:actor AND id=:id"
                        +(lock?" FOR UPDATE":""))
                .param("org",organizationId).param("store",storeId).param("actor",actorId).param("id",id)
                .query((row,index)->map(row)).optional();
    }

    public Optional<Intent> byPrepareId(UUID organizationId,UUID actorId,UUID id) {
        return jdbc.sql("SELECT * FROM stockrescue.inventory_command_intent WHERE org_id=:org AND actor_user_id=:actor AND id=:id")
                .param("org",organizationId).param("actor",actorId).param("id",id).query((row,index)->map(row)).optional();
    }

    public Optional<Intent> byRequest(UUID organizationId,UUID actorId,String operation,UUID requestId) {
        return jdbc.sql("SELECT * FROM stockrescue.inventory_command_intent WHERE org_id=:org AND actor_user_id=:actor AND operation=:operation AND request_id=:request")
                .param("org",organizationId).param("actor",actorId).param("operation",operation).param("request",requestId)
                .query((row,index)->map(row)).optional();
    }

    public Optional<Intent> activeBySubject(UUID organizationId,UUID storeId,UUID actorId,String subject) {
        return jdbc.sql("SELECT * FROM stockrescue.inventory_command_intent WHERE org_id=:org AND store_id=:store AND actor_user_id=:actor AND subject=:subject AND state='PENDING'")
                .param("org",organizationId).param("store",storeId).param("actor",actorId).param("subject",subject)
                .query((row,index)->map(row)).optional();
    }

    public List<Pending> pending(UUID organizationId,UUID storeId,UUID actorId,String subject,int offset,int limit) {
        return jdbc.sql("""
                SELECT id,operation,subject,request_id,prepared_at FROM stockrescue.inventory_command_intent
                WHERE org_id=:org AND store_id=:store AND actor_user_id=:actor AND state='PENDING'
                    AND (CAST(:subject AS varchar) IS NULL OR subject=:subject)
                ORDER BY prepared_at,id LIMIT :limit OFFSET :offset
                """)
                .param("org",organizationId).param("store",storeId).param("actor",actorId)
                .param("subject",subject,java.sql.Types.VARCHAR).param("offset",offset).param("limit",limit)
                .query((row,index)->new Pending(row.getObject("id",UUID.class),row.getString("operation"),row.getString("subject"),
                        row.getObject("request_id",UUID.class),row.getTimestamp("prepared_at").toInstant())).list();
    }

    public boolean hasCommittedCommand(Intent intent) {
        String operation=intent.operation().equals("RECEIPT_DOCUMENT")?"RECEIVE":intent.operation();
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM stockrescue.inventory_command_receipt WHERE org_id=:org AND store_id=:store AND actor_user_id=:actor AND operation=:operation AND request_id=:request)")
                .param("org",intent.organizationId()).param("store",intent.storeId()).param("actor",intent.actorId())
                .param("operation",operation).param("request",intent.requestId()).query(Boolean.class).single();
    }

    public boolean resolve(UUID organizationId,UUID storeId,UUID actorId,UUID id,String state) {
        return jdbc.sql("UPDATE stockrescue.inventory_command_intent SET state=:state,resolved_at=clock_timestamp() WHERE org_id=:org AND store_id=:store AND actor_user_id=:actor AND id=:id AND state='PENDING'")
                .param("state",state).param("org",organizationId).param("store",storeId).param("actor",actorId).param("id",id).update()==1;
    }

    public boolean markExecuted(UUID organizationId,UUID storeId,UUID actorId,UUID id) {
        return jdbc.sql("UPDATE stockrescue.inventory_command_intent SET executed_at=clock_timestamp() WHERE org_id=:org AND store_id=:store AND actor_user_id=:actor AND id=:id AND state='PENDING' AND executed_at IS NULL")
                .param("org",organizationId).param("store",storeId).param("actor",actorId).param("id",id).update()==1;
    }

    public int purgeExpiredResolved() {
        return jdbc.sql("DELETE FROM stockrescue.inventory_command_intent WHERE state IN ('ACKNOWLEDGED','REVIEWED') AND resolved_at<clock_timestamp()-interval '90 days'")
                .update();
    }

    private static Intent map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Intent(row.getObject("id",UUID.class),row.getObject("org_id",UUID.class),row.getObject("store_id",UUID.class),
                row.getObject("actor_user_id",UUID.class),row.getObject("membership_id",UUID.class),row.getString("operation"),
                row.getString("subject"),row.getObject("request_id",UUID.class),row.getBytes("payload_fingerprint"),
                row.getString("payload"),row.getString("state"),row.getTimestamp("prepared_at").toInstant(),
                row.getTimestamp("executed_at")==null?null:row.getTimestamp("executed_at").toInstant(),
                row.getTimestamp("resolved_at")==null?null:row.getTimestamp("resolved_at").toInstant());
    }
}
