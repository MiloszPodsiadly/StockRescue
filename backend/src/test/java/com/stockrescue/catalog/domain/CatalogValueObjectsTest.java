package com.stockrescue.catalog.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogValueObjectsTest {
    @Test
    void skuTrimsAsciiSpacesAndCanonicalizesCase() {
        var canonical = new Sku("  ab-12/x  ");

        assertThat(canonical.value()).isEqualTo("AB-12/X");
        assertThat(new Sku("ab-12/x")).isEqualTo(canonical);
    }

    @Test
    void skuRejectsBlankControlProhibitedAndNonAsciiCharacters() {
        for (var value : new String[]{null, "   ", "A\tB", "A\u0001", "A#B", "café", "cafe\u0301"}) {
            assertThatThrownBy(() -> new Sku(value))
                    .isInstanceOf(CatalogFailure.class)
                    .extracting(error -> ((CatalogFailure) error).kind())
                    .isEqualTo(CatalogFailure.Kind.INVALID_SKU);
        }
    }

    @Test
    void gtinValidatesSupportedLengthsAndCheckDigitsWithoutDroppingLeadingZeroes() {
        assertThat(new Gtin("96385074").value()).isEqualTo("96385074");
        assertThat(new Gtin("036000291452").value()).isEqualTo("036000291452");
        assertThat(new Gtin("4006381333931").value()).isEqualTo("4006381333931");
        assertThat(new Gtin("04006381333931").value()).isEqualTo("04006381333931");
    }

    @Test
    void gtinRejectsUnsupportedLengthNonDigitsAndIncorrectCheckDigit() {
        for (var value : new String[]{null, "1234567", "123456789", "036000291453", "400638133393X"}) {
            assertThatThrownBy(() -> new Gtin(value))
                    .isInstanceOf(CatalogFailure.class)
                    .extracting(error -> ((CatalogFailure) error).kind())
                    .isEqualTo(CatalogFailure.Kind.INVALID_GTIN);
        }
    }

    @Test
    void onlyEachUnitIsSupported() {
        assertThat(UnitOfMeasure.parse("EA")).isEqualTo(UnitOfMeasure.EA);
        assertThatThrownBy(() -> UnitOfMeasure.parse("KG"))
                .isInstanceOf(CatalogFailure.class)
                .extracting(error -> ((CatalogFailure) error).kind())
                .isEqualTo(CatalogFailure.Kind.UNSUPPORTED_UNIT);
    }

    @Test
    void provenanceFailuresHaveTheirOwnSafeDomainKind() {
        assertThatThrownBy(() -> new EvidenceProvenance(" \t", null))
                .isInstanceOf(CatalogFailure.class)
                .hasMessage("Catalog value is invalid.")
                .extracting(error -> ((CatalogFailure) error).kind())
                .isEqualTo(CatalogFailure.Kind.INVALID_PROVENANCE);
    }

    @Test
    void dateMarkingsKeepExactLabelsAndDoNotGuessPartialDates() {
        var provenance = Optional.of(new EvidenceProvenance("manufacturer label", "photo-17"));
        var useByDay = new DateMarking("30/04/2027", DateMarkingType.USE_BY, DatePrecision.DAY,
                provenance, Optional.empty());
        var useByMonth = new DateMarking("04/2027", DateMarkingType.USE_BY, DatePrecision.MONTH,
                provenance, Optional.empty());
        var bestBeforeDay = new DateMarking("30/04/2027", DateMarkingType.BEST_BEFORE, DatePrecision.DAY,
                provenance, Optional.empty());
        var bestBeforeEndMonth = new DateMarking("04/2027", DateMarkingType.BEST_BEFORE_END,
                DatePrecision.MONTH, provenance, Optional.empty());
        var bestBeforeEndYear = new DateMarking("2027", DateMarkingType.BEST_BEFORE_END,
                DatePrecision.YEAR, provenance, Optional.empty());
        var unknown = new DateMarking("not legible", DateMarkingType.UNKNOWN, DatePrecision.UNKNOWN,
                Optional.empty(), Optional.empty());

        assertThat(useByDay.labeledValue()).isEqualTo("30/04/2027");
        assertThat(useByDay.normalizedCutoff()).isEmpty();
        assertThat(useByDay.provenance()).contains(new EvidenceProvenance("manufacturer label", "photo-17"));
        assertThat(useByMonth.labeledValue()).isEqualTo("04/2027");
        assertThat(useByMonth.normalizedCutoff()).isEmpty();
        assertThat(bestBeforeDay.type()).isEqualTo(DateMarkingType.BEST_BEFORE);
        assertThat(bestBeforeDay.normalizedCutoff()).isEmpty();
        assertThat(bestBeforeEndMonth.normalizedCutoff()).isEmpty();
        assertThat(bestBeforeEndYear.normalizedCutoff()).isEmpty();
        assertThat(unknown.type()).isEqualTo(DateMarkingType.UNKNOWN);
        assertThat(unknown.precision()).isEqualTo(DatePrecision.UNKNOWN);
        assertThat(unknown.provenance()).isEmpty();
        assertThat(unknown.normalizedCutoff()).isEmpty();
    }

    @Test
    void normalizedCutoffRequiresExplicitPolicyEvidenceAndCompleteUseByDay() {
        var policyCutoff = new PolicyDerivedCutoff(LocalDate.of(2027, 4, 30), UUID.randomUUID(), 2, "date-rule-v2");
        var marking = new DateMarking("30/04/2027", DateMarkingType.USE_BY, DatePrecision.DAY,
                Optional.of(new EvidenceProvenance("manufacturer label", "photo-17")), Optional.of(policyCutoff));

        assertThat(marking.normalizedCutoff()).contains(policyCutoff);
        assertThatThrownBy(() -> new DateMarking("04/2027", DateMarkingType.USE_BY, DatePrecision.MONTH,
                Optional.of(new EvidenceProvenance("manufacturer label", "photo-17")), Optional.of(policyCutoff)))
                .isInstanceOf(CatalogFailure.class)
                .extracting(error -> ((CatalogFailure) error).kind())
                .isEqualTo(CatalogFailure.Kind.INVALID_DATE_MARKING);
    }
}
