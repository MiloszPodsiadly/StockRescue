package com.stockrescue.transfers.persistence;

import java.math.BigInteger;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.transfers.domain.TransferMovementType;
import com.stockrescue.transfers.domain.TransferQuantities;
import com.stockrescue.transfers.domain.TransferStatus;
import com.stockrescue.transfers.domain.TransferWriteConflict;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transactional transfer persistence. Callers must authorize before acquiring transfer locks. */
@Repository
public class TransferRepository {
    public record Page<T>(List<T> items, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }

    public record TransferCommandReceipt(UUID id, UUID organizationId, UUID transferId, UUID actorId,
            UUID membershipId, String operation, UUID requestId, byte[] payloadDigest, String outcome,
            String correlationId, UUID stockStoreId, UUID stockCommandReceiptId, String stockOperation) {
        public TransferCommandReceipt {
            payloadDigest = payloadDigest.clone();
        }
        @Override public byte[] payloadDigest() { return payloadDigest.clone(); }
    }

    public record TransferSummary(UUID id, UUID organizationId, UUID sourceStoreId, UUID destinationStoreId,
            TransferStatus status, long version, Instant createdAt, Instant updatedAt, long lineCount,
            BigInteger outstandingQuantity) { }

    public record TransferLine(UUID id, int lineNumber, UUID sourcePositionId, UUID destinationLocationId,
            UUID productId, UUID lotId, String sku, String lotCode, long requestedQuantity,
            long expectedSourceVersion, UUID reservationAllocationId, long version, boolean picked,
            TransferQuantities quantities) { }

    public record TransferDetail(TransferSummary transfer, List<TransferLine> lines) {
        public TransferDetail { lines = List.copyOf(lines); }
    }

    public record CustodyMovement(UUID id, UUID lineId, long version, TransferMovementType type,
            UUID commandId, UUID actorId, long quantity, long outstandingBefore, long outstandingAfter,
            UUID stockStoreId, UUID stockMovementId, UUID stockPositionId, String reason,
            String evidenceReference, Instant recordedAt) { }

    public record InTransitLine(UUID transferId, UUID lineId, UUID sourceStoreId, UUID destinationStoreId,
            UUID sourcePositionId, UUID destinationLocationId, long outstanding, Instant dispatchedAt,
            long ageSeconds) { }

    public record Discrepancy(UUID id, UUID transferId, UUID lineId, String type, String reason,
            String evidenceReference, String expectedState, String observedState, Instant recordedAt) { }

    public record ReconciliationEvidence(UUID id, UUID organizationId, UUID transferId, UUID lineId,
            UUID commandId, UUID actorId, UUID membershipId, String type, String expectedState,
            String observedState, String reason, String evidenceReference) { }

    public record PendingReturn(UUID commandId, UUID lineId, long quantity, String documentReference) { }

    public record CustodyAppend(UUID id, UUID organizationId, UUID transferId, UUID lineId, long expectedVersion,
            TransferMovementType type, UUID commandId, UUID actorId, UUID membershipId, long quantity,
            UUID stockStoreId, UUID stockMovementId, UUID stockPositionId, String reason,
            String evidenceReference) { }

    public record LockedTransfer(UUID id, UUID sourceStoreId, UUID destinationStoreId, UUID createdByUserId,
            UUID approvedLaneId, UUID approvedLaneRevisionId, TransferStatus status, long version) { }

    public record NewTransferLine(UUID id, int lineNumber, UUID sourcePositionId, UUID destinationLocationId,
            long quantity, long expectedSourceVersion) { }

    public record TransferIdentity(UUID id, UUID sourceStoreId, UUID destinationStoreId, UUID createdByUserId,
            UUID approvedLaneId, UUID approvedLaneRevisionId, TransferStatus status, long version) { }

    public record PickEvidence(String documentReference, long quantity, String observedSku,
            String observedLotCode, long sourcePositionVersion) { }

    public record PhysicalDocument(UUID id, UUID organizationId, UUID transferId, UUID sourceStoreId,
            UUID destinationStoreId, UUID documentStoreId, UUID commandId, String storeSide,
            String type, String reference, String reason, String evidenceReference,
            UUID actorId, UUID membershipId) { }

    public record PhysicalDocumentLine(UUID organizationId, UUID transferId, UUID documentId,
            UUID lineId, String documentType, int lineNumber, String lineReference, long quantity,
            String disposition, String observedSku, String observedLotCode, Long sourcePositionVersion) { }

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public double oldestInTransitSeconds() {
        return jdbc.sql("""
                SELECT coalesce(greatest(extract(epoch FROM clock_timestamp()-min(m.recorded_at)),0),0)
                FROM stockrescue.transfer_custody_account a
                JOIN stockrescue.transfer_custody_movement m ON m.org_id=a.org_id AND m.transfer_id=a.transfer_id
                    AND m.line_id=a.line_id AND m.movement_type='DISPATCH' AND m.version=1
                WHERE a.outstanding>0
                """).query(Double.class).single();
    }

    public long custodyLedgerMismatchCount() {
        return jdbc.sql("""
                WITH account_mismatch AS (
                    SELECT a.org_id,a.transfer_id,a.line_id FROM stockrescue.transfer_custody_account a
                    LEFT JOIN LATERAL (
                        SELECT coalesce(sum(quantity) FILTER (WHERE movement_type='DISPATCH'),0) dispatched,
                            coalesce(sum(quantity) FILTER (WHERE movement_type='RECEIPT'),0) received,
                            coalesce(sum(quantity) FILTER (WHERE movement_type='LOSS'),0) lost,
                            coalesce(sum(quantity) FILTER (WHERE movement_type='RETURN'),0) returned,
                            coalesce(max(version),0) version,
                            coalesce((array_agg(outstanding_after ORDER BY version DESC))[1],0) outstanding
                        FROM stockrescue.transfer_custody_movement m
                        WHERE m.org_id=a.org_id AND m.transfer_id=a.transfer_id AND m.line_id=a.line_id
                    ) journal ON true
                    WHERE a.dispatched<>journal.dispatched OR a.physically_received<>journal.received
                        OR a.confirmed_loss<>journal.lost OR a.physically_returned<>journal.returned
                        OR a.version<>journal.version OR a.outstanding<>journal.outstanding
                ), ledger_mismatch AS (
                    SELECT c.org_id,c.transfer_id,c.line_id
                    FROM stockrescue.transfer_custody_movement c
                    JOIN stockrescue.transfer_line l ON l.org_id=c.org_id AND l.transfer_id=c.transfer_id AND l.id=c.line_id
                    LEFT JOIN stockrescue.stock_position source_position
                      ON source_position.org_id=l.org_id AND source_position.store_id=l.source_store_id
                     AND source_position.id=l.source_position_id
                    LEFT JOIN stockrescue.transfer_command_receipt tc
                      ON tc.org_id=c.org_id AND tc.transfer_id=c.transfer_id AND tc.id=c.command_id
                    LEFT JOIN stockrescue.stock_movement m
                      ON m.org_id=c.org_id AND m.store_id=c.stock_store_id AND m.id=c.stock_movement_id
                    LEFT JOIN stockrescue.stock_position p
                      ON p.org_id=c.org_id AND p.store_id=c.stock_store_id AND p.id=c.stock_position_id
                    LEFT JOIN stockrescue.stock_movement_line ml
                      ON ml.org_id=c.org_id AND ml.store_id=c.stock_store_id AND ml.movement_id=c.stock_movement_id
                     AND ml.position_id=c.stock_position_id
                    WHERE c.movement_type<>'LOSS' AND (
                        tc.id IS NULL OR tc.stock_command_receipt_id IS NULL OR m.id IS NULL OR ml.movement_id IS NULL
                        OR m.command_id IS DISTINCT FROM tc.stock_command_receipt_id
                        OR m.operation IS DISTINCT FROM CASE c.movement_type
                            WHEN 'DISPATCH' THEN 'TRANSFER_OUT' WHEN 'RECEIPT' THEN 'TRANSFER_IN' ELSE 'TRANSFER_RETURN' END
                        OR c.stock_store_id IS DISTINCT FROM CASE WHEN c.movement_type='RECEIPT'
                            THEN l.destination_store_id ELSE l.source_store_id END
                        OR source_position.id IS NULL OR p.id IS NULL
                        OR (c.movement_type IN ('DISPATCH','RETURN')
                            AND c.stock_position_id IS DISTINCT FROM l.source_position_id)
                        OR (c.movement_type='RECEIPT' AND (
                            p.location_id IS DISTINCT FROM l.destination_location_id
                            OR p.product_id IS DISTINCT FROM source_position.product_id
                            OR p.lot_id IS DISTINCT FROM source_position.lot_id))
                        OR ml.delta_on_hand IS DISTINCT FROM CASE WHEN c.movement_type='DISPATCH'
                            THEN -c.quantity ELSE c.quantity END
                        OR ml.delta_reserved IS DISTINCT FROM CASE WHEN c.movement_type='DISPATCH'
                            THEN -c.quantity ELSE 0 END
                    )
                )
                SELECT count(*) FROM (SELECT * FROM account_mismatch UNION SELECT * FROM ledger_mismatch) mismatch
                """).query(Long.class).single();
    }

    public boolean recordCommand(TransferCommandReceipt command) {
        requireWriteTransaction();
        return jdbc.sql("""
                INSERT INTO stockrescue.transfer_command_receipt
                    (id,org_id,transfer_id,actor_user_id,membership_id,operation,request_id,payload_digest,outcome,
                     correlation_id,stock_store_id,stock_command_receipt_id,stock_operation)
                VALUES (:id,:org,:transfer,:actor,:membership,:operation,:request,:digest,CAST(:outcome AS jsonb),
                        :correlation,:stockStore,:stockCommand,:stockOperation)
                ON CONFLICT (org_id,actor_user_id,operation,request_id) DO NOTHING
                """)
                .param("id", command.id()).param("org", command.organizationId()).param("transfer", command.transferId())
                .param("actor", command.actorId()).param("membership", command.membershipId())
                .param("operation", command.operation()).param("request", command.requestId())
                .param("digest", command.payloadDigest()).param("outcome", command.outcome())
                .param("correlation", command.correlationId(), Types.VARCHAR)
                .param("stockStore", command.stockStoreId(), Types.OTHER)
                .param("stockCommand", command.stockCommandReceiptId(), Types.OTHER)
                .param("stockOperation", command.stockOperation(), Types.VARCHAR).update() == 1;
    }

    public void lockCommand(UUID organizationId, UUID actorId, String operation, UUID requestId) {
        requireWriteTransaction();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "transfer-command:" + organizationId + ":" + actorId + ":" + operation + ":" + requestId)
                .query((row, index) -> true).single();
    }

    public Optional<TransferCommandReceipt> command(UUID organizationId, UUID actorId, String operation, UUID requestId) {
        return jdbc.sql("""
                SELECT id,org_id,transfer_id,actor_user_id,membership_id,operation,request_id,payload_digest,outcome::text,correlation_id,
                    stock_store_id,stock_command_receipt_id,stock_operation
                FROM stockrescue.transfer_command_receipt
                WHERE org_id=:org AND actor_user_id=:actor AND operation=:operation AND request_id=:request
                """)
                .param("org", organizationId).param("actor", actorId).param("operation", operation).param("request", requestId)
                .query((row, index) -> new TransferCommandReceipt(row.getObject("id", UUID.class),
                        row.getObject("org_id", UUID.class), row.getObject("transfer_id", UUID.class),
                        row.getObject("actor_user_id", UUID.class), row.getObject("membership_id", UUID.class),
                        row.getString("operation"), row.getObject("request_id", UUID.class), row.getBytes("payload_digest"),
                        row.getString("outcome"), row.getString("correlation_id"), row.getObject("stock_store_id", UUID.class),
                        row.getObject("stock_command_receipt_id", UUID.class), row.getString("stock_operation")))
                .optional();
    }

    public Optional<TransferIdentity> identity(UUID organizationId, UUID transferId) {
        return jdbc.sql("""
                SELECT id,source_store_id,destination_store_id,created_by_user_id,approved_lane_id,
                    approved_lane_revision_id,status,version
                FROM stockrescue.transfer_order WHERE org_id=:org AND id=:transfer
                """)
                .param("org", organizationId).param("transfer", transferId)
                .query((row, index) -> new TransferIdentity(row.getObject("id", UUID.class),
                        row.getObject("source_store_id", UUID.class), row.getObject("destination_store_id", UUID.class),
                        row.getObject("created_by_user_id", UUID.class), row.getObject("approved_lane_id", UUID.class),
                        row.getObject("approved_lane_revision_id", UUID.class),
                        TransferStatus.valueOf(row.getString("status")), row.getLong("version")))
                .optional();
    }

    public TransferDetail create(UUID organizationId, UUID transferId, UUID sourceStoreId, UUID destinationStoreId,
            UUID actorId, UUID membershipId, List<NewTransferLine> lines) {
        requireWriteTransaction();
        jdbc.sql("""
                INSERT INTO stockrescue.transfer_order(id,org_id,source_store_id,destination_store_id,created_by_user_id,created_by_membership_id)
                VALUES (:id,:org,:source,:destination,:actor,:membership)
                """)
                .param("id", transferId).param("org", organizationId).param("source", sourceStoreId)
                .param("destination", destinationStoreId).param("actor", actorId).param("membership", membershipId).update();
        for (var line : lines) {
            jdbc.sql("""
                    INSERT INTO stockrescue.transfer_line(id,org_id,transfer_id,source_store_id,destination_store_id,line_number,
                        source_position_id,destination_location_id,requested_quantity,expected_source_version)
                    VALUES (:id,:org,:transfer,:source,:destination,:lineNumber,:position,:location,:quantity,:expectedVersion)
                    """)
                    .param("id", line.id()).param("org", organizationId).param("transfer", transferId)
                    .param("source", sourceStoreId).param("destination", destinationStoreId)
                    .param("lineNumber", line.lineNumber()).param("position", line.sourcePositionId())
                    .param("location", line.destinationLocationId()).param("quantity", line.quantity())
                    .param("expectedVersion", line.expectedSourceVersion()).update();
            jdbc.sql("""
                    INSERT INTO stockrescue.transfer_custody_account(org_id,transfer_id,line_id)
                    VALUES (:org,:transfer,:line)
                    """).param("org", organizationId).param("transfer", transferId).param("line", line.id()).update();
        }
        return detail(organizationId, transferId).orElseThrow(OrganizationUnavailableException::new);
    }

    public void recordInitialTransition(UUID organizationId, UUID transferId, UUID commandId,
            UUID actorId, UUID membershipId, String reason, String evidenceReference) {
        requireWriteTransaction();
        jdbc.sql("""
                INSERT INTO stockrescue.transfer_transition
                    (org_id,transfer_id,version,status_before,status_after,command_id,actor_user_id,membership_id,reason,evidence_reference)
                VALUES (:org,:transfer,0,NULL,'REQUESTED',:command,:actor,:membership,:reason,:evidence)
                """)
                .param("org", organizationId).param("transfer", transferId).param("command", commandId)
                .param("actor", actorId).param("membership", membershipId)
                .param("reason", text(reason, 500)).param("evidence", text(evidenceReference, 256)).update();
    }

    public boolean lockDestinationContext(UUID organizationId, UUID destinationStoreId, UUID locationId) {
        requireWriteTransaction();
        return jdbc.sql("""
                SELECT s.active AND l.active AND z.active
                FROM stockrescue.store s
                JOIN stockrescue.storage_location l ON l.org_id=s.org_id AND l.store_id=s.id
                JOIN stockrescue.storage_zone z ON z.org_id=l.org_id AND z.store_id=l.store_id AND z.id=l.zone_id
                WHERE s.org_id=:org AND s.id=:store AND l.id=:location
                FOR SHARE OF s,l,z
                """)
                .param("org", organizationId).param("store", destinationStoreId).param("location", locationId)
                .query(Boolean.class).optional().orElse(false);
    }

    public void recordPhysicalDocument(PhysicalDocument document) {
        requireWriteTransaction();
        jdbc.sql("""
                INSERT INTO stockrescue.transfer_physical_document
                    (id,org_id,transfer_id,source_store_id,destination_store_id,document_store_id,command_id,
                     store_side,document_type,document_reference,reason,evidence_reference,recorded_by_user_id,membership_id)
                VALUES (:id,:org,:transfer,:source,:destination,:store,:command,:side,:type,:reference,
                        :reason,:evidence,:actor,:membership)
                """)
                .param("id", document.id()).param("org", document.organizationId()).param("transfer", document.transferId())
                .param("source", document.sourceStoreId()).param("destination", document.destinationStoreId())
                .param("store", document.documentStoreId()).param("command", document.commandId())
                .param("side", document.storeSide()).param("type", document.type()).param("reference", text(document.reference(), 200))
                .param("reason", text(document.reason(), 500)).param("evidence", text(document.evidenceReference(), 256))
                .param("actor", document.actorId()).param("membership", document.membershipId()).update();
    }

    public void lockDocumentIdentity(UUID organizationId, UUID documentStoreId, String type, String reference) {
        requireWriteTransaction();
        String key="transfer-document:"+organizationId+":"+documentStoreId+":"+type.length()+":"+type+":"+reference.length()+":"+reference;
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", key).query((row, index) -> true).single();
    }

    public boolean physicalDocumentExists(UUID organizationId, UUID documentStoreId, String type, String reference) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM stockrescue.transfer_physical_document
                    WHERE org_id=:org AND document_store_id=:store AND document_type=:type AND document_reference=:reference)
                """)
                .param("org", organizationId).param("store", documentStoreId).param("type", type)
                .param("reference", reference).query(Boolean.class).single();
    }

    public void recordReconciliationEvidence(ReconciliationEvidence evidence) {
        requireWriteTransaction();
        jdbc.sql("""
                INSERT INTO stockrescue.transfer_reconciliation_evidence
                    (id,org_id,transfer_id,line_id,command_id,actor_user_id,membership_id,evidence_type,
                     expected_state,observed_state,reason,evidence_reference)
                VALUES (:id,:org,:transfer,:line,:command,:actor,:membership,:type,
                        CAST(:expected AS jsonb),CAST(:observed AS jsonb),:reason,:reference)
                """)
                .param("id", evidence.id()).param("org", evidence.organizationId()).param("transfer", evidence.transferId())
                .param("line", evidence.lineId(), Types.OTHER).param("command", evidence.commandId())
                .param("actor", evidence.actorId()).param("membership", evidence.membershipId())
                .param("type", evidence.type()).param("expected", evidence.expectedState())
                .param("observed", evidence.observedState()).param("reason", text(evidence.reason(), 500))
                .param("reference", text(evidence.evidenceReference(), 256)).update();
    }

    public Optional<PendingReturn> pendingReturn(UUID organizationId, UUID transferId, UUID lineId) {
        return jdbc.sql("""
                SELECT e.command_id,e.line_id,(e.expected_state->>'outstanding')::bigint AS quantity,
                    e.observed_state->>'returnReference' AS document_reference
                FROM stockrescue.transfer_reconciliation_evidence e
                WHERE e.org_id=:org AND e.transfer_id=:transfer AND e.line_id=:line
                    AND e.evidence_type='RETURN_REQUEST'
                    AND NOT EXISTS (
                        SELECT 1 FROM stockrescue.transfer_physical_document d
                        JOIN stockrescue.transfer_physical_document_line l
                          ON l.org_id=d.org_id AND l.transfer_id=d.transfer_id AND l.document_id=d.id
                        WHERE d.org_id=e.org_id AND d.transfer_id=e.transfer_id AND d.document_type='RETURN'
                            AND d.document_reference=e.observed_state->>'returnReference' AND l.line_id=e.line_id)
                    AND e.expected_state->'outstanding' = (
                        SELECT to_jsonb(a.outstanding)
                        FROM stockrescue.transfer_custody_account a
                        WHERE a.org_id=e.org_id AND a.transfer_id=e.transfer_id AND a.line_id=e.line_id)
                ORDER BY e.recorded_at DESC,e.id DESC LIMIT 1
                """)
                .param("org", organizationId).param("transfer", transferId).param("line", lineId)
                .query((row, index) -> new PendingReturn(row.getObject("command_id", UUID.class),
                        row.getObject("line_id", UUID.class), row.getLong("quantity"), row.getString("document_reference")))
                .optional();
    }

    public void recordPhysicalDocumentLine(PhysicalDocumentLine line) {
        requireWriteTransaction();
        jdbc.sql("""
                INSERT INTO stockrescue.transfer_physical_document_line
                    (org_id,transfer_id,document_id,line_id,document_type,line_number,document_line_reference,
                     quantity,disposition,observed_sku,observed_lot_code,source_position_version)
                VALUES (:org,:transfer,:document,:line,:type,:number,:reference,:quantity,:disposition,
                        :sku,:lot,:sourceVersion)
                """)
                .param("org", line.organizationId()).param("transfer", line.transferId()).param("document", line.documentId())
                .param("line", line.lineId()).param("type", line.documentType()).param("number", line.lineNumber())
                .param("reference", text(line.lineReference(), 64)).param("quantity", line.quantity())
                .param("disposition", line.disposition()).param("sku", line.observedSku())
                .param("lot", line.observedLotCode()).param("sourceVersion", line.sourcePositionVersion(), Types.BIGINT).update();
    }

    public Optional<PickEvidence> pickEvidence(UUID organizationId, UUID transferId, UUID lineId) {
        return jdbc.sql("""
                SELECT d.document_reference,l.quantity,l.observed_sku,l.observed_lot_code,l.source_position_version
                FROM stockrescue.transfer_physical_document d
                JOIN stockrescue.transfer_physical_document_line l
                  ON l.org_id=d.org_id AND l.transfer_id=d.transfer_id AND l.document_id=d.id
                    AND l.document_type=d.document_type
                WHERE d.org_id=:org AND d.transfer_id=:transfer AND l.line_id=:line AND d.document_type='PICK'
                """)
                .param("org", organizationId).param("transfer", transferId).param("line", lineId)
                .query((row, index) -> new PickEvidence(row.getString("document_reference"), row.getLong("quantity"),
                        row.getString("observed_sku"), row.getString("observed_lot_code"),
                        row.getLong("source_position_version"))).optional();
    }

    public long pickedLineCount(UUID organizationId, UUID transferId) {
        return jdbc.sql("""
                SELECT count(*) FROM stockrescue.transfer_physical_document_line
                WHERE org_id=:org AND transfer_id=:transfer AND document_type='PICK'
                """)
                .param("org", organizationId).param("transfer", transferId).query(Long.class).single();
    }

    public void linkReservations(UUID organizationId, UUID transferId, List<TransferLine> lines,
            java.util.Map<UUID, UUID> allocationByLine) {
        requireWriteTransaction();
        for (var line : lines) {
            var allocation = allocationByLine.get(line.id());
            if (allocation == null || jdbc.sql("""
                    UPDATE stockrescue.transfer_line
                    SET reservation_allocation_id=:allocation,reservation_bucket='RESERVED'
                    WHERE org_id=:org AND transfer_id=:transfer AND id=:line AND reservation_allocation_id IS NULL
                    """)
                    .param("allocation", allocation).param("org", organizationId).param("transfer", transferId)
                    .param("line", line.id()).update() != 1) throw new TransferWriteConflict();
        }
    }

    public void lockLineCommands(UUID organizationId, UUID transferId) {
        requireWriteTransaction();
        jdbc.sql("""
                SELECT id FROM stockrescue.transfer_line WHERE org_id=:org AND transfer_id=:transfer
                ORDER BY source_position_id FOR UPDATE
                """).param("org", organizationId).param("transfer", transferId)
                .query((row, index) -> row.getObject("id", UUID.class)).list();
    }

    public LockedTransfer lockTransfer(UUID organizationId, UUID transferId) {
        requireWriteTransaction();
        return jdbc.sql("""
                SELECT id,source_store_id,destination_store_id,created_by_user_id,approved_lane_id,
                    approved_lane_revision_id,status,version
                FROM stockrescue.transfer_order
                WHERE org_id=:org AND id=:transfer FOR UPDATE
                """)
                .param("org", organizationId).param("transfer", transferId)
                .query((row, index) -> new LockedTransfer(row.getObject("id", UUID.class),
                        row.getObject("source_store_id", UUID.class), row.getObject("destination_store_id", UUID.class),
                        row.getObject("created_by_user_id", UUID.class), row.getObject("approved_lane_id", UUID.class),
                        row.getObject("approved_lane_revision_id", UUID.class),
                        TransferStatus.valueOf(row.getString("status")), row.getLong("version")))
                .optional().orElseThrow(OrganizationUnavailableException::new);
    }

    public LockedTransfer lockTransferForCommand(UUID organizationId, UUID transferId) {
        var transfer = lockTransfer(organizationId, transferId);
        jdbc.sql("""
                SELECT l.id
                FROM stockrescue.transfer_line l
                WHERE l.org_id=:org AND l.transfer_id=:transfer
                ORDER BY l.id
                FOR UPDATE OF l
                """)
                .param("org", organizationId).param("transfer", transferId)
                .query((row, index) -> row.getObject("id", UUID.class)).list();
        jdbc.sql("""
                SELECT line_id
                FROM stockrescue.transfer_custody_account
                WHERE org_id=:org AND transfer_id=:transfer
                ORDER BY line_id FOR UPDATE
                """)
                .param("org", organizationId).param("transfer", transferId)
                .query((row, index) -> row.getObject("line_id", UUID.class)).list();
        return transfer;
    }

    long advanceStatus(UUID organizationId, UUID transferId, long expectedVersion,
            TransferStatus nextStatus, UUID commandId, UUID actorId, UUID membershipId,
            String reason, String evidenceReference) {
        return advanceStatus(organizationId, transferId, expectedVersion, nextStatus, commandId, actorId,
                membershipId, reason, evidenceReference, null, null, false);
    }

    public long advanceStatus(UUID organizationId, UUID transferId, long expectedVersion,
            TransferStatus nextStatus, UUID commandId, UUID actorId, UUID membershipId,
            String reason, String evidenceReference, UUID approvedLaneId, UUID approvedLaneRevisionId) {
        return advanceStatus(organizationId, transferId, expectedVersion, nextStatus, commandId, actorId,
                membershipId, reason, evidenceReference, approvedLaneId, approvedLaneRevisionId, true);
    }

    private long advanceStatus(UUID organizationId, UUID transferId, long expectedVersion,
            TransferStatus nextStatus, UUID commandId, UUID actorId, UUID membershipId,
            String reason, String evidenceReference, UUID approvedLaneId, UUID approvedLaneRevisionId,
            boolean requireApprovedRoute) {
        requireWriteTransaction();
        var current = lockTransferForCommand(organizationId, transferId);
        if (current.version() != expectedVersion) throw new TransferWriteConflict();
        if (!current.status().canTransitionTo(nextStatus)) throw new TransferWriteConflict();
        if ((approvedLaneId == null) != (approvedLaneRevisionId == null)
                || requireApprovedRoute && nextStatus == TransferStatus.APPROVED && approvedLaneId == null
                || nextStatus != TransferStatus.APPROVED && approvedLaneId != null) throw new TransferWriteConflict();
        long nextVersion;
        try {
            nextVersion = Math.incrementExact(current.version());
        } catch (ArithmeticException overflow) {
            throw new TransferWriteConflict();
        }
        int changed = jdbc.sql("""
                UPDATE stockrescue.transfer_order
                SET status=:status,version=:next,approved_lane_id=coalesce(:lane,approved_lane_id),
                    approved_lane_revision_id=coalesce(:revision,approved_lane_revision_id),updated_at=clock_timestamp()
                WHERE org_id=:org AND id=:transfer AND version=:expected
                """)
                .param("status", nextStatus.name()).param("next", nextVersion)
                .param("lane", approvedLaneId, Types.OTHER).param("revision", approvedLaneRevisionId, Types.OTHER)
                .param("org", organizationId).param("transfer", transferId).param("expected", expectedVersion).update();
        if (changed != 1) throw new TransferWriteConflict();
        jdbc.sql("""
                INSERT INTO stockrescue.transfer_transition
                    (org_id,transfer_id,version,status_before,status_after,command_id,actor_user_id,membership_id,reason,evidence_reference)
                VALUES (:org,:transfer,:version,:before,:after,:command,:actor,:membership,:reason,:evidence)
                """)
                .param("org", organizationId).param("transfer", transferId).param("version", nextVersion)
                .param("before", current.status().name()).param("after", nextStatus.name())
                .param("command", commandId).param("actor", actorId).param("membership", membershipId)
                .param("reason", text(reason, 500)).param("evidence", text(evidenceReference, 256)).update();
        return nextVersion;
    }

    public TransferQuantities appendCustodyMovement(CustodyAppend movement) {
        requireWriteTransaction();
        lockTransferForCommand(movement.organizationId(), movement.transferId());
        var current = jdbc.sql("""
                SELECT l.requested_quantity,a.dispatched,a.physically_received,a.confirmed_loss,
                    a.physically_returned,a.outstanding,a.version
                FROM stockrescue.transfer_line l
                JOIN stockrescue.transfer_custody_account a
                  ON a.org_id=l.org_id AND a.transfer_id=l.transfer_id AND a.line_id=l.id
                WHERE l.org_id=:org AND l.transfer_id=:transfer AND l.id=:line
                FOR UPDATE OF a
                """)
                .param("org", movement.organizationId()).param("transfer", movement.transferId()).param("line", movement.lineId())
                .query((row, index) -> new AccountState(row.getLong("requested_quantity"),
                        new TransferQuantities(row.getLong("dispatched"), row.getLong("physically_received"),
                                row.getLong("confirmed_loss"), row.getLong("physically_returned"), row.getLong("outstanding")),
                        row.getLong("version")))
                .optional().orElseThrow(OrganizationUnavailableException::new);
        if (current.version() != movement.expectedVersion()) throw new TransferWriteConflict();
        TransferQuantities after;
        try {
            after = switch (movement.type()) {
                case DISPATCH -> {
                    if (movement.quantity() != current.requestedQuantity()) throw new TransferWriteConflict();
                    yield current.quantities().dispatch(movement.quantity());
                }
                case RECEIPT -> current.quantities().receive(movement.quantity());
                case LOSS -> current.quantities().confirmLoss(movement.quantity());
                case RETURN -> current.quantities().confirmReturn(movement.quantity());
            };
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new TransferWriteConflict();
        }
        long nextVersion;
        try {
            nextVersion = Math.incrementExact(current.version());
        } catch (ArithmeticException overflow) {
            throw new TransferWriteConflict();
        }
        int changed = jdbc.sql("""
                UPDATE stockrescue.transfer_custody_account
                SET dispatched=:dispatched,physically_received=:received,confirmed_loss=:loss,
                    physically_returned=:returned,outstanding=:outstanding,version=:next,updated_at=clock_timestamp()
                WHERE org_id=:org AND transfer_id=:transfer AND line_id=:line AND version=:expected
                """)
                .param("dispatched", after.dispatched()).param("received", after.physicallyReceived())
                .param("loss", after.confirmedLoss()).param("returned", after.physicallyReturned())
                .param("outstanding", after.outstanding()).param("next", nextVersion)
                .param("org", movement.organizationId()).param("transfer", movement.transferId())
                .param("line", movement.lineId()).param("expected", movement.expectedVersion()).update();
        if (changed != 1) throw new TransferWriteConflict();
        jdbc.sql("""
                INSERT INTO stockrescue.transfer_custody_movement
                    (id,org_id,transfer_id,line_id,version,movement_type,command_id,actor_user_id,membership_id,
                     quantity,outstanding_before,outstanding_after,stock_store_id,stock_movement_id,stock_position_id,reason,evidence_reference)
                VALUES (:id,:org,:transfer,:line,:version,:type,:command,:actor,:membership,:quantity,:before,:after,
                        :stockStore,:stockMovement,:stockPosition,:reason,:evidence)
                """)
                .param("id", movement.id()).param("org", movement.organizationId()).param("transfer", movement.transferId())
                .param("line", movement.lineId()).param("version", nextVersion).param("type", movement.type().name())
                .param("command", movement.commandId()).param("actor", movement.actorId()).param("membership", movement.membershipId())
                .param("quantity", movement.quantity()).param("before", current.quantities().outstanding())
                .param("after", after.outstanding()).param("stockStore", movement.stockStoreId(), Types.OTHER)
                .param("stockMovement", movement.stockMovementId(), Types.OTHER)
                .param("stockPosition", movement.stockPositionId(), Types.OTHER)
                .param("reason", text(movement.reason(), 500)).param("evidence", text(movement.evidenceReference(), 256)).update();
        return after;
    }

    public Optional<TransferDetail> detail(UUID organizationId, UUID transferId) {
        var header = jdbc.sql("""
                SELECT t.id,t.org_id,t.source_store_id,t.destination_store_id,t.status,t.version,t.created_at,t.updated_at,
                    count(l.id) AS line_count,coalesce(sum(a.outstanding),0) AS outstanding
                FROM stockrescue.transfer_order t
                LEFT JOIN stockrescue.transfer_line l ON l.org_id=t.org_id AND l.transfer_id=t.id
                LEFT JOIN stockrescue.transfer_custody_account a
                  ON a.org_id=l.org_id AND a.transfer_id=l.transfer_id AND a.line_id=l.id
                WHERE t.org_id=:org AND t.id=:transfer
                GROUP BY t.id
                """)
                .param("org", organizationId).param("transfer", transferId)
                .query((row, index) -> summary(row)).optional();
        if (header.isEmpty()) return Optional.empty();
        var lines = jdbc.sql("""
                SELECT l.id,l.line_number,l.source_position_id,l.destination_location_id,
                    source_position.product_id,source_position.lot_id,
                    p.sku,lot.lot_code,l.requested_quantity,l.expected_source_version,l.reservation_allocation_id,a.dispatched,
                    a.physically_received,a.confirmed_loss,a.physically_returned,a.outstanding,a.version,
                    EXISTS (SELECT 1 FROM stockrescue.transfer_physical_document_line picked
                        WHERE picked.org_id=l.org_id AND picked.transfer_id=l.transfer_id
                            AND picked.line_id=l.id AND picked.document_type='PICK') AS picked
                FROM stockrescue.transfer_line l
                JOIN stockrescue.stock_position source_position
                  ON source_position.org_id=l.org_id AND source_position.store_id=l.source_store_id
                    AND source_position.id=l.source_position_id
                JOIN stockrescue.product p ON p.org_id=source_position.org_id AND p.id=source_position.product_id
                JOIN stockrescue.manufacturer_lot lot
                  ON lot.org_id=source_position.org_id AND lot.product_id=source_position.product_id AND lot.id=source_position.lot_id
                JOIN stockrescue.transfer_custody_account a
                  ON a.org_id=l.org_id AND a.transfer_id=l.transfer_id AND a.line_id=l.id
                WHERE l.org_id=:org AND l.transfer_id=:transfer
                ORDER BY l.line_number,l.id
                """)
                .param("org", organizationId).param("transfer", transferId)
                .query((row, index) -> new TransferLine(row.getObject("id", UUID.class), row.getInt("line_number"),
                        row.getObject("source_position_id", UUID.class), row.getObject("destination_location_id", UUID.class),
                        row.getObject("product_id", UUID.class), row.getObject("lot_id", UUID.class), row.getString("sku"),
                        row.getString("lot_code"), row.getLong("requested_quantity"), row.getLong("expected_source_version"),
                        row.getObject("reservation_allocation_id", UUID.class), row.getLong("version"),
                        row.getBoolean("picked"),
                        new TransferQuantities(row.getLong("dispatched"), row.getLong("physically_received"),
                                row.getLong("confirmed_loss"), row.getLong("physically_returned"), row.getLong("outstanding"))))
                .list();
        return Optional.of(new TransferDetail(header.orElseThrow(), lines));
    }

    public Page<TransferSummary> page(UUID organizationId, UUID storeId, TransferStatus status, int offset, int limit) {
        validatePage(offset, limit);
        var rows = jdbc.sql("""
                SELECT t.id,t.org_id,t.source_store_id,t.destination_store_id,t.status,t.version,t.created_at,t.updated_at,
                    count(l.id) AS line_count,coalesce(sum(a.outstanding),0) AS outstanding
                FROM stockrescue.transfer_order t
                LEFT JOIN stockrescue.transfer_line l ON l.org_id=t.org_id AND l.transfer_id=t.id
                LEFT JOIN stockrescue.transfer_custody_account a
                  ON a.org_id=l.org_id AND a.transfer_id=l.transfer_id AND a.line_id=l.id
                WHERE t.org_id=:org AND (CAST(:store AS uuid) IS NULL OR t.source_store_id=:store OR t.destination_store_id=:store)
                    AND (CAST(:status AS varchar) IS NULL OR t.status=:status)
                GROUP BY t.id
                ORDER BY t.updated_at DESC,t.id DESC LIMIT :limit OFFSET :offset
                """)
                .param("org", organizationId).param("store", storeId, Types.OTHER)
                .param("status", status == null ? null : status.name(), Types.VARCHAR)
                .param("limit", limit + 1).param("offset", offset)
                .query((row, index) -> summary(row)).list();
        return page(rows, limit);
    }

    public Page<CustodyMovement> custodyTimeline(UUID organizationId, UUID transferId, int offset, int limit) {
        validatePage(offset, limit);
        var rows = jdbc.sql("""
                SELECT id,line_id,version,movement_type,command_id,actor_user_id,quantity,outstanding_before,outstanding_after,
                    stock_store_id,stock_movement_id,stock_position_id,reason,evidence_reference,recorded_at
                FROM stockrescue.transfer_custody_movement
                WHERE org_id=:org AND transfer_id=:transfer
                ORDER BY recorded_at DESC,id DESC LIMIT :limit OFFSET :offset
                """)
                .param("org", organizationId).param("transfer", transferId)
                .param("limit", limit + 1).param("offset", offset)
                .query((row, index) -> new CustodyMovement(row.getObject("id", UUID.class),
                        row.getObject("line_id", UUID.class), row.getLong("version"),
                        TransferMovementType.valueOf(row.getString("movement_type")), row.getObject("command_id", UUID.class),
                        row.getObject("actor_user_id", UUID.class),
                        row.getLong("quantity"), row.getLong("outstanding_before"), row.getLong("outstanding_after"),
                        row.getObject("stock_store_id", UUID.class), row.getObject("stock_movement_id", UUID.class),
                        row.getObject("stock_position_id", UUID.class), row.getString("reason"),
                        row.getString("evidence_reference"), row.getTimestamp("recorded_at").toInstant()))
                .list();
        return page(rows, limit);
    }

    public Page<InTransitLine> inTransitAging(UUID organizationId, UUID storeId, int offset, int limit) {
        validatePage(offset, limit);
        var rows = jdbc.sql("""
                SELECT l.transfer_id,l.id AS line_id,l.source_store_id,l.destination_store_id,l.source_position_id,
                    l.destination_location_id,a.outstanding,min(m.recorded_at) FILTER (WHERE m.movement_type='DISPATCH') AS dispatched_at,
                    greatest(floor(extract(epoch FROM clock_timestamp()-min(m.recorded_at)
                        FILTER (WHERE m.movement_type='DISPATCH'))),0)::bigint AS age_seconds
                FROM stockrescue.transfer_line l
                JOIN stockrescue.transfer_custody_account a
                  ON a.org_id=l.org_id AND a.transfer_id=l.transfer_id AND a.line_id=l.id
                JOIN stockrescue.transfer_custody_movement m
                  ON m.org_id=l.org_id AND m.transfer_id=l.transfer_id AND m.line_id=l.id
                WHERE l.org_id=:org AND a.outstanding>0
                    AND (CAST(:store AS uuid) IS NULL OR l.source_store_id=:store OR l.destination_store_id=:store)
                GROUP BY l.org_id,l.transfer_id,l.id,a.outstanding
                ORDER BY dispatched_at,l.transfer_id,l.id
                LIMIT :limit OFFSET :offset
                """)
                .param("org", organizationId).param("store", storeId, Types.OTHER)
                .param("limit", limit + 1).param("offset", offset)
                .query((row, index) -> new InTransitLine(row.getObject("transfer_id", UUID.class),
                        row.getObject("line_id", UUID.class), row.getObject("source_store_id", UUID.class),
                        row.getObject("destination_store_id", UUID.class), row.getObject("source_position_id", UUID.class),
                        row.getObject("destination_location_id", UUID.class), row.getLong("outstanding"),
                        row.getTimestamp("dispatched_at").toInstant(), row.getLong("age_seconds")))
                .list();
        return page(rows, limit);
    }

    public Page<Discrepancy> discrepancies(UUID organizationId, UUID storeId, int offset, int limit) {
        validatePage(offset, limit);
        var rows = jdbc.sql("""
                SELECT e.id,e.transfer_id,e.line_id,e.evidence_type,e.reason,e.evidence_reference,
                    e.expected_state::text AS expected_state,e.observed_state::text AS observed_state,e.recorded_at
                FROM stockrescue.transfer_reconciliation_evidence e
                JOIN stockrescue.transfer_order t ON t.org_id=e.org_id AND t.id=e.transfer_id
                WHERE e.org_id=:org AND e.evidence_type IN ('DISCREPANCY','RETURN_REQUEST')
                    AND (CAST(:store AS uuid) IS NULL OR t.source_store_id=:store OR t.destination_store_id=:store)
                ORDER BY e.recorded_at DESC,e.id DESC LIMIT :limit OFFSET :offset
                """)
                .param("org", organizationId).param("store", storeId, Types.OTHER)
                .param("limit", limit + 1).param("offset", offset)
                .query((row, index) -> new Discrepancy(row.getObject("id", UUID.class),
                        row.getObject("transfer_id", UUID.class), row.getObject("line_id", UUID.class),
                        row.getString("evidence_type"), row.getString("reason"), row.getString("evidence_reference"),
                        row.getString("expected_state"), row.getString("observed_state"),
                        row.getTimestamp("recorded_at").toInstant()))
                .list();
        return page(rows, limit);
    }

    public boolean hasReconciliationEvidence(UUID organizationId,UUID transferId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM stockrescue.transfer_reconciliation_evidence
                    WHERE org_id=:org AND transfer_id=:transfer
                        AND evidence_type IN ('DISCREPANCY','RETURN_REQUEST','CUSTODY_RECONCILIATION')
                    UNION ALL
                    SELECT 1 FROM stockrescue.transfer_custody_movement
                    WHERE org_id=:org AND transfer_id=:transfer AND movement_type IN ('LOSS','RETURN'))
                """)
                .param("org",organizationId).param("transfer",transferId).query(Boolean.class).single();
    }

    private static TransferSummary summary(java.sql.ResultSet row) throws java.sql.SQLException {
        return new TransferSummary(row.getObject("id", UUID.class), row.getObject("org_id", UUID.class),
                row.getObject("source_store_id", UUID.class), row.getObject("destination_store_id", UUID.class),
                TransferStatus.valueOf(row.getString("status")), row.getLong("version"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                row.getLong("line_count"), row.getBigDecimal("outstanding").toBigIntegerExact());
    }

    private static <T> Page<T> page(List<T> rows, int limit) {
        return new Page<>(rows.stream().limit(limit).toList(), rows.size() > limit);
    }

    private static void validatePage(int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page bounds");
    }

    private static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid evidence text");
        return value.strip();
    }

    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Transfer writes require a writable transaction");
        }
    }

    private record AccountState(long requestedQuantity, TransferQuantities quantities, long version) { }
}
