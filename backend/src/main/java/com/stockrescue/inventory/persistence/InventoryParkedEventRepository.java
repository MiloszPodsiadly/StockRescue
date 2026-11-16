package com.stockrescue.inventory.persistence;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InventoryParkedEventRepository {
    private final JdbcClient jdbc;
    public InventoryParkedEventRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Transactional
    public void record(String consumer,String topic,int partition,long offset,UUID eventId,byte[] digest,String code) {
        jdbc.sql("""
                INSERT INTO stockrescue.inventory_consumer_parked_event(consumer_name,topic,partition_id,record_offset,event_id,payload_digest,failure_code)
                VALUES (:consumer,:topic,:partition,:offset,:event,:digest,:code)
                ON CONFLICT (consumer_name,topic,partition_id,record_offset) DO UPDATE
                SET occurrences=stockrescue.inventory_consumer_parked_event.occurrences+1,
                    last_seen_at=greatest(clock_timestamp(),stockrescue.inventory_consumer_parked_event.last_seen_at)
                """).param("consumer",consumer).param("topic",topic).param("partition",partition).param("offset",offset)
                .param("event",eventId).param("digest",digest).param("code",code).update();
    }
}
