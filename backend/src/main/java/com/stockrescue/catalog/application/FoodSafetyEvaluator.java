package com.stockrescue.catalog.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Objects;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.*;
import com.stockrescue.catalog.application.ManufacturerLot.DamageState;
import com.stockrescue.catalog.application.ManufacturerLot.RecallState;
import com.stockrescue.catalog.domain.*;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import com.stockrescue.stores.application.OperationalConfiguration.Zone;
import com.stockrescue.stores.application.Store;
import com.stockrescue.stores.application.StorageLocation;

/** Pure evaluation of one immutable input snapshot. Hard rules cannot be weakened by a policy review. */
public final class FoodSafetyEvaluator {
    public static final String VERSION="conservative-pilot-3";
    private static final EnumSet<ReasonCode> LOT_REVIEW_REASONS=EnumSet.of(ReasonCode.LOT_IDENTITY_UNVERIFIED,ReasonCode.DATE_LABEL_MISSING,
            ReasonCode.DATE_TYPE_UNKNOWN,ReasonCode.DATE_PRECISION_UNKNOWN,ReasonCode.DATE_LABEL_INVALID,ReasonCode.USE_BY_DAY_REQUIRED,
            ReasonCode.DATE_PROVENANCE_MISSING,ReasonCode.DATE_REFERENCE_MISSING,ReasonCode.PARTIAL_DATE_RULE_UNVERIFIED,
            ReasonCode.RECALL_STATUS_UNKNOWN,ReasonCode.DAMAGE_STATUS_UNKNOWN,ReasonCode.STATUS_REFERENCE_MISSING);
    public record Context(ManufacturerLot lot,Product product,Store store,Zone zone,FoodSafetyPolicyVersion policy,StorageLocation location) {
        public Context { Objects.requireNonNull(lot);Objects.requireNonNull(product);Objects.requireNonNull(store); }
        public Context(ManufacturerLot lot,Product product,Store store,Zone zone,FoodSafetyPolicyVersion policy) { this(lot,product,store,zone,policy,null); }
    }
    public FoodSafetyEvaluation evaluate(Context context,Operation operation,Instant evaluatedAt) {
        Objects.requireNonNull(operation);Objects.requireNonNull(evaluatedAt);
        var lot=context.lot(); var product=context.product(); var store=context.store(); var zone=context.zone();
        var localDate=evaluatedAt.atZone(ZoneId.of(store.data().zoneId())).toLocalDate();
        var reasons=EnumSet.noneOf(ReasonCode.class);
        if (lot.recallState()==RecallState.RECALLED) reasons.add(ReasonCode.LOT_RECALLED);
        if (lot.damageState()==DamageState.DAMAGED) reasons.add(ReasonCode.LOT_DAMAGED);
        if (!lot.active()) reasons.add(ReasonCode.LOT_ARCHIVED);
        if (!product.active()) reasons.add(ReasonCode.PRODUCT_ARCHIVED);
        if (!store.active()) reasons.add(ReasonCode.STORE_ARCHIVED);
        if (zone!=null && !zone.active()) reasons.add(ReasonCode.ZONE_ARCHIVED);
        if (context.location()!=null && !context.location().active()) reasons.add(ReasonCode.LOCATION_ARCHIVED);
        if (zone!=null && zone.temperatureClass()!=product.data().temperatureClass()) reasons.add(ReasonCode.TEMPERATURE_CLASS_MISMATCH);
        var date=assessDate(lot.data().dateMarking(),localDate,reasons);
        if (lot.data().identityState()!=LotIdentityState.CONFIRMED) reasons.add(ReasonCode.LOT_IDENTITY_UNVERIFIED);
        if (lot.recallState()==RecallState.UNKNOWN) reasons.add(ReasonCode.RECALL_STATUS_UNKNOWN);
        if (lot.damageState()==DamageState.UNKNOWN) reasons.add(ReasonCode.DAMAGE_STATUS_UNKNOWN);
        if ((lot.recallState()==RecallState.NOT_REPORTED || lot.damageState()==DamageState.NOT_REPORTED)
                && (lot.statusProvenance()==null || lot.statusProvenance().reference()==null)) reasons.add(ReasonCode.STATUS_REFERENCE_MISSING);
        var storage=zone==null?StorageEvidenceStatus.MISSING:!store.active() || !zone.active()
                || context.location()!=null && !context.location().usable()?StorageEvidenceStatus.INACTIVE
                :zone.temperatureClass()==product.data().temperatureClass()?StorageEvidenceStatus.DECLARED_COMPATIBLE:StorageEvidenceStatus.DECLARED_INCOMPATIBLE;
        if (zone==null) reasons.add(ReasonCode.STORAGE_CONTEXT_MISSING);
        if (product.data().temperatureClass()!=TemperatureClass.AMBIENT) reasons.add(ReasonCode.COLD_CHAIN_EVIDENCE_UNVERIFIED);
        var policy=context.policy();
        if (policy==null) reasons.add(ReasonCode.POLICY_MISSING);
        else {
            if (policy.temperatureClass()!=product.data().temperatureClass()) reasons.add(ReasonCode.POLICY_CLASS_MISMATCH);
            reasons.add(ReasonCode.POLICY_APPROVAL_UNVERIFIED);
        }
        boolean denied=reasons.stream().anyMatch(ReasonCode::hardDenial);
        // Only unverified reviews can currently be recorded. They never authorize SALE or TRANSFER,
        // including when every date/declared-temperature check succeeds.
        var next=denied?NextAction.DO_NOT_EXECUTE
                :reasons.stream().anyMatch(LOT_REVIEW_REASONS::contains)
                    ?NextAction.REVIEW_LOT_EVIDENCE:zone==null?NextAction.RECORD_STORAGE_CONTEXT:NextAction.OBTAIN_INDEPENDENT_POLICY_APPROVAL;
        return new FoodSafetyEvaluation(denied?Decision.DENIED:Decision.REQUIRES_REVIEW,reasons.stream().toList(),operation,lot.id(),lot.version(),
                product.id(),product.version(),store.id(),store.version(),zone==null?null:zone.id(),zone==null?null:zone.version(),
                context.location()==null?null:context.location().id(),context.location()==null?null:context.location().version(),
                policy==null?null:new PolicyReference(policy.id(),policy.profileId(),policy.version()),evaluatedAt,localDate,store.data().zoneId(),
                new EvidenceAssessment(date,recall(lot.recallState()),damage(lot.damageState()),storage,policy==null?PolicyEvidenceStatus.MISSING:PolicyEvidenceStatus.RECORDED_UNVERIFIED),next,VERSION);
    }
    private DateEvidenceStatus assessDate(DateMarking marking,LocalDate localDate,EnumSet<ReasonCode> reasons) {
        var assessment=DateLabelAssessment.assess(marking);
        for (var reason:assessment.reasons()) reasons.add(switch(reason) {
            case MISSING_LABEL -> ReasonCode.DATE_LABEL_MISSING;
            case UNKNOWN_TYPE -> ReasonCode.DATE_TYPE_UNKNOWN;
            case UNKNOWN_PRECISION -> ReasonCode.DATE_PRECISION_UNKNOWN;
            case MISSING_PROVENANCE -> ReasonCode.DATE_PROVENANCE_MISSING;
            case USE_BY_REQUIRES_DAY -> ReasonCode.USE_BY_DAY_REQUIRED;
            case UNRECOGNIZED_FORMAT, NONEXISTENT_DATE -> ReasonCode.DATE_LABEL_INVALID;
        });
        if (marking.provenance().isPresent() && marking.provenance().orElseThrow().reference()==null) reasons.add(ReasonCode.DATE_REFERENCE_MISSING);
        if (marking.precision()==DatePrecision.DAY && marking.type()!=DateMarkingType.UNKNOWN
                && marking.labeledValue()!=null && !marking.labeledValue().isBlank() && assessment.status()!=DateLabelAssessment.Status.INVALID) {
            var labelDate=LocalDate.parse(marking.labeledValue().strip());
            if (localDate.isAfter(labelDate)) reasons.add(marking.type()==DateMarkingType.USE_BY?ReasonCode.USE_BY_EXPIRED:ReasonCode.BEST_BEFORE_EXPIRED);
        }
        boolean partial=marking.precision()==DatePrecision.MONTH || marking.precision()==DatePrecision.YEAR;
        if (partial) reasons.add(ReasonCode.PARTIAL_DATE_RULE_UNVERIFIED);
        return assessment.status()==DateLabelAssessment.Status.INVALID?DateEvidenceStatus.INVALID
                :assessment.status()==DateLabelAssessment.Status.INCOMPLETE || reasons.contains(ReasonCode.DATE_REFERENCE_MISSING)?DateEvidenceStatus.INCOMPLETE
                :partial?DateEvidenceStatus.PARTIAL_REQUIRES_VERIFIED_RULE:DateEvidenceStatus.COMPLETE_LABEL;
    }
    private StatusEvidenceStatus recall(RecallState value) { return switch(value) { case UNKNOWN -> StatusEvidenceStatus.UNKNOWN;case NOT_REPORTED -> StatusEvidenceStatus.REPORTED;case RECALLED -> StatusEvidenceStatus.BLOCK_RECORDED; }; }
    private StatusEvidenceStatus damage(DamageState value) { return switch(value) { case UNKNOWN -> StatusEvidenceStatus.UNKNOWN;case NOT_REPORTED -> StatusEvidenceStatus.REPORTED;case DAMAGED -> StatusEvidenceStatus.BLOCK_RECORDED; }; }
}
