package com.stockrescue.transfers.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.stockrescue.inventory.domain.InventoryFailure;
import static com.stockrescue.transfers.application.TransferService.*;

/** Validates and canonicalizes transfer command input before transactional orchestration. */
final class TransferRequestNormalizer {
    private TransferRequestNormalizer() { }

    static NormalizedRequest normalize(CreateRequest request) {
        if (request == null || request.requestId() == null || request.sourceStoreId() == null
                || request.destinationStoreId() == null || request.sourceStoreId().equals(request.destinationStoreId())
                || request.lines() == null || request.lines().isEmpty() || request.lines().size() > MAX_LINES)
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        var reason = text(request.reason(), 500);
        var evidence = text(request.evidenceReference(), 256);
        var seen = new java.util.HashSet<UUID>();
        var lines = new ArrayList<NormalizedLine>(request.lines().size());
        for (var line : request.lines()) {
            if (line == null || line.sourcePositionId() == null || line.destinationLocationId() == null
                    || line.quantity() <= 0 || line.expectedSourceVersion() < 0
                    || line.expectedSourceVersion() == Long.MAX_VALUE || !seen.add(line.sourcePositionId()))
                throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
            lines.add(new NormalizedLine(line.sourcePositionId(), line.destinationLocationId(), line.quantity(),
                    line.expectedSourceVersion(), 0));
        }
        lines.sort(Comparator.comparing(NormalizedLine::sourcePositionId));
        var numbered = new ArrayList<NormalizedLine>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            numbered.add(new NormalizedLine(line.sourcePositionId(), line.destinationLocationId(), line.quantity(),
                    line.expectedSourceVersion(), index + 1));
        }
        return new NormalizedRequest(request.requestId(), request.sourceStoreId(), request.destinationStoreId(),
                List.copyOf(numbered), reason, evidence);
    }

    static DecisionRequest normalize(DecisionRequest request) {
        if (request == null || request.requestId() == null || request.expectedVersion() < 0
                || request.expectedVersion() == Long.MAX_VALUE)
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        return new DecisionRequest(request.requestId(), request.expectedVersion(), text(request.reason(), 500),
                text(request.evidenceReference(), 256));
    }

    static CancelRequest normalize(CancelRequest request) {
        if (request == null || request.requestId() == null || request.expectedVersion() < 0
                || request.expectedVersion() == Long.MAX_VALUE)
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        String putBackReference = request.putBackDocumentReference() == null
                || request.putBackDocumentReference().isBlank() ? null : text(request.putBackDocumentReference(), 200);
        return new CancelRequest(request.requestId(), request.expectedVersion(), text(request.reason(), 500),
                text(request.evidenceReference(), 256), Boolean.TRUE.equals(request.putBackConfirmed()), putBackReference);
    }

    static PickRequest normalize(PickRequest request) {
        if (request == null || request.requestId() == null || request.expectedVersion() < 0
                || request.expectedVersion() == Long.MAX_VALUE || request.lineId() == null || request.quantity() <= 0)
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        return new PickRequest(request.requestId(), request.expectedVersion(), request.lineId(),
                text(request.documentReference(), 200), request.quantity(), text(request.observedSku(), 64),
                text(request.observedLotCode(), 128), text(request.reason(), 500),
                text(request.evidenceReference(), 256));
    }

    static DispatchRequest normalize(DispatchRequest request) {
        if (request == null || request.requestId() == null || request.expectedVersion() < 0
                || request.expectedVersion() == Long.MAX_VALUE || request.lines() == null
                || request.lines().isEmpty() || request.lines().size() > MAX_LINES)
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        var seen = new java.util.HashSet<UUID>();
        var lines = new ArrayList<DispatchLine>(request.lines().size());
        for (var line : request.lines()) {
            if (line == null || line.lineId() == null || line.quantity() <= 0 || !seen.add(line.lineId()))
                throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
            lines.add(new DispatchLine(line.lineId(), line.quantity(), text(line.observedSku(), 64),
                    text(line.observedLotCode(), 128)));
        }
        lines.sort(Comparator.comparing(DispatchLine::lineId));
        return new DispatchRequest(request.requestId(), request.expectedVersion(),
                text(request.documentReference(), 200), List.copyOf(lines), text(request.reason(), 500),
                text(request.evidenceReference(), 256));
    }

    static ReceiveRequest normalize(ReceiveRequest request) {
        if (request == null || request.requestId() == null || request.expectedVersion() < 0
                || request.expectedVersion() == Long.MAX_VALUE || request.physicallyReceivedAt() == null
                || request.lines() == null || request.lines().isEmpty() || request.lines().size() > MAX_LINES)
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        var ids = new java.util.HashSet<UUID>();
        var lineReferences = new java.util.HashSet<String>();
        var lines = new ArrayList<ReceiveLine>(request.lines().size());
        for (var line : request.lines()) {
            if (line == null || line.lineId() == null || line.quantity() <= 0 || line.expectedDestinationVersion() < 0
                    || line.expectedDestinationVersion() == Long.MAX_VALUE || !ids.add(line.lineId()))
                throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
            String reference = text(line.documentLineReference(), 64);
            if (!lineReferences.add(reference)) throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
            lines.add(new ReceiveLine(line.lineId(), line.quantity(), line.expectedDestinationVersion(), reference,
                    text(line.observedSku(), 64), text(line.observedLotCode(), 128)));
        }
        lines.sort(Comparator.comparing(ReceiveLine::lineId));
        return new ReceiveRequest(request.requestId(), request.expectedVersion(), text(request.documentReference(), 200),
                request.physicallyReceivedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS), List.copyOf(lines),
                text(request.reason(), 500), text(request.evidenceReference(), 256));
    }

    static DamageRequest normalize(DamageRequest request) {
        if (request == null || request.requestId() == null || request.expectedVersion() < 0
                || request.expectedVersion() == Long.MAX_VALUE || request.lines() == null || request.lines().isEmpty()
                || request.lines().size() > MAX_LINES)
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        var reasonCode = text(request.reasonCode(), 48);
        if (!List.of("DAMAGED_IN_TRANSIT", "DESTROYED_IN_TRANSIT", "LOST_WITH_EVIDENCE").contains(reasonCode))
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        var ids = new java.util.HashSet<UUID>();
        var lineReferences = new java.util.HashSet<String>();
        var lines = new ArrayList<DamageLine>(request.lines().size());
        for (var line : request.lines()) {
            if (line == null || line.lineId() == null || line.quantity() <= 0 || !ids.add(line.lineId()))
                throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
            String reference = text(line.documentLineReference(), 64);
            if (!lineReferences.add(reference)) throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
            lines.add(new DamageLine(line.lineId(), line.quantity(), reference));
        }
        lines.sort(Comparator.comparing(DamageLine::lineId));
        return new DamageRequest(request.requestId(), request.expectedVersion(), text(request.documentReference(), 200),
                reasonCode, List.copyOf(lines), text(request.reason(), 500), text(request.evidenceReference(), 256));
    }

    static DiscrepancyRequest normalize(DiscrepancyRequest request) {
        if (request == null || request.requestId() == null || request.expectedVersion() < 0
                || request.expectedVersion() == Long.MAX_VALUE)
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        return new DiscrepancyRequest(request.requestId(), request.expectedVersion(), request.lineId(),
                text(request.expectedState(), 500), text(request.observedState(), 500), text(request.reason(), 500),
                text(request.evidenceReference(), 256));
    }

    static RequestReturn normalize(RequestReturn request) {
        if (request == null || request.requestId() == null || request.expectedVersion() < 0
                || request.expectedVersion() == Long.MAX_VALUE || request.lines() == null || request.lines().isEmpty()
                || request.lines().size() > MAX_LINES)
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        var ids = new java.util.HashSet<UUID>();
        var lines = new ArrayList<ReturnRequestLine>(request.lines().size());
        for (var line : request.lines()) {
            if (line == null || line.lineId() == null || !ids.add(line.lineId()))
                throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
            lines.add(line);
        }
        lines.sort(Comparator.comparing(ReturnRequestLine::lineId));
        return new RequestReturn(request.requestId(), request.expectedVersion(), text(request.requestReference(), 200),
                List.copyOf(lines), text(request.reason(), 500), text(request.evidenceReference(), 256));
    }

    static ConfirmReturnRequest normalize(ConfirmReturnRequest request) {
        if (request == null || request.requestId() == null || request.expectedVersion() < 0
                || request.expectedVersion() == Long.MAX_VALUE || request.lines() == null || request.lines().isEmpty()
                || request.lines().size() > MAX_LINES)
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        var ids = new java.util.HashSet<UUID>();
        var lineReferences = new java.util.HashSet<String>();
        var lines = new ArrayList<ConfirmReturnLine>(request.lines().size());
        for (var line : request.lines()) {
            if (line == null || line.lineId() == null || line.quantity() <= 0 || line.expectedSourceVersion() < 0
                    || line.expectedSourceVersion() == Long.MAX_VALUE || !ids.add(line.lineId()))
                throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
            String reference = text(line.documentLineReference(), 64);
            if (!lineReferences.add(reference)) throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
            lines.add(new ConfirmReturnLine(line.lineId(), line.quantity(), line.expectedSourceVersion(), reference,
                    text(line.observedSku(), 64), text(line.observedLotCode(), 128)));
        }
        lines.sort(Comparator.comparing(ConfirmReturnLine::lineId));
        return new ConfirmReturnRequest(request.requestId(), request.expectedVersion(), text(request.requestReference(), 200),
                text(request.documentReference(), 200),
                List.copyOf(lines), text(request.reason(), 500), text(request.evidenceReference(), 256));
    }

    private static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        return value.strip();
    }

    record NormalizedLine(UUID sourcePositionId, UUID destinationLocationId, long quantity,
            long expectedSourceVersion, int lineNumber) { }

    record NormalizedRequest(UUID requestId, UUID sourceStoreId, UUID destinationStoreId,
            List<NormalizedLine> lines, String reason, String evidenceReference) { }
}
