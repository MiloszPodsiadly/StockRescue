package com.stockrescue.transfers.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.stockrescue.transfers.application.TransferService.DispatchLine;
import com.stockrescue.transfers.application.TransferService.DispatchRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TransferCommandFingerprintServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final TransferCommandFingerprintService fingerprints = new TransferCommandFingerprintService(json);

    @Test
    void nestedTextFieldsAreFramedAndNormalizedLineOrderingStaysEquivalent() {
        UUID organizationId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID firstLineId = UUID.randomUUID();
        UUID secondLineId = UUID.randomUUID();

        var firstPayload = new DispatchRequest(requestId, 4, "dispatch-document",
                List.of(new DispatchLine(firstLineId, 1, "SKU, B", "C"),
                        new DispatchLine(secondLineId, 2, "SECOND-SKU", "SECOND-LOT")),
                "Reviewed dispatch", "dispatch evidence");
        var reorderedPayload = new DispatchRequest(requestId, 4, "dispatch-document",
                List.of(new DispatchLine(secondLineId, 2, "SECOND-SKU", "SECOND-LOT"),
                        new DispatchLine(firstLineId, 1, "SKU, B", "C")),
                "Reviewed dispatch", "dispatch evidence");
        var collisionPayload = new DispatchRequest(requestId, 4, "dispatch-document",
                List.of(new DispatchLine(firstLineId, 1, "SKU", "B, C"),
                        new DispatchLine(secondLineId, 2, "SECOND-SKU", "SECOND-LOT")),
                "Reviewed dispatch", "dispatch evidence");
        var first = TransferRequestNormalizer.normalize(firstPayload);
        var reorderedEquivalent = TransferRequestNormalizer.normalize(reorderedPayload);
        var delimiterCollision = TransferRequestNormalizer.normalize(collisionPayload);

        byte[] expected = fingerprints.forRequest(organizationId, "DISPATCH", transferId, first);
        assertThat(Arrays.equals(expected,
                fingerprints.forRequest(organizationId, "DISPATCH", transferId, reorderedEquivalent))).isTrue();
        assertThat(Arrays.equals(expected,
                fingerprints.forRequest(organizationId, "DISPATCH", transferId, delimiterCollision))).isFalse();
        assertThat(Arrays.equals(expected,
                fingerprints.fromPayload(organizationId, "DISPATCH", transferId, json.valueToTree(firstPayload)))).isTrue();
        assertThat(Arrays.equals(expected,
                fingerprints.fromPayload(organizationId, "DISPATCH", transferId, json.valueToTree(reorderedPayload)))).isTrue();
        assertThat(Arrays.equals(expected,
                fingerprints.fromPayload(organizationId, "DISPATCH", transferId, json.valueToTree(collisionPayload)))).isFalse();
    }
}
