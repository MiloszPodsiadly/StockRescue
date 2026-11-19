package com.stockrescue.transfers.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.stockrescue.transfers.messaging.TransferEventCodec;
import com.stockrescue.transfers.messaging.TransferAggregateEvent;
import com.stockrescue.transfers.messaging.TransferEventContractException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TransferInboxRepository {
    public record Pending(UUID eventId,long version,String payload,byte[] digest) {
        public Pending { digest=digest.clone(); }
        @Override public byte[] digest() { return digest.clone(); }
    }
    public enum Outcome { APPLIED, DEFERRED, DUPLICATE }

    private final JdbcClient jdbc;
    private final TransferEventCodec pendingCodec;

    @Transactional
    public Outcome receive(String consumer,TransferAggregateEvent event,String payload,byte[] digest) {
        String lockKey=consumer+":"+event.orgId()+":"+event.transferId();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,90871))").param("key",lockKey)
                .query((row,index)->Boolean.TRUE).single();
        int inserted=jdbc.sql("""
                INSERT INTO stockrescue.transfer_inbox(consumer_name,event_id,org_id,transfer_id,aggregate_version,payload_digest,payload)
                VALUES (:consumer,:event,:org,:transfer,:version,:digest,CAST(:payload AS jsonb))
                ON CONFLICT (consumer_name,event_id) DO NOTHING
                """).param("consumer",consumer).param("event",event.eventId()).param("org",event.orgId())
                .param("transfer",event.transferId()).param("version",event.aggregateVersion()).param("digest",digest)
                .param("payload",payload).update();
        if(inserted==0) {
            var known=jdbc.sql("SELECT payload_digest,applied_at FROM stockrescue.transfer_inbox WHERE consumer_name=:consumer AND event_id=:event")
                    .param("consumer",consumer).param("event",event.eventId())
                    .query((row,index)->new Existing(row.getBytes("payload_digest"),row.getTimestamp("applied_at")!=null)).single();
            if(!java.security.MessageDigest.isEqual(known.digest(),digest)) throw new TransferEventContractException("EVENT_ID_CONFLICT");
            if(known.applied()) return Outcome.DUPLICATE;
        }
        long last=jdbc.sql("SELECT coalesce((SELECT aggregate_version FROM stockrescue.transfer_change_projection "
                +"WHERE consumer_name=:consumer AND org_id=:org AND transfer_id=:transfer),-1)")
                .param("consumer",consumer).param("org",event.orgId()).param("transfer",event.transferId())
                .query(Long.class).single();
        if(event.aggregateVersion()>last+1) return Outcome.DEFERRED;
        if(event.aggregateVersion()<=last) throw new TransferEventContractException("OUT_OF_ORDER_EVENT");
        apply(event,payload,consumer);
        long next=event.aggregateVersion()+1;
        while(true) {
            Optional<Pending> pending=pending(consumer,event.orgId(),event.transferId(),next);
            if(pending.isEmpty()) break;
            Pending row=pending.orElseThrow();
            TransferAggregateEvent nextEvent=decodePending(row);
            apply(nextEvent,row.payload(),consumer);
            next++;
        }
        return Outcome.APPLIED;
    }

    private Optional<Pending> pending(String consumer,UUID org,UUID transfer,long version) {
        return jdbc.sql("""
                SELECT event_id,aggregate_version,payload::text AS payload,payload_digest
                FROM stockrescue.transfer_inbox
                WHERE consumer_name=:consumer AND org_id=:org AND transfer_id=:transfer
                    AND aggregate_version=:version AND applied_at IS NULL
                """).param("consumer",consumer).param("org",org).param("transfer",transfer).param("version",version)
                .query((row,index)->new Pending(row.getObject("event_id",UUID.class),row.getLong("aggregate_version"),
                        row.getString("payload"),row.getBytes("payload_digest"))).optional();
    }

    private void apply(TransferAggregateEvent event,String payload,String consumer) {
        Instant occurred=event.occurredAt();
        int projected=jdbc.sql("""
                INSERT INTO stockrescue.transfer_change_projection(consumer_name,org_id,transfer_id,aggregate_version,event_id,status,payload,recorded_at)
                VALUES (:consumer,:org,:transfer,:version,:event,:status,CAST(:payload AS jsonb),:recorded)
                ON CONFLICT (consumer_name,org_id,transfer_id) DO UPDATE SET aggregate_version=excluded.aggregate_version,
                    event_id=excluded.event_id,status=excluded.status,payload=excluded.payload,recorded_at=excluded.recorded_at
                WHERE stockrescue.transfer_change_projection.aggregate_version=excluded.aggregate_version-1
                """).param("consumer",consumer).param("org",event.orgId()).param("transfer",event.transferId())
                .param("version",event.aggregateVersion()).param("event",event.eventId()).param("status",event.data().statusAfter())
                .param("payload",payload).param("recorded",java.sql.Timestamp.from(occurred)).update();
        if(projected!=1) throw new TransferEventContractException("INBOX_SEQUENCE_CONFLICT");
        int marked=jdbc.sql("""
                UPDATE stockrescue.transfer_inbox SET applied_at=clock_timestamp()
                WHERE consumer_name=:consumer AND event_id=:event AND applied_at IS NULL
                """).param("consumer",consumer).param("event",event.eventId()).update();
        if(marked!=1) throw new TransferEventContractException("INBOX_STATE_CONFLICT");
    }

    private TransferAggregateEvent decodePending(Pending row) {
        try { return pendingCodec.decode(row.payload()); }
        catch(TransferEventContractException invalid) { throw invalid; }
    }

    public TransferInboxRepository(JdbcClient jdbc,TransferEventCodec codec) { this.jdbc=jdbc;this.pendingCodec=codec; }

    private record Existing(byte[] digest,boolean applied) {
        private Existing { digest=digest.clone(); }
        @Override public byte[] digest() { return digest.clone(); }
    }
}
