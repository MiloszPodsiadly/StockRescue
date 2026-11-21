package com.stockrescue.transfers.http;

import java.util.UUID;

import com.stockrescue.transfers.application.TransferOutboxRedriveService;
import com.stockrescue.transfers.application.TransferOutboxRedriveService.ParkedPage;
import com.stockrescue.transfers.application.TransferOutboxRedriveService.RedriveRequest;
import com.stockrescue.transfers.application.TransferOutboxRedriveService.RedriveResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.media.Schema;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value="/api/v1/organizations/{organizationId}/transfers/outbox/parked-events",
        produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="session")
public class TransferOutboxRedriveController {
    @Schema(name="TransferOutboxRedriveRequest")
    public record RedriveRequestBody(@NotNull UUID requestId,@NotNull @Min(0) Long expectedOriginalClaimVersion,
            @NotNull @Min(0) Integer expectedOriginalAttemptCount,@NotBlank @Pattern(regexp="[0-9a-f]{64}") String sourceFingerprint,
            @NotBlank @Size(max=500) String reason,@NotBlank @Size(max=256) String evidenceReference) {
        RedriveRequest command() {
            return new RedriveRequest(requestId,expectedOriginalClaimVersion,expectedOriginalAttemptCount,
                    sourceFingerprint,reason,evidenceReference);
        }
    }
    private final TransferOutboxRedriveService redrives;
    public TransferOutboxRedriveController(TransferOutboxRedriveService redrives) { this.redrives=redrives; }

    @GetMapping
    @io.swagger.v3.oas.annotations.Operation(operationId="listParkedTransferOutboxEvents",
            summary="Read payload-free diagnostics for parked transfer events")
    public ParkedPage parked(Authentication actor,@PathVariable UUID organizationId,
            @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        return redrives.parked(actor(actor),organizationId,offset,limit);
    }
    @PostMapping(value="/{eventId}/redrives",consumes=MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.Operation(operationId="requestTransferOutboxRedrive",
            summary="Request an audited replay of the same immutable parked transfer event")
    public RedriveResult redrive(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID eventId,
            @Valid @RequestBody RedriveRequestBody request) {
        return redrives.redrive(actor(actor),organizationId,eventId,request.command());
    }
    private static UUID actor(Authentication authentication) { return UUID.fromString(authentication.getName()); }
}
