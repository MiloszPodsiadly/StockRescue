package com.stockrescue.catalog.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Interprets only explicit ISO label precision; it never derives a safety cutoff. */
public record DateLabelAssessment(Status status,List<Reason> reasons) {
    public enum Status { VALID, INCOMPLETE, INVALID }
    public enum Reason {
        MISSING_LABEL, UNKNOWN_TYPE, UNKNOWN_PRECISION, MISSING_PROVENANCE, MISSING_REFERENCE,
        USE_BY_REQUIRES_DAY, BEST_BEFORE_END_REQUIRES_PERIOD, UNRECOGNIZED_FORMAT, NONEXISTENT_DATE
    }
    public DateLabelAssessment { reasons=List.copyOf(reasons); }

    public static DateLabelAssessment assess(DateMarking marking) {
        var reasons=new ArrayList<Reason>();
        var label=marking.labeledValue();
        boolean missing=label==null || label.isBlank();
        if (missing) reasons.add(Reason.MISSING_LABEL);
        if (marking.type()==DateMarkingType.UNKNOWN) reasons.add(Reason.UNKNOWN_TYPE);
        if (marking.precision()==DatePrecision.UNKNOWN) reasons.add(Reason.UNKNOWN_PRECISION);
        if (marking.provenance().isEmpty()) reasons.add(Reason.MISSING_PROVENANCE);
        else if (marking.provenance().orElseThrow().reference()==null) reasons.add(Reason.MISSING_REFERENCE);
        if (marking.type()==DateMarkingType.USE_BY && marking.precision()!=DatePrecision.DAY) reasons.add(Reason.USE_BY_REQUIRES_DAY);
        if (marking.type()==DateMarkingType.BEST_BEFORE_END && marking.precision()==DatePrecision.DAY)
            reasons.add(Reason.BEST_BEFORE_END_REQUIRES_PERIOD);
        boolean invalid=false;
        if (!missing && marking.precision()!=DatePrecision.UNKNOWN) {
            var value=label.strip();
            var format=switch(marking.precision()) {
                case DAY -> "[0-9]{4}-[0-9]{2}-[0-9]{2}";
                case MONTH -> "[0-9]{4}-[0-9]{2}";
                case YEAR -> "[0-9]{4}";
                case UNKNOWN -> throw new IllegalStateException();
            };
            if (!value.matches(format)) { reasons.add(Reason.UNRECOGNIZED_FORMAT); invalid=true; }
            else {
                try {
                    int year=Integer.parseInt(value.substring(0,4));
                    if (year<1) throw new DateTimeException("Positive label year required");
                    switch(marking.precision()) {
                        case DAY -> LocalDate.parse(value);
                        case MONTH -> YearMonth.parse(value);
                        case YEAR -> { }
                        case UNKNOWN -> throw new IllegalStateException();
                    }
                } catch (DateTimeException malformed) { reasons.add(Reason.NONEXISTENT_DATE); invalid=true; }
            }
        }
        return new DateLabelAssessment(invalid?Status.INVALID:reasons.isEmpty()?Status.VALID:Status.INCOMPLETE,reasons);
    }
}
