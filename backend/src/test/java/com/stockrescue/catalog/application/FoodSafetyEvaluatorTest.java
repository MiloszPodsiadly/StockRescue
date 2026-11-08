package com.stockrescue.catalog.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.*;
import com.stockrescue.catalog.application.FoodSafetyEvaluator.Context;
import com.stockrescue.catalog.application.FoodSafetyPolicyVersion.*;
import com.stockrescue.catalog.application.ManufacturerLot.*;
import com.stockrescue.catalog.domain.*;
import com.stockrescue.stores.application.OperationalConfiguration.*;
import com.stockrescue.stores.application.Store;
import com.stockrescue.stores.domain.StoreData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FoodSafetyEvaluatorTest {
    private static final Instant NOW=Instant.parse("2026-11-05T12:00:00Z");
    private final FoodSafetyEvaluator evaluator=new FoodSafetyEvaluator();

    @Test
    void useByRemainsWithinItsLocalLabelDayAndExpiresAtStoreMidnight() {
        var warsaw=context("2026-11-05",DateMarkingType.USE_BY,DatePrecision.DAY,"Europe/Warsaw",TemperatureClass.AMBIENT);
        var losAngeles=context("2026-11-05",DateMarkingType.USE_BY,DatePrecision.DAY,"America/Los_Angeles",TemperatureClass.AMBIENT);
        var instant=Instant.parse("2026-11-05T23:00:00Z");
        var expired=evaluator.evaluate(warsaw,Operation.SALE,instant);
        assertThat(expired.decision()).isEqualTo(Decision.DENIED);
        assertThat(expired.reasonCodes()).contains(ReasonCode.USE_BY_EXPIRED);
        assertThat(expired.storeLocalDate().toString()).isEqualTo("2026-11-06");
        assertThat(evaluator.evaluate(warsaw,Operation.SALE,instant.minusNanos(1)).reasonCodes()).doesNotContain(ReasonCode.USE_BY_EXPIRED);
        var sameInstant=evaluator.evaluate(losAngeles,Operation.SALE,instant);
        assertThat(sameInstant.storeLocalDate().toString()).isEqualTo("2026-11-05");
        assertThat(sameInstant.decision()).isEqualTo(Decision.REQUIRES_REVIEW);
        assertThat(sameInstant.reasonCodes()).doesNotContain(ReasonCode.USE_BY_EXPIRED);
    }

    @Test
    void daylightSavingTransitionsUseActualZoneRulesForTheLastLabelDay() {
        for (var transition:new String[][]{
                {"2027-03-28","2027-03-28T00:59:59Z","2027-03-28T01:00:00Z","2027-03-28T21:59:59Z","2027-03-28T22:00:00Z"},
                {"2027-10-31","2027-10-31T00:59:59Z","2027-10-31T01:00:00Z","2027-10-31T22:59:59Z","2027-10-31T23:00:00Z"}}) {
            var context=context(transition[0],DateMarkingType.USE_BY,DatePrecision.DAY,"Europe/Warsaw",TemperatureClass.AMBIENT);
            for (int index=1;index<4;index++) assertThat(evaluator.evaluate(context,Operation.TRANSFER,Instant.parse(transition[index])).reasonCodes()).doesNotContain(ReasonCode.USE_BY_EXPIRED);
            assertThat(evaluator.evaluate(context,Operation.TRANSFER,Instant.parse(transition[4])).reasonCodes()).contains(ReasonCode.USE_BY_EXPIRED);
        }
    }

    @Test
    void pastBestBeforeIsDeniedForBothPilotOperationsWithoutUseByRelabeling() {
        for (var type:new DateMarkingType[]{DateMarkingType.BEST_BEFORE}) for (var operation:Operation.values()) {
            var result=evaluator.evaluate(context("2026-11-04",type,DatePrecision.DAY,"UTC",TemperatureClass.AMBIENT),operation,NOW);
            assertThat(result.decision()).isEqualTo(Decision.DENIED);
            assertThat(result.reasonCodes()).contains(ReasonCode.BEST_BEFORE_EXPIRED).doesNotContain(ReasonCode.USE_BY_EXPIRED);
        }
    }

    @Test
    void bestBeforeEndWithDayPrecisionCannotBecomeCompleteOrTriggerExpiry() {
        var result=evaluator.evaluate(context("2026-11-04",DateMarkingType.BEST_BEFORE_END,DatePrecision.DAY,"UTC",TemperatureClass.AMBIENT),
                Operation.SALE,NOW);
        assertThat(result.evidence().date()).isEqualTo(DateEvidenceStatus.INCOMPLETE);
        assertThat(result.decision()).isEqualTo(Decision.REQUIRES_REVIEW);
        assertThat(result.reasonCodes()).contains(ReasonCode.DATE_TYPE_PRECISION_MISMATCH);
        assertThat(result.reasonCodes()).doesNotContain(ReasonCode.BEST_BEFORE_EXPIRED,ReasonCode.USE_BY_EXPIRED);
    }

    @Test
    void unreferencedDayLabelCannotBeUsedAsAnExpiryDecision() {
        var context=context("2026-11-04",DateMarkingType.USE_BY,DatePrecision.DAY,"UTC",TemperatureClass.AMBIENT,
                ProductCategory.UNCLASSIFIED,Optional.of(new EvidenceProvenance("Observed label",null)));
        var result=evaluator.evaluate(context,Operation.SALE,NOW);
        assertThat(result.evidence().date()).isEqualTo(DateEvidenceStatus.INCOMPLETE);
        assertThat(result.decision()).isEqualTo(Decision.REQUIRES_REVIEW);
        assertThat(result.reasonCodes()).contains(ReasonCode.DATE_REFERENCE_MISSING).doesNotContain(ReasonCode.USE_BY_EXPIRED);
    }

    @Test
    void partialUnknownAndInvalidLabelsCannotAcquireAnInventedCutoff() {
        for (var example:new Object[][]{
                {"2027-02",DateMarkingType.BEST_BEFORE_END,DatePrecision.MONTH,DateEvidenceStatus.PARTIAL_REQUIRES_VERIFIED_RULE},
                {"2027",DateMarkingType.BEST_BEFORE_END,DatePrecision.YEAR,DateEvidenceStatus.PARTIAL_REQUIRES_VERIFIED_RULE},
                {"2027-02",DateMarkingType.USE_BY,DatePrecision.MONTH,DateEvidenceStatus.INCOMPLETE},
                {"2027-02-29",DateMarkingType.USE_BY,DatePrecision.DAY,DateEvidenceStatus.INVALID},
                {"01/02/2027",DateMarkingType.BEST_BEFORE,DatePrecision.DAY,DateEvidenceStatus.INVALID},
                {null,DateMarkingType.UNKNOWN,DatePrecision.UNKNOWN,DateEvidenceStatus.INCOMPLETE}}) {
            var context=context((String)example[0],(DateMarkingType)example[1],(DatePrecision)example[2],"UTC",TemperatureClass.AMBIENT);
            var result=evaluator.evaluate(context,Operation.SALE,NOW);
            assertThat(result.decision()).isEqualTo(Decision.REQUIRES_REVIEW);
            assertThat(result.evidence().date()).isEqualTo(example[3]);
            assertThat(context.lot().data().dateMarking().normalizedCutoff()).isEmpty();
            assertThat(result.reasonCodes()).doesNotContain(ReasonCode.USE_BY_EXPIRED,ReasonCode.BEST_BEFORE_EXPIRED);
        }
    }

    @Test
    void aRequestedPeriodEndRuleIsUnverifiedEvenWhenAnAdministratorRecordedReviewEvidence() {
        var original=context("2027-02",DateMarkingType.BEST_BEFORE_END,DatePrecision.MONTH,"UTC",TemperatureClass.AMBIENT);
        var policy=new FoodSafetyPolicyVersion(UUID.randomUUID(),UUID.randomUUID(),ProductCategory.UNCLASSIFIED,TemperatureClass.AMBIENT,1,PartialDateRule.END_OF_LABELED_PERIOD,
                ApprovalStatus.UNVERIFIED,source(),UUID.randomUUID(),NOW,"Requested rule reviewed");
        var result=evaluator.evaluate(new Context(original.lot(),original.product(),original.store(),original.zone(),policy),Operation.SALE,NOW);
        assertThat(result.reasonCodes()).contains(ReasonCode.PARTIAL_DATE_RULE_UNVERIFIED,ReasonCode.PLATFORM_RULESET_SCOPE_UNSUPPORTED)
                .doesNotContain(ReasonCode.POLICY_APPROVAL_UNVERIFIED);
        assertThat(result.policy().id()).isEqualTo(policy.id());
        assertThat(result.decision()).isEqualTo(Decision.REQUIRES_REVIEW);
    }

    @Test
    void recallAndDamageDenialsPrecedeAllIncompleteEvidenceInDeterministicOrder() {
        var context=context(null,DateMarkingType.UNKNOWN,DatePrecision.UNKNOWN,"UTC",TemperatureClass.AMBIENT);
        var lot=context.lot();
        var blocked=new ManufacturerLot(lot.id(),lot.data(),lot.confirmedByMembershipId(),lot.confirmedAt(),RecallState.RECALLED,DamageState.DAMAGED,source(),true,1,NOW,NOW,null);
        var result=evaluator.evaluate(new Context(blocked,context.product(),context.store(),null,null),Operation.TRANSFER,NOW);
        assertThat(result.decision()).isEqualTo(Decision.DENIED);
        assertThat(result.reasonCodes()).startsWith(ReasonCode.LOT_RECALLED,ReasonCode.LOT_DAMAGED);
        assertThat(result.reasonCodes()).contains(ReasonCode.DATE_LABEL_MISSING,ReasonCode.STORAGE_CONTEXT_MISSING);
        assertThat(result.nextAction()).isEqualTo(NextAction.DO_NOT_EXECUTE);
    }

    @Test
    void unknownStorageRequiresReviewWhileAnIncompatibleOrArchivedZoneDenies() {
        var context=context("2027-04-30",DateMarkingType.USE_BY,DatePrecision.DAY,"UTC",TemperatureClass.CHILLED);
        assertThat(evaluator.evaluate(new Context(context.lot(),context.product(),context.store(),null,null),Operation.SALE,NOW).reasonCodes())
                .contains(ReasonCode.STORAGE_CONTEXT_MISSING,ReasonCode.COLD_CHAIN_EVIDENCE_UNVERIFIED);
        var incompatible=new Zone(UUID.randomUUID(),"Shelf",TemperatureClass.AMBIENT,true,0,NOW,NOW);
        var result=evaluator.evaluate(new Context(context.lot(),context.product(),context.store(),incompatible,null),Operation.SALE,NOW);
        assertThat(result.decision()).isEqualTo(Decision.DENIED);
        assertThat(result.evidence().storage()).isEqualTo(StorageEvidenceStatus.DECLARED_INCOMPATIBLE);
        var archived=new Zone(UUID.randomUUID(),"Cooler",TemperatureClass.CHILLED,false,1,NOW,NOW);
        assertThat(evaluator.evaluate(new Context(context.lot(),context.product(),context.store(),archived,null),Operation.SALE,NOW).reasonCodes()).contains(ReasonCode.ZONE_ARCHIVED);
    }

    @Test
    void independentCategoryEvidenceAloneCannotConfirmTemperatureOrProductProfile() {
        var context=context("2027-04-30",DateMarkingType.USE_BY,DatePrecision.DAY,"UTC",TemperatureClass.AMBIENT,ProductCategory.BAKERY);
        var result=evaluator.evaluate(context,Operation.SALE,NOW,null,verifiedClassification(context.product()));

        assertThat(result.decision()).isEqualTo(Decision.REQUIRES_REVIEW);
        assertThat(result.reasonCodes()).contains(ReasonCode.PRODUCT_CLASSIFICATION_MISSING);
        assertThat(result.approvalVerification()).isNull();
        assertThat(result.assessmentBasis()).isEqualTo(AssessmentBasis.PLATFORM_RULESET);
        assertThat(result.rulesetId()).isEqualTo(FoodSafetyRuleset.CURRENT_ID);
        assertThat(result.rulesetDigest()).matches("[0-9a-f]{64}");
        assertThat(result.assessmentNotice()).isNull();
    }

    @Test
    void operatorClassificationIsAttributedAndOnlyAppliesToItsExactProductVersion() {
        var original=context("2027-04-30",DateMarkingType.USE_BY,DatePrecision.DAY,"UTC",TemperatureClass.AMBIENT,ProductCategory.BAKERY);
        var product=original.product();
        var confirmedProduct=new Product(product.id(),product.data(),true,1,product.createdAt(),NOW,null);
        var confirmation=new FoodSafetyProductClassificationConfirmation(UUID.randomUUID(),UUID.randomUUID(),product.id(),
                product.data().sku().value(),0,1,ProductCategory.BAKERY,ProductCategory.BAKERY,
                TemperatureClass.AMBIENT,TemperatureClass.AMBIENT,
                FoodSafetyProductClassificationConfirmation.ProductProfile.PACKAGED_SHELF_STABLE_DRY_BAKERY,
                FoodSafetyProductClassificationConfirmation.SourceType.ORIGINAL_PACKAGE,"package:category-label",
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Reviewed the package category",new byte[32],NOW);
        var current=evaluator.evaluate(new Context(original.lot(),confirmedProduct,original.store(),original.zone(),null),Operation.SALE,NOW,
                null,null,confirmation);
        assertThat(current.decision()).isEqualTo(Decision.ALLOWED);
        assertThat(current.operatorClassificationStatus()).isEqualTo(FoodSafetyProductClassificationConfirmation.Status.CURRENT_OPERATOR_CONFIRMED);
        assertThat(current.evidence().classification()).isEqualTo(ProductClassificationEvidenceStatus.OPERATOR_CONFIRMED);
        assertThat(current.assessmentNotice()).isEqualTo(FoodSafetyEvaluation.ALLOWED_NOTICE);

        var changedTemperature=ProductData.parse(product.data().sku().value(),"Milk",null,"EA",ProductCategory.BAKERY,TemperatureClass.CHILLED);
        var correctedProduct=new Product(product.id(),changedTemperature,true,2,product.createdAt(),NOW,null);
        var corrected=evaluator.evaluate(new Context(original.lot(),correctedProduct,original.store(),original.zone(),null),Operation.SALE,NOW,
                null,null,confirmation);
        assertThat(corrected.decision()).isEqualTo(Decision.DENIED);
        assertThat(corrected.operatorClassificationStatus()).isEqualTo(FoodSafetyProductClassificationConfirmation.Status.STALE);
        assertThat(corrected.reasonCodes()).contains(ReasonCode.PRODUCT_CLASSIFICATION_OPERATOR_CONFIRMATION_STALE,
                ReasonCode.PLATFORM_RULESET_SCOPE_UNSUPPORTED,ReasonCode.COLD_CHAIN_EVIDENCE_UNVERIFIED,ReasonCode.TEMPERATURE_CLASS_MISMATCH);

        var laterProduct=new Product(product.id(),product.data(),true,2,product.createdAt(),NOW,null);
        var stale=evaluator.evaluate(new Context(original.lot(),laterProduct,original.store(),original.zone(),null),Operation.SALE,NOW,
                null,null,confirmation);
        assertThat(stale.decision()).isEqualTo(Decision.REQUIRES_REVIEW);
        assertThat(stale.operatorClassificationStatus()).isEqualTo(FoodSafetyProductClassificationConfirmation.Status.STALE);
        assertThat(stale.reasonCodes()).contains(ReasonCode.PRODUCT_CLASSIFICATION_OPERATOR_CONFIRMATION_STALE);
    }

    @Test
    void onlyExplicitShelfStableDryBakeryEvidenceFromAnOriginalPackageOrSupplierDocumentPasses() {
        var original=context("2027-04-30",DateMarkingType.USE_BY,DatePrecision.DAY,"UTC",TemperatureClass.AMBIENT,ProductCategory.BAKERY);
        var before=original.product();
        var current=new Product(before.id(),before.data(),true,1,before.createdAt(),NOW,null);
        var profiles=new FoodSafetyProductClassificationConfirmation.ProductProfile[]{
                FoodSafetyProductClassificationConfirmation.ProductProfile.NOT_ASSESSED,
                FoodSafetyProductClassificationConfirmation.ProductProfile.REFRIGERATION_REQUIRED,
                FoodSafetyProductClassificationConfirmation.ProductProfile.CREAM_FILLED_OR_PERISHABLE,
                FoodSafetyProductClassificationConfirmation.ProductProfile.NOT_SHELF_STABLE,
                FoodSafetyProductClassificationConfirmation.ProductProfile.STORAGE_INSTRUCTION_MISSING_OR_AMBIGUOUS,
                FoodSafetyProductClassificationConfirmation.ProductProfile.CONTRADICTORY_STORAGE_EVIDENCE,
                FoodSafetyProductClassificationConfirmation.ProductProfile.OUTSIDE_CURRENT_PILOT};

        for (var profile:profiles) {
            var confirmation=operatorConfirmation(before,current,profile,FoodSafetyProductClassificationConfirmation.SourceType.ORIGINAL_PACKAGE);
            var result=evaluator.evaluate(new Context(original.lot(),current,original.store(),original.zone(),null),Operation.SALE,NOW,
                    null,null,confirmation);
            assertThat(result.decision()).as(profile.name()).isEqualTo(Decision.REQUIRES_REVIEW);
            assertThat(result.reasonCodes()).as(profile.name()).contains(ReasonCode.PLATFORM_RULESET_SCOPE_UNSUPPORTED);
        }

        var supported=FoodSafetyProductClassificationConfirmation.ProductProfile.PACKAGED_SHELF_STABLE_DRY_BAKERY;
        for (var sourceType:new FoodSafetyProductClassificationConfirmation.SourceType[]{
                FoodSafetyProductClassificationConfirmation.SourceType.ORIGINAL_PACKAGE,
                FoodSafetyProductClassificationConfirmation.SourceType.SUPPLIER_DOCUMENT}) {
            var confirmation=operatorConfirmation(before,current,supported,sourceType);
            var result=evaluator.evaluate(new Context(original.lot(),current,original.store(),original.zone(),null),Operation.SALE,NOW,
                    null,null,confirmation);
            assertThat(result.decision()).as(sourceType.name()).isEqualTo(Decision.ALLOWED);
        }

        var nonProductEvidence=operatorConfirmation(before,current,supported,
                FoodSafetyProductClassificationConfirmation.SourceType.OTHER);
        var outside=evaluator.evaluate(new Context(original.lot(),current,original.store(),original.zone(),null),Operation.SALE,NOW,
                null,null,nonProductEvidence);
        assertThat(outside.decision()).isEqualTo(Decision.REQUIRES_REVIEW);
        assertThat(outside.reasonCodes()).contains(ReasonCode.PLATFORM_RULESET_SCOPE_UNSUPPORTED);
    }

    private FoodSafetyProductClassificationConfirmation operatorConfirmation(Product before,Product after,
            FoodSafetyProductClassificationConfirmation.ProductProfile profile,
            FoodSafetyProductClassificationConfirmation.SourceType sourceType) {
        return new FoodSafetyProductClassificationConfirmation(UUID.randomUUID(),UUID.randomUUID(),before.id(),
                before.data().sku().value(),before.version(),after.version(),before.data().category(),after.data().category(),
                before.data().temperatureClass(),after.data().temperatureClass(),profile,sourceType,"package:storage-and-product-label",
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Reviewed source instructions",new byte[32],NOW);
    }

    @Test
    void unsupportedCategoryAndTemperatureNeverFallBackToThePlatformRuleset() {
        for (var pair:new Object[][]{{ProductCategory.DAIRY,TemperatureClass.AMBIENT},{ProductCategory.BAKERY,TemperatureClass.CHILLED},
                {ProductCategory.BAKERY,TemperatureClass.FROZEN}}) {
            var context=context("2027-04-30",DateMarkingType.USE_BY,DatePrecision.DAY,"UTC",(TemperatureClass)pair[1],(ProductCategory)pair[0]);
            var result=evaluator.evaluate(context,Operation.SALE,NOW,null,verifiedClassification(context.product()));
            assertThat(result.decision()).isEqualTo(Decision.REQUIRES_REVIEW);
            assertThat(result.reasonCodes()).contains(ReasonCode.PLATFORM_RULESET_SCOPE_UNSUPPORTED);
        }
    }

    @Test
    void aPolicyForAnotherProductCategoryCannotApplyAtTheSameTemperatureClass() {
        var productContext=context("2027-04-30",DateMarkingType.USE_BY,DatePrecision.DAY,"UTC",TemperatureClass.CHILLED,ProductCategory.DAIRY);
        var bakeryPolicy=new FoodSafetyPolicyVersion(UUID.randomUUID(),UUID.randomUUID(),ProductCategory.BAKERY,TemperatureClass.CHILLED,1,
                PartialDateRule.NO_NORMALIZATION,ApprovalStatus.UNVERIFIED,source(),UUID.randomUUID(),NOW,"Separate bakery scope");
        var result=evaluator.evaluate(new Context(productContext.lot(),productContext.product(),productContext.store(),productContext.zone(),bakeryPolicy),Operation.SALE,NOW);
        assertThat(result.decision()).isEqualTo(Decision.REQUIRES_REVIEW);
        assertThat(result.reasonCodes()).contains(ReasonCode.POLICY_SCOPE_MISMATCH,ReasonCode.PLATFORM_RULESET_SCOPE_UNSUPPORTED)
                .doesNotContain(ReasonCode.POLICY_APPROVAL_UNVERIFIED);
        assertThat(result.policy().productCategory()).isEqualTo(ProductCategory.BAKERY);
    }

    @Test
    void anUnclassifiedProductCannotBecomeAllowedEvenWithAValidScopedAttestation() {
        var original=context("2027-04-30",DateMarkingType.USE_BY,DatePrecision.DAY,"UTC",TemperatureClass.AMBIENT);
        var policy=new FoodSafetyPolicyVersion(UUID.randomUUID(),UUID.randomUUID(),ProductCategory.UNCLASSIFIED,TemperatureClass.AMBIENT,1,
                PartialDateRule.NO_NORMALIZATION,ApprovalStatus.UNVERIFIED,source(),UUID.randomUUID(),NOW,"Unclassified scope review");
        var verified=new FoodSafetyPolicyApprovalVerification(FoodSafetyPolicyApprovalVerification.Outcome.VERIFIED,1,
                FoodSafetyPolicyDigest.hex(policy),"test-issuer","test-key",java.util.List.of(Operation.SALE),NOW,NOW,NOW.plusSeconds(60),
                "test-evidence",NOW,false,null,null,null,2,FoodSafetyRuleset.CURRENT_ID,new FoodSafetyRuleset().digestHex(),
                FoodSafetyRuleset.CURRENT_ID,new FoodSafetyRuleset().digestHex());
        var result=evaluator.evaluate(new Context(original.lot(),original.product(),original.store(),original.zone(),policy),Operation.SALE,NOW,verified);
        assertThat(result.decision()).isEqualTo(Decision.REQUIRES_REVIEW);
        assertThat(result.reasonCodes()).contains(ReasonCode.PRODUCT_CATEGORY_UNCLASSIFIED);
    }

    @Test
    void archivedProductRemainsDeniedWhenItsClassificationEvidenceIsVerified() {
        var original=context("2027-04-30",DateMarkingType.USE_BY,DatePrecision.DAY,"UTC",TemperatureClass.AMBIENT,ProductCategory.DAIRY);
        var current=original.product();
        var archived=new Product(current.id(),current.data(),false,current.version()+1,NOW.minusSeconds(30),NOW,null);
        var classification=new FoodSafetyProductClassificationVerification(FoodSafetyProductClassificationVerification.Outcome.VERIFIED,
                UUID.randomUUID(),archived.id(),UUID.randomUUID(),archived.data().sku().value(),ProductCategory.DAIRY,archived.version(),
                ProductCategory.DAIRY,archived.version(),"test-issuer","test-key",NOW.minusSeconds(30),NOW.minusSeconds(20),
                NOW.plusSeconds(60),"test-evidence",NOW,false,null,null,null);

        var result=evaluator.evaluate(new Context(original.lot(),archived,original.store(),original.zone(),null),Operation.SALE,NOW,null,classification);

        assertThat(result.decision()).isEqualTo(Decision.DENIED);
        assertThat(result.reasonCodes()).contains(ReasonCode.PRODUCT_ARCHIVED);
    }

    private Context context(String label,DateMarkingType type,DatePrecision precision,String zoneId,TemperatureClass temperature) {
        return context(label,type,precision,zoneId,temperature,ProductCategory.UNCLASSIFIED);
    }
    private Context context(String label,DateMarkingType type,DatePrecision precision,String zoneId,TemperatureClass temperature,ProductCategory category) {
        return context(label,type,precision,zoneId,temperature,category,Optional.of(source()));
    }
    private Context context(String label,DateMarkingType type,DatePrecision precision,String zoneId,TemperatureClass temperature,
            ProductCategory category,Optional<EvidenceProvenance> provenance) {
        var product=new Product(UUID.randomUUID(),ProductData.parse("MILK","Milk",null,"EA",category,temperature),true,0,NOW,NOW,null);
        var marking=new DateMarking(label,type,precision,provenance,Optional.empty());
        var lot=new ManufacturerLot(UUID.randomUUID(),new LotData(product.id(),"LOT",LotIdentityState.CONFIRMED,"maker",source(),true,marking),
                UUID.randomUUID(),NOW,RecallState.NOT_REPORTED,DamageState.NOT_REPORTED,source(),true,0,NOW,NOW,null);
        var store=new Store(UUID.randomUUID(),new StoreData("NORTH","North",null,null,null,null,zoneId),true,0,NOW,NOW);
        return new Context(lot,product,store,new Zone(UUID.randomUUID(),"Declared zone",temperature,true,0,NOW,NOW),null);
    }
    private EvidenceProvenance source() { return new EvidenceProvenance("Observed original document","document-17"); }
    private FoodSafetyProductClassificationVerification verifiedClassification(Product product) {
        var category=product.data().category();
        return new FoodSafetyProductClassificationVerification(FoodSafetyProductClassificationVerification.Outcome.VERIFIED,
                UUID.randomUUID(),product.id(),UUID.randomUUID(),product.data().sku().value(),category,product.version(),category,product.version(),
                "test-issuer","test-key",NOW,NOW,NOW.plusSeconds(3600),"classification:source-17",NOW,false,null,null,null);
    }
}
