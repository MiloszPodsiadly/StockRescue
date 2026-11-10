package com.stockrescue.catalog.http;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import com.stockrescue.catalog.domain.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class CatalogEvidenceDtos {
    private CatalogEvidenceDtos() { }
    public record CatalogProvenanceRequest(
            @NotBlank @Size(max=256) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String source,
            @Size(max=256) String reference) {
        EvidenceProvenance value() { return new EvidenceProvenance(source,reference); }
    }
    public record CatalogProvenanceResponse(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String source,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String reference) {
        static CatalogProvenanceResponse from(EvidenceProvenance value) { return value==null?null:new CatalogProvenanceResponse(value.source(),value.reference()); }
    }
    public record DateMarkingRequest(
            @Size(max=256) String labeledValue,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) DateMarkingType type,
            @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) DatePrecision precision,
            @Valid CatalogProvenanceRequest provenance) {
        DateMarking value() { return new DateMarking(labeledValue,type,precision,Optional.ofNullable(provenance).map(CatalogProvenanceRequest::value),Optional.empty()); }
    }
    @Schema(description="Original label and parsing evidence. Validation is not operational safety approval; partial dates never acquire a guessed day.")
    public record DateMarkingResponse(
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String labeledValue,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) DateMarkingType type,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) DatePrecision precision,
            @Schema(types={"object","null"},requiredMode=Schema.RequiredMode.REQUIRED) CatalogProvenanceResponse provenance,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) DateLabelAssessment.Status validationStatus,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<DateLabelAssessment.Reason> validationReasons,
            @Schema(types={"string","null"},format="date",requiredMode=Schema.RequiredMode.REQUIRED) LocalDate normalizedCutoff) {
        static DateMarkingResponse from(DateMarking value) {
            var assessment=DateLabelAssessment.assess(value);
            return new DateMarkingResponse(value.labeledValue(),value.type(),value.precision(),CatalogProvenanceResponse.from(value.provenance().orElse(null)),
                    assessment.status(),assessment.reasons(),value.normalizedCutoff().map(PolicyDerivedCutoff::value).orElse(null));
        }
    }
}
