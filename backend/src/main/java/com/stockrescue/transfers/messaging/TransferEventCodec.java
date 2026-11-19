package com.stockrescue.transfers.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import com.stockrescue.platform.correlation.CorrelationIds;
import com.stockrescue.transfers.persistence.TransferOutboxRepository.OutboxEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class TransferEventCodec {
    private static final Set<String> EVENT_TYPES=Set.of("TransferCommandRecorded","TransferDispatched","TransferReceived",
            "TransferReturned","TransferDiscrepancyResolved");
    private static final Set<String> OPERATIONS=Set.of("CREATE","APPROVE","REJECT","CANCEL","START_PICK","PICK",
            "DISPATCH","RECEIVE","OPEN_DISCREPANCY","CONFIRM_LOSS","REQUEST_RETURN","CONFIRM_RETURN",
            "RESUME","CLOSE","RECONCILE");
    private static final Set<String> STATUSES=Set.of("REQUESTED","REJECTED","CANCELLED","APPROVED","PICKING",
            "PICKED","IN_TRANSIT","PARTIALLY_RECEIVED","RECONCILIATION_REQUIRED","RECEIVED","CLOSED_WITH_DISCREPANCY");
    private static final Set<String> ENVELOPE=Set.of("eventId","eventType","schemaVersion","occurredAt","orgId",
            "transferId","aggregateVersion","correlationId","causationId","commandId","dataClassification","data");
    private static final Set<String> DATA=Set.of("operation","statusBefore","statusAfter","sourceStoreId",
            "destinationStoreId","stockMovements","custodyMovements");
    private static final Set<String> STOCK_REF=Set.of("storeId","movementId");
    private static final Set<String> CUSTODY_REF=Set.of("id","lineId","movementType","quantity","stockStoreId",
            "stockMovementId","stockPositionId");

    private final ObjectMapper json;
    public TransferEventCodec(ObjectMapper json) { this.json=json; }

    public String encode(TransferAggregateEvent event) {
        String payload=json.writeValueAsString(event);
        decode(payload);
        return payload;
    }

    public TransferAggregateEvent decode(String payload) {
        final JsonNode root;
        try { root=json.readTree(payload); }
        catch(JacksonException malformed) { throw failure("MALFORMED_EVENT"); }
        requireObject(root,ENVELOPE);
        int version=intValue(root.get("schemaVersion"));
        String type=text(root.get("eventType"));
        if(version!=1) throw failure("UNSUPPORTED_SCHEMA_VERSION");
        if(!EVENT_TYPES.contains(type)) throw failure("UNSUPPORTED_EVENT_TYPE");
        if(!text(root.get("dataClassification")).equals("INTERNAL")) throw failure("MALFORMED_EVENT");
        Instant occurred;
        try { occurred=Instant.parse(text(root.get("occurredAt"))); }
        catch(DateTimeParseException invalid) { throw failure("MALFORMED_EVENT"); }
        UUID id=uuid(root.get("eventId")),org=uuid(root.get("orgId")),transfer=uuid(root.get("transferId"));
        long aggregateVersion=longValue(root.get("aggregateVersion"));
        if(aggregateVersion<0) throw failure("MALFORMED_EVENT");
        String correlation=nullableText(root.get("correlationId"));
        if(correlation!=null&&!CorrelationIds.isValid(correlation)) throw failure("MALFORMED_EVENT");
        UUID causation=uuid(root.get("causationId")),command=uuid(root.get("commandId"));
        if(!causation.equals(command)) throw failure("MALFORMED_EVENT");
        JsonNode data=root.get("data");requireObject(data,DATA);
        String operation=text(data.get("operation")),after=text(data.get("statusAfter"));
        String before=nullableText(data.get("statusBefore"));
        if(!OPERATIONS.contains(operation)||!STATUSES.contains(after)) throw failure("MALFORMED_EVENT");
        if(aggregateVersion==0) {
            if(before!=null||!after.equals("REQUESTED")||!operation.equals("CREATE")) throw failure("MALFORMED_EVENT");
        } else if(before==null||!STATUSES.contains(before)||operation.equals("CREATE")) throw failure("MALFORMED_EVENT");
        UUID source=uuid(data.get("sourceStoreId")),destination=uuid(data.get("destinationStoreId"));
        if(source.equals(destination)) throw failure("MALFORMED_EVENT");
        JsonNode stockNodes=data.get("stockMovements"),custodyNodes=data.get("custodyMovements");
        if(!stockNodes.isArray()||!custodyNodes.isArray()||stockNodes.size()>50||custodyNodes.size()>50)
            throw failure("MALFORMED_EVENT");
        var stock=new ArrayList<TransferAggregateEvent.StockMovementRef>(stockNodes.size());
        for(JsonNode node:stockNodes) {
            requireObject(node,STOCK_REF);
            stock.add(new TransferAggregateEvent.StockMovementRef(uuid(node.get("storeId")),uuid(node.get("movementId"))));
        }
        var custody=new ArrayList<TransferAggregateEvent.CustodyMovementRef>(custodyNodes.size());
        for(JsonNode node:custodyNodes) {
            requireObject(node,CUSTODY_REF);
            long quantity=longValue(node.get("quantity"));
            String movementType=text(node.get("movementType"));
            if(quantity<1||!Set.of("DISPATCH","RECEIPT","LOSS","RETURN").contains(movementType)) throw failure("MALFORMED_EVENT");
            custody.add(new TransferAggregateEvent.CustodyMovementRef(uuid(node.get("id")),uuid(node.get("lineId")),
                    movementType,quantity,nullableUuid(node.get("stockStoreId")),nullableUuid(node.get("stockMovementId")),
                    nullableUuid(node.get("stockPositionId"))));
        }
        String expectedType=switch(operation) {
            case "DISPATCH" -> "TransferDispatched";
            case "RECEIVE" -> "TransferReceived";
            case "CONFIRM_RETURN" -> "TransferReturned";
            case "CONFIRM_LOSS" -> "TransferDiscrepancyResolved";
            default -> "TransferCommandRecorded";
        };
        if(!type.equals(expectedType)) throw failure("MALFORMED_EVENT");
        return new TransferAggregateEvent(id,type,version,occurred,org,transfer,aggregateVersion,correlation,causation,
                command,"INTERNAL",new TransferAggregateEvent.Data(operation,before,after,source,destination,stock,custody));
    }

    public TransferAggregateEvent fromOutbox(OutboxEvent row) {
        final JsonNode stored;
        try { stored=json.readTree(row.payload()); }
        catch(JacksonException malformed) { throw failure("OUTBOX_CONTRACT_INVALID"); }
        if(stored==null||!stored.isObject()||!sameUuid(stored.get("eventId"),row.id())
                ||!sameText(stored.get("eventType"),row.eventType())||intValueSafe(stored.get("schemaVersion"))!=row.schemaVersion()
                ||!sameUuid(stored.get("orgId"),row.organizationId())||!sameUuid(stored.get("transferId"),row.transferId())
                ||longValueSafe(stored.get("aggregateVersion"))!=row.aggregateVersion()
                ||!sameInstant(stored.get("occurredAt"),row.occurredAt())
                ||!sameNullableText(stored.get("correlationId"),row.correlationId())) throw failure("OUTBOX_CONTRACT_INVALID");
        try {
            var event=decode(row.payload());
            if(!event.eventId().equals(row.id())||!event.orgId().equals(row.organizationId())
                    ||!event.transferId().equals(row.transferId())||event.aggregateVersion()!=row.aggregateVersion()
                    ||!event.eventType().equals(row.eventType())) throw failure("OUTBOX_CONTRACT_INVALID");
            return event;
        } catch(TransferEventContractException invalid) { throw failure("OUTBOX_CONTRACT_INVALID"); }
    }

    public String eventTypeOrNull(String payload) {
        try {
            JsonNode root=json.readTree(payload);
            return root!=null&&root.isObject()&&root.path("eventType").isString()?root.path("eventType").stringValue():null;
        } catch(JacksonException malformed) { return null; }
    }

    private static void requireObject(JsonNode node,Set<String> fields) {
        if(node==null||!node.isObject()||node.size()!=fields.size()) throw failure("MALFORMED_EVENT");
        for(String name:node.propertyNames()) if(!fields.contains(name)) throw failure("MALFORMED_EVENT");
        for(String field:fields) if(!node.has(field)) throw failure("MALFORMED_EVENT");
    }
    private static UUID uuid(JsonNode value) {
        if(value==null||!value.isString()) throw failure("MALFORMED_EVENT");
        try { UUID parsed=UUID.fromString(value.stringValue());if(!parsed.toString().equals(value.stringValue())) throw failure("MALFORMED_EVENT");return parsed; }
        catch(IllegalArgumentException invalid) { throw failure("MALFORMED_EVENT"); }
    }
    private static UUID nullableUuid(JsonNode value) { return value==null||value.isNull()?null:uuid(value); }
    private static String text(JsonNode value) { if(value==null||!value.isString()||value.stringValue().isBlank()) throw failure("MALFORMED_EVENT");return value.stringValue(); }
    private static String nullableText(JsonNode value) { if(value==null||value.isNull()) return null;return text(value); }
    private static long longValue(JsonNode value) {
        if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()) throw failure("MALFORMED_EVENT");return value.longValue();
    }
    private static int intValue(JsonNode value) { long number=longValue(value);if(number<0||number>Integer.MAX_VALUE) throw failure("MALFORMED_EVENT");return (int)number; }
    private static boolean sameUuid(JsonNode value,UUID expected) { try{return uuid(value).equals(expected);}catch(TransferEventContractException invalid){return false;} }
    private static boolean sameText(JsonNode value,String expected) { return value!=null&&value.isString()&&expected.equals(value.stringValue()); }
    private static boolean sameNullableText(JsonNode value,String expected) { return expected==null?value!=null&&value.isNull():sameText(value,expected); }
    private static boolean sameInstant(JsonNode value,Instant expected) {
        try{return Instant.parse(text(value)).equals(expected);}catch(DateTimeParseException|TransferEventContractException invalid){return false;}
    }
    private static int intValueSafe(JsonNode value) { try{return intValue(value);}catch(TransferEventContractException invalid){return -1;} }
    private static long longValueSafe(JsonNode value) { try{return longValue(value);}catch(TransferEventContractException invalid){return -1;} }
    private static TransferEventContractException failure(String code) { return new TransferEventContractException(code); }
}
