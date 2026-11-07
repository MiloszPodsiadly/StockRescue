package com.stockrescue.catalog.http;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.catalog.application.ProductData;
import com.stockrescue.catalog.application.ProductService;
import com.stockrescue.catalog.domain.UnitOfMeasure;
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
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value="/api/v1/organizations/{organizationId}/products",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="session")
@ApiResponses({
        @ApiResponse(responseCode="400",description="Malformed fields or page",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="401",description="Authentication required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="403",description="Action or CSRF denied",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="404",description="Organization or product unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="409",description="Reserved SKU, changed retry, stale version or inactive product",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="422",description="Invalid catalog identity, unit or product data",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="500",description="Request failed",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="503",description="Service unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
})
public class ProductController {
    private final ProductService products;
    public ProductController(ProductService products) { this.products=products; }

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="createProduct",summary="Catalog administrator creates one durable product; unchanged retries return its current state")
    @ApiResponses({
            @ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=ProductResponse.class))),
            @ApiResponse(responseCode="200",description="Recorded creation replayed; current product state",content=@Content(schema=@Schema(implementation=ProductResponse.class)))
    })
    public ResponseEntity<ProductResponse> create(Authentication actor,@PathVariable UUID organizationId,@Valid @RequestBody CreateProductRequest request) {
        var result=products.create(actor(actor),organizationId,request.requestId(),ProductData.parse(request.sku(),request.name(),request.gtin(),request.baseUnit(),request.category(),request.temperatureClass()));
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).body(ProductResponse.from(result.view()));
    }

    @GetMapping
    @Operation(operationId="listProducts",summary="Active organization members search literal SKU, name or GTIN with deterministic SKU/UUID ordering")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=ProductPage.class)))
    public ProductPage list(Authentication actor,@PathVariable UUID organizationId,
            @RequestParam(defaultValue="ACTIVE") ProductService.State state,
            @RequestParam(defaultValue="") @Size(max=100) String q,
            @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var result=products.list(actor(actor),organizationId,state,q,offset,limit);
        return new ProductPage(result.items().stream().map(ProductResponse::from).toList(),result.hasMore(),result.canCreate());
    }

    @GetMapping("/{productId}")
    @Operation(operationId="getProduct",summary="Read organization catalog metadata, including archived identity")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=ProductResponse.class)))
    public ProductResponse detail(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID productId) {
        return ProductResponse.from(products.detail(actor(actor),organizationId,productId));
    }

    @PostMapping(value="/{productId}/archive",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="archiveProduct",summary="Catalog administrator archives a versioned product with a durable retry key and reason")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=ProductResponse.class)))
    public ProductResponse archive(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID productId,@Valid @RequestBody ArchiveProductRequest request) {
        return ProductResponse.from(products.archive(actor(actor),organizationId,productId,request.requestId(),request.expectedVersion(),request.reason()));
    }

    @PostMapping(value="/{productId}/category-corrections",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="correctProductCategory",summary="Correct a product's descriptive category with an expected version and audited reason")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=ProductResponse.class)))
    public ProductResponse correctCategory(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID productId,@Valid @RequestBody CorrectProductCategoryRequest request) {
        return ProductResponse.from(products.reclassify(actor(actor),organizationId,productId,request.requestId(),request.expectedVersion(),request.category(),request.reason()));
    }

    private UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }
    public record CreateProductRequest(
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotBlank @Size(max=128) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String sku,
            @NotBlank @Size(max=160) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String name,
            @Size(max=14) String gtin,
            @NotBlank @Size(max=8) @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"EA"}) String baseUnit,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Descriptive product category; UNCLASSIFIED is not safety approval") ProductCategory category,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) TemperatureClass temperatureClass) { }
    public record ArchiveProductRequest(
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedVersion,
            @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) { }
    public record CorrectProductCategoryRequest(
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE-1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedVersion,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Descriptive product category only") ProductCategory category,
            @NotBlank @Size(max=500) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason) { }
    public record ProductPage(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<ProductResponse> items,
                              @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore,
                              @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean canCreate) { }
    public record ProductResponse(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String sku,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String name,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String gtin,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UnitOfMeasure baseUnit,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ProductCategory category,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) TemperatureClass temperatureClass,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean active,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long version,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant updatedAt,
            @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant archivedAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<OrganizationAction> allowedActions) {
        static ProductResponse from(ProductService.View view) {
            var product=view.product();
            var data=product.data();
            return new ProductResponse(product.id(),data.sku().value(),data.name(),data.gtin()==null?null:data.gtin().value(),data.baseUnit(),data.category(),
                    data.temperatureClass(),product.active(),product.version(),product.createdAt(),product.updatedAt(),product.archivedAt(),view.allowedActions());
        }
    }
}
