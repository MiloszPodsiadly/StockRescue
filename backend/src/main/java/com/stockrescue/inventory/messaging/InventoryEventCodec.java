package com.stockrescue.inventory.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import com.stockrescue.inventory.domain.StockBuckets;
import com.stockrescue.inventory.messaging.InventoryStockChangedEvent.Data;
import com.stockrescue.inventory.messaging.InventoryStockChangedEvent.Delta;
import com.stockrescue.inventory.messaging.InventoryStockChangedEvent.Position;
import com.stockrescue.inventory.persistence.InventoryOutboxRepository.OutboxEvent;
import com.stockrescue.inventory.persistence.InventoryOutboxRedriveRepository.SourceEvidence;
import com.stockrescue.platform.correlation.CorrelationIds;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class InventoryEventCodec {
    private static final Set<String> ENVELOPE_FIELDS=Set.of("eventId","eventType","schemaVersion","occurredAt","tenantId",
            "aggregateType","aggregateId","aggregateVersion","correlationId","causationId","dataClassification","data");
    private static final Set<String> DATA_FIELDS=Set.of("storeId","movementId","unit","delta","position");
    private static final Set<String> DELTA_FIELDS=Set.of("onHand","reserved","quarantined","otherBlocked");
    private static final Set<String> POSITION_FIELDS=Set.of("onHand","reserved","quarantined","otherBlocked","available","trust");

    private final ObjectMapper json;
    public InventoryEventCodec(ObjectMapper json) { this.json=json; }

    public String encode(InventoryStockChangedEvent event) {
        String payload=json.writeValueAsString(event);
        decode(payload);
        return payload;
    }

    public InventoryStockChangedEvent decode(String payload) {
        final JsonNode root;
        try { root=json.readTree(payload); }
        catch(JacksonException invalid) { throw failure("MALFORMED_EVENT"); }
        requireObject(root,ENVELOPE_FIELDS);
        int schema=intValue(root.get("schemaVersion"));
        if(schema!=1) throw failure("UNSUPPORTED_SCHEMA_VERSION");
        String eventType=text(root.get("eventType"));
        if(!eventType.equals("InventoryStockChanged")) throw failure("UNSUPPORTED_EVENT_TYPE");
        if(!text(root.get("aggregateType")).equals("INVENTORY_POSITION")
                || !text(root.get("dataClassification")).equals("INTERNAL")) throw failure("MALFORMED_EVENT");
        Instant occurredAt;
        try { occurredAt=Instant.parse(text(root.get("occurredAt"))); }
        catch(DateTimeParseException invalid) { throw failure("MALFORMED_EVENT"); }
        UUID eventId=uuid(root.get("eventId")),tenant=uuid(root.get("tenantId")),aggregate=uuid(root.get("aggregateId"));
        String correlation=correlationId(root.get("correlationId"));
        UUID causation=uuid(root.get("causationId"));
        long version=longValue(root.get("aggregateVersion"));
        if(version<1) throw failure("MALFORMED_EVENT");
        JsonNode dataNode=root.get("data");requireObject(dataNode,DATA_FIELDS);
        UUID store=uuid(dataNode.get("storeId")),movement=uuid(dataNode.get("movementId"));
        if(!text(dataNode.get("unit")).equals("EA") || !movement.equals(causation)) throw failure("MALFORMED_EVENT");
        JsonNode deltaNode=dataNode.get("delta");requireObject(deltaNode,DELTA_FIELDS);
        var delta=new Delta(delta(deltaNode.get("onHand")),delta(deltaNode.get("reserved")),
                delta(deltaNode.get("quarantined")),delta(deltaNode.get("otherBlocked")));
        JsonNode positionNode=dataNode.get("position");requireObject(positionNode,POSITION_FIELDS);
        final StockBuckets buckets;
        try {
            buckets=new StockBuckets(quantity(positionNode.get("onHand")),quantity(positionNode.get("reserved")),
                    quantity(positionNode.get("quarantined")),quantity(positionNode.get("otherBlocked")));
        } catch(IllegalArgumentException invalid) { throw failure("MALFORMED_EVENT"); }
        long available=quantity(positionNode.get("available"));
        if(available!=buckets.available()) throw failure("MALFORMED_EVENT");
        String trust=text(positionNode.get("trust"));
        if(!Set.of("UNVERIFIED","CLEAN","FROZEN").contains(trust)) throw failure("MALFORMED_EVENT");
        return new InventoryStockChangedEvent(eventId,eventType,schema,occurredAt,tenant,"INVENTORY_POSITION",aggregate,version,
                correlation,causation,"INTERNAL",new Data(store,movement,"EA",delta,
                new Position(buckets.onHand(),buckets.reserved(),buckets.quarantined(),buckets.otherBlocked(),available,trust)));
    }

    public InventoryStockChangedEvent fromOutbox(OutboxEvent row) {
        final JsonNode stored;
        try { stored=json.readTree(row.payload()); }
        catch(JacksonException invalid) { throw failure("OUTBOX_CONTRACT_INVALID"); }
        String correlation=outboxCorrelationId(row.correlationId());
        if(stored==null || !stored.isObject() || !stored.path("eventId").isString() || !stored.path("eventId").stringValue().equals(row.id().toString())
                || !row.eventType().equals("InventoryStockChanged") || row.schemaVersion()!=1
                || !stored.path("eventType").isString() || !stored.path("eventType").stringValue().equals("InventoryStockChanged")
                || stored.path("schemaVersion").asInt(-1)!=1
                || !sameUuid(stored.get("organizationId"),row.organizationId()) || !sameUuid(stored.get("storeId"),row.storeId())
                || !sameUuid(stored.get("positionId"),row.positionId()) || stored.path("aggregateVersion").asLong(-1)!=row.aggregateVersion()
                || !sameUuid(stored.get("movementId"),row.movementId()) || !sameInstant(stored.get("occurredAt"),row.occurredAt())
                || !sameNullableText(stored.get("correlationId"),correlation)) throw failure("OUTBOX_CONTRACT_INVALID");
        JsonNode change=stored.path("change");
        if(!change.isObject() || !change.path("unit").isString() || !change.path("unit").stringValue().equals("EA"))
            throw failure("OUTBOX_CONTRACT_INVALID");
        JsonNode oldDelta=change.get("delta"),oldPosition=change.get("position");
        if(oldDelta==null || !oldDelta.isObject() || oldPosition==null || !oldPosition.isObject() || !sameUuid(oldPosition.get("id"),row.positionId())
                || !sameUuid(oldPosition.get("organizationId"),row.organizationId()) || !sameUuid(oldPosition.get("storeId"),row.storeId())
                || oldPosition.path("version").asLong(-1)!=row.aggregateVersion()) throw failure("OUTBOX_CONTRACT_INVALID");
        JsonNode oldBuckets=oldPosition.get("buckets");
        if(oldBuckets==null || !oldBuckets.isObject()) throw failure("OUTBOX_CONTRACT_INVALID");
        try {
            var event=new InventoryStockChangedEvent(row.id(),"InventoryStockChanged",1,row.occurredAt(),row.organizationId(),
                    "INVENTORY_POSITION",row.positionId(),row.aggregateVersion(),correlation,row.movementId(),"INTERNAL",
                    new Data(row.storeId(),row.movementId(),"EA",
                            new Delta(delta(oldDelta.get("onHand")),delta(oldDelta.get("reserved")),delta(oldDelta.get("quarantined")),delta(oldDelta.get("otherBlocked"))),
                            new Position(quantity(oldBuckets.get("onHand")),quantity(oldBuckets.get("reserved")),quantity(oldBuckets.get("quarantined")),
                                    quantity(oldBuckets.get("otherBlocked")),quantity(oldBuckets.get("onHand"))-quantity(oldBuckets.get("reserved"))
                                            -quantity(oldBuckets.get("quarantined"))-quantity(oldBuckets.get("otherBlocked")),
                                    text(oldPosition.get("trust")))));
            decode(encode(event));
            return event;
        } catch(InventoryEventContractException invalid) { throw failure("OUTBOX_CONTRACT_INVALID"); }
    }

    public InventoryStockChangedEvent fromOutbox(OutboxEvent row,SourceEvidence source) {
        InventoryStockChangedEvent event=fromOutbox(row);
        if(source==null || source.sourceVersionCount()!=row.aggregateVersion()
                || !"INITIALIZED".equals(source.initializationAfter())
                || !Set.of("UNVERIFIED","CLEAN","FROZEN").contains(source.trustAfter())) {
            throw failure("OUTBOX_SOURCE_INVALID");
        }
        var delta=event.data().delta();var position=event.data().position();
        if(delta.onHand()!=source.deltaOnHand() || delta.reserved()!=source.deltaReserved()
                || delta.quarantined()!=source.deltaQuarantined() || delta.otherBlocked()!=source.deltaOtherBlocked()
                || !position.trust().equals(source.trustAfter())) {
            throw failure("OUTBOX_SOURCE_INVALID");
        }
        try {
            var buckets=new StockBuckets(source.onHand().longValueExact(),source.reserved().longValueExact(),
                    source.quarantined().longValueExact(),source.otherBlocked().longValueExact());
            if(position.onHand()!=buckets.onHand() || position.reserved()!=buckets.reserved()
                    || position.quarantined()!=buckets.quarantined() || position.otherBlocked()!=buckets.otherBlocked()) {
                throw failure("OUTBOX_SOURCE_INVALID");
            }
        } catch(ArithmeticException|IllegalArgumentException invalid) { throw failure("OUTBOX_SOURCE_INVALID"); }
        return event;
    }

    private static void requireObject(JsonNode node,Set<String> fields) {
        if(node==null || !node.isObject() || node.size()!=fields.size()) throw failure("MALFORMED_EVENT");
        for(String name:node.propertyNames()) if(!fields.contains(name)) throw failure("MALFORMED_EVENT");
        for(String field:fields) if(!node.has(field)) throw failure("MALFORMED_EVENT");
    }
    private static UUID uuid(JsonNode value) {
        try { String text=text(value);UUID result=UUID.fromString(text);if(!result.toString().equals(text)) throw failure("MALFORMED_EVENT");return result; }
        catch(IllegalArgumentException invalid) { throw failure("MALFORMED_EVENT"); }
    }
    private static String correlationId(JsonNode value) {
        if(value!=null&&value.isNull()) return null;
        if(value==null || !value.isString() || !CorrelationIds.isValid(value.stringValue())) throw failure("MALFORMED_EVENT");
        return value.stringValue();
    }
    private static String outboxCorrelationId(String value) {
        if(value!=null&&!CorrelationIds.isValid(value)) throw failure("OUTBOX_CONTRACT_INVALID");
        return value;
    }
    private static boolean sameUuid(JsonNode value,UUID expected) {
        try { return expected.equals(uuid(value)); } catch(InventoryEventContractException invalid) { return false; }
    }
    private static boolean sameNullableText(JsonNode value,String expected) {
        if(expected==null) return value!=null&&value.isNull();
        return value!=null&&value.isString()&&expected.equals(value.stringValue());
    }
    private static boolean sameInstant(JsonNode value,Instant expected) {
        try { return Instant.parse(text(value)).equals(expected); } catch(DateTimeParseException|InventoryEventContractException invalid) { return false; }
    }
    private static long quantity(JsonNode value) { long number=longValue(value);if(number<0) throw failure("MALFORMED_EVENT");return number; }
    private static long delta(JsonNode value) { long number=longValue(value);if(number==Long.MIN_VALUE) throw failure("MALFORMED_EVENT");return number; }
    private static long longValue(JsonNode value) { if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()) throw failure("MALFORMED_EVENT");return value.longValue(); }
    private static int intValue(JsonNode value) { long number=longValue(value);if(number<Integer.MIN_VALUE||number>Integer.MAX_VALUE) throw failure("MALFORMED_EVENT");return (int)number; }
    private static String text(JsonNode value) { if(value==null||!value.isString()||value.stringValue().isBlank()) throw failure("MALFORMED_EVENT");return value.stringValue(); }
    private static InventoryEventContractException failure(String code) { return new InventoryEventContractException(code); }

    public UUID eventIdOrNull(String payload) {
        try {
            var node=json.readTree(payload);
            if(node==null || !node.isObject() || !node.path("eventId").isString()) return null;
            UUID id=UUID.fromString(node.path("eventId").stringValue());
            return id.toString().equals(node.path("eventId").stringValue())?id:null;
        } catch(JacksonException | IllegalArgumentException invalid) { return null; }
    }
}
