package com.stockrescue.inventory.persistence;

import java.util.UUID;

import com.stockrescue.inventory.messaging.InventoryStockChangedEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryMessagingRepository {
    public record Inbox(byte[] payloadDigest,String outcome) {
        public Inbox { payloadDigest=payloadDigest.clone(); }
        @Override public byte[] payloadDigest() { return payloadDigest.clone(); }
    }

    private final JdbcClient jdbc;
    public InventoryMessagingRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    public Inbox findInbox(String consumer,UUID eventId) {
        return jdbc.sql("SELECT payload_digest,processing_outcome FROM stockrescue.inventory_inbox WHERE consumer_name=:consumer AND event_id=:event")
                .param("consumer",consumer).param("event",eventId)
                .query((row,index)->new Inbox(row.getBytes("payload_digest"),row.getString("processing_outcome"))).optional().orElse(null);
    }

    public Long lockProjectionVersion(String consumer,InventoryStockChangedEvent event) {
        return jdbc.sql("""
                SELECT aggregate_version FROM stockrescue.inventory_change_projection
                WHERE consumer_name=:consumer AND org_id=:org AND store_id=:store AND position_id=:position FOR UPDATE
                """).param("consumer",consumer).param("org",event.tenantId()).param("store",event.data().storeId())
                .param("position",event.aggregateId()).query(Long.class).optional().orElse(null);
    }

    public void insertInbox(String consumer,InventoryStockChangedEvent event,byte[] digest,String outcome) {
        jdbc.sql("""
                INSERT INTO stockrescue.inventory_inbox(consumer_name,event_id,org_id,store_id,position_id,aggregate_version,
                    payload_digest,processing_outcome)
                VALUES (:consumer,:event,:org,:store,:position,:version,:digest,:outcome)
                """).param("consumer",consumer).param("event",event.eventId()).param("org",event.tenantId())
                .param("store",event.data().storeId()).param("position",event.aggregateId()).param("version",event.aggregateVersion())
                .param("digest",digest).param("outcome",outcome).update();
    }

    public void appendFeed(String consumer,InventoryStockChangedEvent event,String changeJson) {
        jdbc.sql("""
                INSERT INTO stockrescue.inventory_change_feed(consumer_name,event_id,org_id,store_id,position_id,aggregate_version,change)
                VALUES (:consumer,:event,:org,:store,:position,:version,CAST(:change AS jsonb))
                """).param("consumer",consumer).param("event",event.eventId()).param("org",event.tenantId())
                .param("store",event.data().storeId()).param("position",event.aggregateId()).param("version",event.aggregateVersion())
                .param("change",changeJson).update();
    }

    public void upsertProjection(String consumer,InventoryStockChangedEvent event,String changeJson) {
        jdbc.sql("""
                INSERT INTO stockrescue.inventory_change_projection(consumer_name,org_id,store_id,position_id,event_id,aggregate_version,change)
                VALUES (:consumer,:org,:store,:position,:event,:version,CAST(:change AS jsonb))
                ON CONFLICT (consumer_name,org_id,store_id,position_id) DO UPDATE
                SET event_id=EXCLUDED.event_id,aggregate_version=EXCLUDED.aggregate_version,change=EXCLUDED.change,updated_at=clock_timestamp()
                WHERE stockrescue.inventory_change_projection.aggregate_version<EXCLUDED.aggregate_version
                """).param("consumer",consumer).param("event",event.eventId()).param("org",event.tenantId())
                .param("store",event.data().storeId()).param("position",event.aggregateId()).param("version",event.aggregateVersion())
                .param("change",changeJson).update();
    }
}
