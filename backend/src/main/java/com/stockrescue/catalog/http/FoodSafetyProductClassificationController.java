package com.stockrescue.catalog.http;

import java.time.Instant;
import java.util.UUID;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmation;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmation.ProductProfile;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmationService;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmationSubmission;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value="/api/v1/organizations/{organizationId}/products/{productId}/classification-evidence/operator-confirmations",
        produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="session")
@ApiResponses({
        @ApiResponse(responseCode="400",description="Malformed source-backed confirmation",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="401",description="Authentication required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="403",description="Action or CSRF denied",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="404",description="Organization or product unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="409",description="Changed retry or stale product version",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="500",description="Request failed",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="503",description="Service unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
})
public class FoodSafetyProductClassificationController {
    private final FoodSafetyProductClassificationConfirmationService confirmations;
    public FoodSafetyProductClassificationController(FoodSafetyProductClassificationConfirmationService confirmations) {
        this.confirmations=confirmations;
    }

    @GetMapping
    @Operation(operationId="getOperatorProductClassificationConfirmation",
            summary="Read the latest source-attributed operator confirmation and whether it matches the current product revision")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=OperatorClassificationConfirmationResponse.class)))
    public ResponseEntity<OperatorClassificationConfirmationResponse> operatorConfirmation(Authentication actor,
            @PathVariable UUID organizationId,@PathVariable UUID productId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                OperatorClassificationConfirmationResponse.from(confirmations.confirmation(actor(actor),organizationId,productId)));
    }

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="confirmOperatorProductClassification",
            summary="Record source-attributed category, declared temperature and product profile and bind them to a new product version")
    @ApiResponses({
            @ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=OperatorClassificationConfirmationResponse.class))),
            @ApiResponse(responseCode="200",description="Previously recorded confirmation replayed",content=@Content(schema=@Schema(implementation=OperatorClassificationConfirmationResponse.class)))})
    public ResponseEntity<OperatorClassificationConfirmationResponse> confirmOperatorClassification(Authentication actor,
            @PathVariable UUID organizationId,@PathVariable UUID productId,
            @Valid @RequestBody ConfirmOperatorClassificationRequest request) {
        var submission=new FoodSafetyProductClassificationConfirmationSubmission(request.requestId(),request.expectedProductVersion(),
                request.category(),request.temperatureClass(),request.productProfile(),request.sourceType(),request.evidenceReference(),request.reason());
        var result=confirmations.confirm(actor(actor),organizationId,productId,submission);
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).cacheControl(CacheControl.noStore())
                .body(OperatorClassificationConfirmationResponse.from(result));
    }

    private UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }

    public record ConfirmOperatorClassificationRequest(
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE-1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedProductVersion,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Descriptive category confirmed from the cited source; UNCLASSIFIED stays under review") ProductCategory category,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Declared product storage temperature confirmed from the cited source") TemperatureClass temperatureClass,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Operator-described profile; only PACKAGED_SHELF_STABLE_DRY_BAKERY is in the limited platform pilot") ProductProfile productProfile,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="ORIGINAL_PACKAGE, SUPPLIER_DOCUMENT or OTHER; source is required for every confirmation") FoodSafetyProductClassificationConfirmation.SourceType sourceType,
            @NotBlank @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Reference to the inspected source; source content is not uploaded") @Size(max=256) String evidenceReference,
            @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) { }

    public record OperatorClassificationConfirmationDetails(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID organizationId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID productId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String sku,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long expectedProductVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long productVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ProductCategory originalCategory,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ProductCategory confirmedCategory,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) TemperatureClass originalTemperatureClass,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) TemperatureClass confirmedTemperatureClass,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ProductProfile productProfile,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) FoodSafetyProductClassificationConfirmation.SourceType sourceType,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String evidenceReference,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID actorUserId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID recordedByMembershipId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant recordedAt) {
        static OperatorClassificationConfirmationDetails from(FoodSafetyProductClassificationConfirmation value) {
            return new OperatorClassificationConfirmationDetails(value.id(),value.organizationId(),value.productId(),value.sku(),
                    value.expectedProductVersion(),value.productVersion(),value.originalCategory(),value.confirmedCategory(),
                    value.originalTemperatureClass(),value.confirmedTemperatureClass(),value.productProfile(),
                    value.sourceType(),value.evidenceReference(),value.actorUserId(),value.recordedByMembershipId(),value.requestId(),
                    value.reason(),value.recordedAt());
        }
    }

    @Schema(description="Operator-confirmed category, declared temperature and product-profile evidence is attributed and source-referenced; source contents are not independently verified.")
    public record OperatorClassificationConfirmationResponse(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ProductController.ProductResponse product,
            @Schema(types={"object","null"},requiredMode=Schema.RequiredMode.REQUIRED) OperatorClassificationConfirmationDetails confirmation,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) FoodSafetyProductClassificationConfirmation.Status status) {
        static OperatorClassificationConfirmationResponse from(FoodSafetyProductClassificationConfirmationService.ConfirmationResult value) {
            return new OperatorClassificationConfirmationResponse(ProductController.ProductResponse.from(value.product()),
                    value.confirmation()==null?null:OperatorClassificationConfirmationDetails.from(value.confirmation()),value.status());
        }
    }
}
