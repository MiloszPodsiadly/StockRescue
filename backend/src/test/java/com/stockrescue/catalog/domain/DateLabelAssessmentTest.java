package com.stockrescue.catalog.domain;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DateLabelAssessmentTest {
    @Test void explicitIsoPrecisionIsParsedWithoutDerivingACutoff() {
        for (var entry:java.util.Map.of(DatePrecision.DAY,"2028-02-29",DatePrecision.MONTH,"2027-02",DatePrecision.YEAR,"2027").entrySet()) {
            var marking=marking(entry.getValue(),DateMarkingType.BEST_BEFORE_END,entry.getKey(),true);
            assertThat(DateLabelAssessment.assess(marking).status()).isEqualTo(DateLabelAssessment.Status.VALID);
            assertThat(marking.normalizedCutoff()).isEmpty();
        }
    }
    @Test void missingAndPartialEvidenceRemainIncompleteWithOrderedReasons() {
        assertThat(DateLabelAssessment.assess(marking(null,DateMarkingType.UNKNOWN,DatePrecision.UNKNOWN,false)).reasons())
                .containsExactly(DateLabelAssessment.Reason.MISSING_LABEL,DateLabelAssessment.Reason.UNKNOWN_TYPE,
                        DateLabelAssessment.Reason.UNKNOWN_PRECISION,DateLabelAssessment.Reason.MISSING_PROVENANCE);
        var useBy=DateLabelAssessment.assess(marking("2027-02",DateMarkingType.USE_BY,DatePrecision.MONTH,true));
        assertThat(useBy.status()).isEqualTo(DateLabelAssessment.Status.INCOMPLETE);
        assertThat(useBy.reasons()).containsExactly(DateLabelAssessment.Reason.USE_BY_REQUIRES_DAY);
    }
    @Test void malformedAndNonexistentDatesAreDistinctFromMissingEvidence() {
        for (var value:java.util.List.of("2027-02-29","2027-04-31","0000-01-01")) {
            var result=DateLabelAssessment.assess(marking(value,DateMarkingType.USE_BY,DatePrecision.DAY,true));
            assertThat(result.status()).isEqualTo(DateLabelAssessment.Status.INVALID);
            assertThat(result.reasons()).containsExactly(DateLabelAssessment.Reason.NONEXISTENT_DATE);
        }
        var ambiguous=DateLabelAssessment.assess(marking("01/02/2027",DateMarkingType.USE_BY,DatePrecision.DAY,true));
        assertThat(ambiguous.status()).isEqualTo(DateLabelAssessment.Status.INVALID);
        assertThat(ambiguous.reasons()).containsExactly(DateLabelAssessment.Reason.UNRECOGNIZED_FORMAT);
        assertThat(DateLabelAssessment.assess(marking("2027-13",DateMarkingType.BEST_BEFORE_END,DatePrecision.MONTH,true)).status()).isEqualTo(DateLabelAssessment.Status.INVALID);
    }
    private DateMarking marking(String label,DateMarkingType type,DatePrecision precision,boolean source) {
        return new DateMarking(label,type,precision,source?Optional.of(new EvidenceProvenance("label",null)):Optional.empty(),Optional.empty());
    }
}
