package com.stockrescue.catalog.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.Operation;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Immutable, reviewable identity for the platform's bounded food-safety checks. */
@Component
public final class FoodSafetyRuleset {
    public static final String CURRENT_ID="food-safety-bakery-ambient-dry-packaged-v3";
    private static final String CURRENT_MANIFEST=String.join("\n",
            "scope=BAKERY/AMBIENT/PACKAGED_SHELF_STABLE_DRY_BAKERY-only;all-other-category-temperature-profile-pairs-require-review",
            "profile=operator-confirms-sealed-packaged-shelf-stable-dry-bakery-from-original-package-or-supplier-document;uncertain-or-perishable-products-require-review",
            "operations=SALE,TRANSFER-as-assessment-context-only;never-operation-authorization",
            "category=exact-current-product-category;UNCLASSIFIED-and-unsupported-categories-never-allow",
            "temperature=AMBIENT-product-and-active-AMBIENT-storage-only",
            "dates=original-source-attributed-valid-day-precision-USE_BY-or-BEST_BEFORE",
            "date-zone=store-local-calendar-date;DST-from-IANA-zone-rules",
            "partial-dates=month-and-year-remain-review;never-infer-a-cutoff",
            "lot=manufacturer-identity-confirmed;original-date-label-and-referenced-provenance-required",
            "status=recall-and-damage-unknown-require-review;NOT_REPORTED-is-only-what-the-reviewed-evidence-says",
            "blocks=recalled,damaged,archived-lot,product,store,zone,location,expired-date,temperature-mismatch",
            "storage=active-AMBIENT-zone-must-match-product-temperature;non-ambient-cold-chain-evidence-unverified",
            "classification=source-attributed-current-operator-confirmation-of-category-temperature-profile-required",
            "missing-evidence=fail-closed-to-review;hard-blocks-take-precedence",
            "tenant-policy=historical-review-only;never-approves-or-weakens-or-replaces-this-platform-ruleset",
            "result=limited-point-in-time-evidence-check;not-certification-or-permission-to-sell-donate-transfer-or-move-stock");

    private final String id;
    private final String manifest;
    private final String digestHex;

    @Autowired
    public FoodSafetyRuleset() {
        this(CURRENT_ID,CURRENT_MANIFEST);
    }

    /** Explicit construction also lets compatibility tests evaluate persisted evidence under another ruleset. */
    public FoodSafetyRuleset(String id,String manifest) {
        if (id==null || !id.matches("[A-Za-z0-9._:-]{1,96}") || manifest==null || manifest.isBlank())
            throw new IllegalArgumentException("Invalid food-safety ruleset identity");
        this.id=id;
        this.manifest=manifest;
        this.digestHex=digest(manifest);
    }

    public String id() { return id; }
    public String manifest() { return manifest; }
    public String digestHex() { return digestHex; }
    public byte[] digest() { return HexFormat.of().parseHex(digestHex); }

    public boolean supports(ProductCategory category,TemperatureClass temperatureClass,
            FoodSafetyProductClassificationConfirmation.ProductProfile productProfile,
            FoodSafetyProductClassificationConfirmation.SourceType sourceType,Operation operation) {
        return category==ProductCategory.BAKERY && temperatureClass==TemperatureClass.AMBIENT
                && productProfile==FoodSafetyProductClassificationConfirmation.ProductProfile.PACKAGED_SHELF_STABLE_DRY_BAKERY
                && (sourceType==FoodSafetyProductClassificationConfirmation.SourceType.ORIGINAL_PACKAGE
                    || sourceType==FoodSafetyProductClassificationConfirmation.SourceType.SUPPLIER_DOCUMENT)
                && (operation==Operation.SALE || operation==Operation.TRANSFER);
    }

    private static String digest(String manifest) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the runtime",impossible);
        }
    }
}
