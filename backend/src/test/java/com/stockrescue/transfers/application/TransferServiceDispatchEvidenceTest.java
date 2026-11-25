package com.stockrescue.transfers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stockrescue.transfers.domain.TransferWriteConflict;
import com.stockrescue.transfers.persistence.TransferRepository.DispatchEvidence;
import org.junit.jupiter.api.Test;

class TransferServiceDispatchEvidenceTest {
    private final UUID firstLine = UUID.randomUUID();
    private final UUID secondLine = UUID.randomUUID();
    private final Instant dispatchTime = Instant.parse("2026-11-24T12:00:00Z");
    private final Map<UUID, Long> requestedQuantities = Map.of(firstLine, 4L, secondLine, 7L);

    @Test
    void usesOneFullImmutableDispatchMovementPerTransferLineAsTheReceiptBoundary() {
        var boundaries = TransferService.verifiedDispatchBoundaries(requestedQuantities, List.of(
                new DispatchEvidence(firstLine, 1, 4, dispatchTime),
                new DispatchEvidence(secondLine, 1, 7, dispatchTime.plusSeconds(1))));

        assertThat(boundaries).containsExactlyInAnyOrderEntriesOf(Map.of(
                firstLine, dispatchTime, secondLine, dispatchTime.plusSeconds(1)));
    }

    @Test
    void rejectsMissingOrUnexpectedDispatchLines() {
        assertThatThrownBy(() -> TransferService.verifiedDispatchBoundaries(requestedQuantities,
                List.of(new DispatchEvidence(firstLine, 1, 4, dispatchTime))))
                .isInstanceOf(TransferWriteConflict.class);
        assertThatThrownBy(() -> TransferService.verifiedDispatchBoundaries(Map.of(firstLine, 4L),
                List.of(new DispatchEvidence(firstLine, 1, 4, dispatchTime),
                        new DispatchEvidence(secondLine, 1, 7, dispatchTime.plusSeconds(1)))))
                .isInstanceOf(TransferWriteConflict.class);
    }

    @Test
    void rejectsDuplicatePartialAmbiguousOrUntimedDispatchEvidence() {
        var duplicate = new DispatchEvidence(firstLine, 1, 4, dispatchTime);
        assertThatThrownBy(() -> TransferService.verifiedDispatchBoundaries(Map.of(firstLine, 4L), List.of(duplicate, duplicate)))
                .isInstanceOf(TransferWriteConflict.class);
        assertThatThrownBy(() -> TransferService.verifiedDispatchBoundaries(Map.of(firstLine, 4L),
                List.of(new DispatchEvidence(firstLine, 1, 3, dispatchTime))))
                .isInstanceOf(TransferWriteConflict.class);
        assertThatThrownBy(() -> TransferService.verifiedDispatchBoundaries(Map.of(firstLine, 4L),
                List.of(new DispatchEvidence(firstLine, 2, 4, dispatchTime))))
                .isInstanceOf(TransferWriteConflict.class);
        assertThatThrownBy(() -> TransferService.verifiedDispatchBoundaries(Map.of(firstLine, 4L),
                List.of(new DispatchEvidence(firstLine, 1, 4, null))))
                .isInstanceOf(TransferWriteConflict.class);
    }
}
