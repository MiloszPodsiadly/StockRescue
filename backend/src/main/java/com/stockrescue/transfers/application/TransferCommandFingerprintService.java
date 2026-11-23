package com.stockrescue.transfers.application;

import java.time.Instant;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.transfers.application.TransferService.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TransferCommandFingerprintService {
    private final ObjectMapper json;

    public TransferCommandFingerprintService(ObjectMapper json) {
        this.json = json;
    }

    public byte[] fromPayload(UUID organizationId, String operation, UUID transferId, JsonNode payload) {
        if (organizationId == null || operation == null || !TransferService.COMMAND_OPERATIONS.contains(operation)
                || payload == null || !payload.isObject() || (operation.equals("CREATE") != (transferId == null)))
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        return switch (operation) {
            case "CREATE" -> forRequest(organizationId, operation, null,
                    TransferRequestNormalizer.normalize(json.treeToValue(payload, CreateRequest.class)));
            case "APPROVE", "REJECT", "START_PICK", "CLOSE" -> forRequest(organizationId, operation, transferId,
                    TransferRequestNormalizer.normalize(json.treeToValue(payload, DecisionRequest.class)));
            case "CANCEL" -> forRequest(organizationId, operation, transferId,
                    TransferRequestNormalizer.normalize(json.treeToValue(payload, CancelRequest.class)));
            case "PICK" -> forRequest(organizationId, operation, transferId,
                    TransferRequestNormalizer.normalize(json.treeToValue(payload, PickRequest.class)));
            case "DISPATCH" -> forRequest(organizationId, operation, transferId,
                    TransferRequestNormalizer.normalize(json.treeToValue(payload, DispatchRequest.class)));
            case "RECEIVE" -> forRequest(organizationId, operation, transferId,
                    TransferRequestNormalizer.normalize(json.treeToValue(payload, ReceiveRequest.class)));
            case "OPEN_DISCREPANCY" -> forRequest(organizationId, operation, transferId,
                    TransferRequestNormalizer.normalize(json.treeToValue(payload, DiscrepancyRequest.class)));
            case "CONFIRM_LOSS" -> forRequest(organizationId, operation, transferId,
                    TransferRequestNormalizer.normalize(json.treeToValue(payload, DamageRequest.class)));
            case "REQUEST_RETURN" -> forRequest(organizationId, operation, transferId,
                    TransferRequestNormalizer.normalize(json.treeToValue(payload, RequestReturn.class)));
            case "CONFIRM_RETURN" -> forRequest(organizationId, operation, transferId,
                    TransferRequestNormalizer.normalize(json.treeToValue(payload, ConfirmReturnRequest.class)));
            default -> throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        };
    }

    byte[] forRequest(UUID organizationId, String operation, UUID transferId, Object request) {
        var values = new ArrayList<Object>();
        values.add(organizationId);
        values.add(operation);
        if (!operation.equals("CREATE")) values.add(transferId);
        switch (operation) {
            case "CREATE" -> {
                var value = (TransferRequestNormalizer.NormalizedRequest) request;
                values.add(value.sourceStoreId());
                values.add(value.destinationStoreId());
                values.add(value.reason());
                values.add(value.evidenceReference());
                values.add(value.lines().stream().map(line -> List.of(line.sourcePositionId(), line.destinationLocationId(),
                        line.quantity(), line.expectedSourceVersion())).toList());
            }
            case "APPROVE", "REJECT", "START_PICK", "CLOSE" -> {
                var value = (DecisionRequest) request;
                values.add(value.expectedVersion());
                values.add(value.reason());
                values.add(value.evidenceReference());
            }
            case "CANCEL" -> {
                var value = (CancelRequest) request;
                values.add(value.expectedVersion());
                values.add(value.reason());
                values.add(value.evidenceReference());
                values.add(value.putBackConfirmed());
                values.add(value.putBackDocumentReference());
            }
            case "PICK" -> {
                var value = (PickRequest) request;
                values.addAll(List.of(value.expectedVersion(), value.lineId(), value.documentReference(),
                        value.quantity(), value.observedSku(), value.observedLotCode(), value.reason(), value.evidenceReference()));
            }
            case "DISPATCH" -> {
                var value = (DispatchRequest) request;
                values.add(value.expectedVersion());
                values.add(value.documentReference());
                values.add(value.lines().stream().map(line -> List.of(line.lineId(), line.quantity(),
                        line.observedSku(), line.observedLotCode())).toList());
                values.add(value.reason());
                values.add(value.evidenceReference());
            }
            case "RECEIVE" -> {
                var value = (ReceiveRequest) request;
                values.add(value.expectedVersion());
                values.add(value.documentReference());
                values.add(value.physicallyReceivedAt());
                values.add(value.lines().stream().map(line -> List.of(line.lineId(), line.quantity(),
                        line.expectedDestinationVersion(), line.documentLineReference(), line.observedSku(), line.observedLotCode())).toList());
                values.add(value.reason());
                values.add(value.evidenceReference());
            }
            case "OPEN_DISCREPANCY" -> {
                var value = (DiscrepancyRequest) request;
                values.add(value.expectedVersion());
                values.add(value.lineId());
                values.add(value.expectedState());
                values.add(value.observedState());
                values.add(value.reason());
                values.add(value.evidenceReference());
            }
            case "CONFIRM_LOSS" -> {
                var value = (DamageRequest) request;
                values.add(value.expectedVersion());
                values.add(value.documentReference());
                values.add(value.reasonCode());
                values.add(value.lines().stream().map(line -> List.of(line.lineId(), line.quantity(), line.documentLineReference())).toList());
                values.add(value.reason());
                values.add(value.evidenceReference());
            }
            case "REQUEST_RETURN" -> {
                var value = (RequestReturn) request;
                values.add(value.expectedVersion());
                values.add(value.requestReference());
                values.add(value.lines().stream().map(ReturnRequestLine::lineId).toList());
                values.add(value.reason());
                values.add(value.evidenceReference());
            }
            case "CONFIRM_RETURN" -> {
                var value = (ConfirmReturnRequest) request;
                values.add(value.expectedVersion());
                values.add(value.requestReference());
                values.add(value.documentReference());
                values.add(value.lines().stream().map(line -> List.of(line.lineId(), line.expectedSourceVersion(),
                        line.documentLineReference(), line.observedSku(), line.observedLotCode())).toList());
                values.add(value.reason());
                values.add(value.evidenceReference());
            }
            default -> throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        }
        return fingerprint(values.toArray());
    }

    private static byte[] fingerprint(Object... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("stockrescue-transfer-command-v1".getBytes(StandardCharsets.US_ASCII));
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(values.length).array());
            for (var value : values) updateCanonicalValue(digest, value);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void updateCanonicalValue(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update((byte) 0);
        } else if (value instanceof String text) {
            digest.update((byte) 1);
            updateBytes(digest, text.getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof UUID id) {
            digest.update((byte) 2);
            updateBytes(digest, id.toString().getBytes(StandardCharsets.US_ASCII));
        } else if (value instanceof Number number) {
            digest.update((byte) 3);
            updateBytes(digest, (number.getClass().getName() + ':' + number).getBytes(StandardCharsets.US_ASCII));
        } else if (value instanceof Boolean flag) {
            digest.update((byte) 4);
            digest.update(flag ? (byte) 1 : (byte) 0);
        } else if (value instanceof Instant instant) {
            digest.update((byte) 5);
            updateBytes(digest, instant.toString().getBytes(StandardCharsets.US_ASCII));
        } else if (value instanceof List<?> items) {
            digest.update((byte) 6);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(items.size()).array());
            for (var item : items) updateCanonicalValue(digest, item);
        } else {
            throw new IllegalArgumentException("Unsupported canonical transfer command value: " + value.getClass().getName());
        }
    }

    private static void updateBytes(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
