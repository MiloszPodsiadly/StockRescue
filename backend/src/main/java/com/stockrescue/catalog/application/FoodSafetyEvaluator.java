package com.stockrescue.catalog.application;

import java.time.Instant;
import java.time.DateTimeException;
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
    public static final String VERSION=FoodSafetyRuleset.CURRENT_ID;
    private static final FoodSafetyRuleset RULESET = new FoodSafetyRuleset();
    private static final EnumSet<ReasonCode> LOT_REVIEW_REASONS=EnumSet.of(ReasonCode.LOT_IDENTITY_UNVERIFIED,ReasonCode.DATE_LABEL_MISSING,
            ReasonCode.DATE_TYPE_UNKNOWN,ReasonCode.DATE_PRECISION_UNKNOWN,ReasonCode.DATE_LABEL_INVALID,ReasonCode.USE_BY_DAY_REQUIRED,
            ReasonCode.DATE_TYPE_PRECISION_MISMATCH,
            ReasonCode.DATE_PROVENANCE_MISSING,ReasonCode.DATE_REFERENCE_MISSING,ReasonCode.PARTIAL_DATE_RULE_UNVERIFIED,
            ReasonCode.RECALL_STATUS_UNKNOWN,ReasonCode.DAMAGE_STATUS_UNKNOWN,ReasonCode.STATUS_REFERENCE_MISSING);
    public record Context(ManufacturerLot lot,Product product,Store store,Zone zone,FoodSafetyPolicyVersion policy,StorageLocation location) {
        public Context { Objects.requireNonNull(lot);Objects.requireNonNull(product);Objects.requireNonNull(store); }
        public Context(ManufacturerLot lot,Product product,Store store,Zone zone,FoodSafetyPolicyVersion policy) { this(lot,product,store,zone,policy,null); }
    }
    public FoodSafetyEvaluation evaluate(Context context,Operation operation,Instant evaluatedAt) {
        return evaluate(context,operation,evaluatedAt,null,null,null);
    }
    public FoodSafetyEvaluation evaluate(Context context,Operation operation,Instant evaluatedAt,FoodSafetyPolicyApprovalVerification approvalVerification) {
        return evaluate(context,operation,evaluatedAt,approvalVerification,null,null);
    }
    public FoodSafetyEvaluation evaluate(Context context,Operation operation,Instant evaluatedAt,
            FoodSafetyPolicyApprovalVerification approvalVerification,FoodSafetyProductClassificationVerification classificationVerification) {
        return evaluate(context,operation,evaluatedAt,approvalVerification,classificationVerification,null);
    }
    public FoodSafetyEvaluation evaluate(Context context,Operation operation,Instant evaluatedAt,
            FoodSafetyPolicyApprovalVerification approvalVerification,FoodSafetyProductClassificationVerification classificationVerification,
            FoodSafetyProductClassificationConfirmation operatorClassificationConfirmation) {
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
        if (product.data().category()==ProductCategory.UNCLASSIFIED) reasons.add(ReasonCode.PRODUCT_CATEGORY_UNCLASSIFIED);
        var profile=operatorClassificationConfirmation==null?null:operatorClassificationConfirmation.productProfile();
        var sourceType=operatorClassificationConfirmation==null?null:operatorClassificationConfirmation.sourceType();
        if (!RULESET.supports(product.data().category(),product.data().temperatureClass(),profile,sourceType,operation))
            reasons.add(ReasonCode.PLATFORM_RULESET_SCOPE_UNSUPPORTED);
        boolean externalClassificationVerified=classificationVerification!=null && classificationVerification.authorizes(product);
        var operatorClassificationStatus=operatorClassificationConfirmation==null
                ?FoodSafetyProductClassificationConfirmation.Status.NOT_RECORDED
                :operatorClassificationConfirmation.statusFor(product);
        boolean operatorClassificationConfirmed=operatorClassificationStatus
                ==FoodSafetyProductClassificationConfirmation.Status.CURRENT_OPERATOR_CONFIRMED;
        // Signed classification evidence binds the category only. The current source-backed operator record
        // is still required to bind declared temperature and the narrow product profile to this exact revision.
        boolean classificationEstablished=operatorClassificationConfirmed;
        if (!classificationEstablished) {
            if (operatorClassificationConfirmation==null) reasons.add(classificationReason(classificationVerification));
            else if (operatorClassificationConfirmation.statusFor(product)==FoodSafetyProductClassificationConfirmation.Status.STALE)
                reasons.add(ReasonCode.PRODUCT_CLASSIFICATION_OPERATOR_CONFIRMATION_STALE);
            else reasons.add(ReasonCode.PRODUCT_CLASSIFICATION_MISSING);
        }
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
        if (policy!=null) {
            if (policy.productCategory()!=product.data().category()) reasons.add(ReasonCode.POLICY_SCOPE_MISMATCH);
            if (policy.temperatureClass()!=product.data().temperatureClass()) reasons.add(ReasonCode.POLICY_CLASS_MISMATCH);
        }
        boolean denied=reasons.stream().anyMatch(ReasonCode::hardDenial);
        boolean allowed=!denied && reasons.isEmpty() && RULESET.supports(product.data().category(),product.data().temperatureClass(),profile,sourceType,operation)
                && classificationEstablished;
        boolean classificationReview=reasons.stream().anyMatch(reason -> reason.name().startsWith("PRODUCT_CLASSIFICATION_"));
        var next=denied?NextAction.DO_NOT_EXECUTE
                :reasons.stream().anyMatch(LOT_REVIEW_REASONS::contains)
                    ?NextAction.REVIEW_LOT_EVIDENCE:classificationReview?NextAction.CONFIRM_PRODUCT_CLASSIFICATION
                    :zone==null?NextAction.RECORD_STORAGE_CONTEXT
                    :reasons.contains(ReasonCode.PLATFORM_RULESET_SCOPE_UNSUPPORTED)?NextAction.REVIEW_RULESET_SCOPE
                    :allowed?NextAction.NO_ADDITIONAL_ACTION:NextAction.REVIEW_LOT_EVIDENCE;
        var assessmentBasis=AssessmentBasis.PLATFORM_RULESET;
        return new FoodSafetyEvaluation(denied?Decision.DENIED:allowed?Decision.ALLOWED:Decision.REQUIRES_REVIEW,reasons.stream().toList(),operation,lot.id(),lot.version(),
                product.id(),product.version(),store.id(),store.version(),zone==null?null:zone.id(),zone==null?null:zone.version(),
                context.location()==null?null:context.location().id(),context.location()==null?null:context.location().version(),
                product.data().category(),product.data().temperatureClass(),
                policy==null?null:new PolicyReference(policy.id(),policy.profileId(),policy.productCategory(),policy.version()),approvalVerification,classificationVerification,
                operatorClassificationConfirmation,
                operatorClassificationStatus,
                evaluatedAt,localDate,store.data().zoneId(),new EvidenceAssessment(date,recall(lot.recallState()),damage(lot.damageState()),storage,
                        policy==null?PolicyEvidenceStatus.PLATFORM_RULESET:approvalVerification!=null && approvalVerification.authorizes(operation)
                                ?PolicyEvidenceStatus.INDEPENDENTLY_VERIFIED:PolicyEvidenceStatus.RECORDED_UNVERIFIED,
                        operatorClassificationConfirmed?ProductClassificationEvidenceStatus.OPERATOR_CONFIRMED
                                :externalClassificationVerified?ProductClassificationEvidenceStatus.INDEPENDENTLY_VERIFIED
                                :operatorClassificationConfirmation==null
                                        && (classificationVerification==null || classificationVerification.outcome()==FoodSafetyProductClassificationVerification.Outcome.NOT_SUBMITTED)
                                    ?ProductClassificationEvidenceStatus.MISSING:ProductClassificationEvidenceStatus.RECORDED_UNVERIFIED,
                        lot.data().manufacturerProvenance(),lot.data().dateMarking().provenance().orElse(null),lot.statusProvenance()),next,VERSION,
                assessmentBasis,RULESET.id(),RULESET.digestHex(),allowed?FoodSafetyEvaluation.ALLOWED_NOTICE:null);
    }
    private ReasonCode classificationReason(FoodSafetyProductClassificationVerification verification) {
        if (verification==null) return ReasonCode.PRODUCT_CLASSIFICATION_MISSING;
        return switch (verification.outcome()) {
            case NOT_SUBMITTED -> ReasonCode.PRODUCT_CLASSIFICATION_MISSING;
            case TRUST_REGISTRY_DISABLED -> ReasonCode.PRODUCT_CLASSIFICATION_TRUST_DISABLED;
            case TRUSTED_ISSUER_UNKNOWN -> ReasonCode.PRODUCT_CLASSIFICATION_ISSUER_UNTRUSTED;
            case TRUSTED_KEY_DISABLED -> ReasonCode.PRODUCT_CLASSIFICATION_KEY_DISABLED;
            case SIGNATURE_INVALID -> ReasonCode.PRODUCT_CLASSIFICATION_SIGNATURE_INVALID;
            case NOT_YET_VALID -> ReasonCode.PRODUCT_CLASSIFICATION_NOT_YET_VALID;
            case EXPIRED -> ReasonCode.PRODUCT_CLASSIFICATION_EXPIRED;
            case REVOKED -> ReasonCode.PRODUCT_CLASSIFICATION_REVOKED;
            case PRODUCT_SCOPE_MISMATCH -> ReasonCode.PRODUCT_CLASSIFICATION_SCOPE_MISMATCH;
            // A valid category signature does not establish the separately required declared temperature and profile.
            case VERIFIED -> ReasonCode.PRODUCT_CLASSIFICATION_MISSING;
        };
    }
    private DateEvidenceStatus assessDate(DateMarking marking,LocalDate localDate,EnumSet<ReasonCode> reasons) {
        var assessment=DateLabelAssessment.assess(marking);
        for (var reason:assessment.reasons()) reasons.add(switch(reason) {
            case MISSING_LABEL -> ReasonCode.DATE_LABEL_MISSING;
            case UNKNOWN_TYPE -> ReasonCode.DATE_TYPE_UNKNOWN;
            case UNKNOWN_PRECISION -> ReasonCode.DATE_PRECISION_UNKNOWN;
            case MISSING_PROVENANCE -> ReasonCode.DATE_PROVENANCE_MISSING;
            case MISSING_REFERENCE -> ReasonCode.DATE_REFERENCE_MISSING;
            case USE_BY_REQUIRES_DAY -> ReasonCode.USE_BY_DAY_REQUIRED;
            case BEST_BEFORE_END_REQUIRES_PERIOD -> ReasonCode.DATE_TYPE_PRECISION_MISMATCH;
            case UNRECOGNIZED_FORMAT, NONEXISTENT_DATE -> ReasonCode.DATE_LABEL_INVALID;
        });
        if (assessment.status()==DateLabelAssessment.Status.VALID && marking.precision()==DatePrecision.DAY
                && (marking.type()==DateMarkingType.USE_BY || marking.type()==DateMarkingType.BEST_BEFORE)) {
            try {
                var labelDate=LocalDate.parse(marking.labeledValue().strip());
                if (localDate.isAfter(labelDate)) reasons.add(marking.type()==DateMarkingType.USE_BY?ReasonCode.USE_BY_EXPIRED:ReasonCode.BEST_BEFORE_EXPIRED);
            } catch (DateTimeException malformed) {
                reasons.add(ReasonCode.DATE_LABEL_INVALID);
                return DateEvidenceStatus.INVALID;
            }
        }
        boolean partial=marking.precision()==DatePrecision.MONTH || marking.precision()==DatePrecision.YEAR;
        if (partial) reasons.add(ReasonCode.PARTIAL_DATE_RULE_UNVERIFIED);
        return assessment.status()==DateLabelAssessment.Status.INVALID?DateEvidenceStatus.INVALID
                :assessment.status()==DateLabelAssessment.Status.INCOMPLETE || reasons.contains(ReasonCode.DATE_REFERENCE_MISSING)?DateEvidenceStatus.INCOMPLETE
                :partial?DateEvidenceStatus.PARTIAL_REQUIRES_VERIFIED_RULE:DateEvidenceStatus.COMPLETE_LABEL;
    }
    private StatusEvidenceStatus recall(RecallState value) { return switch(value) { case UNKNOWN -> StatusEvidenceStatus.UNKNOWN;case NOT_REPORTED -> StatusEvidenceStatus.NOT_REPORTED_IN_REVIEWED_EVIDENCE;case RECALLED -> StatusEvidenceStatus.BLOCK_RECORDED; }; }
    private StatusEvidenceStatus damage(DamageState value) { return switch(value) { case UNKNOWN -> StatusEvidenceStatus.UNKNOWN;case NOT_REPORTED -> StatusEvidenceStatus.NOT_REPORTED_IN_REVIEWED_EVIDENCE;case DAMAGED -> StatusEvidenceStatus.BLOCK_RECORDED; }; }
}
