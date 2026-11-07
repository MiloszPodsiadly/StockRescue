package com.stockrescue.catalog.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Immutable, reviewable identity for the semantics an external policy approval attests. */
@Component
public final class FoodSafetyRuleset {
    public static final String CURRENT_ID="food-safety-conservative-pilot-v1";
    private static final String CURRENT_MANIFEST=String.join("\n",
            "operations=SALE,TRANSFER",
            "category=exact-product-category-and-policy-category;UNCLASSIFIED-never-allows",
            "temperature=exact-product-policy-and-active-storage-zone-class",
            "dates=only-validated-day-precision-USE_BY-and-BEST_BEFORE-have-cutoffs",
            "date-zone=store-local-calendar-date;DST-from-IANA-zone-rules",
            "partial-dates=month-and-year-remain-review;never-infer-normalized-cutoff",
            "lot=manufacturer-identity-confirmed;date-label-and-referenced-provenance-required",
            "status=recall-and-damage-unknown-require-review;reported-status-needs-reference",
            "blocks=recalled,damaged,archived-lot,product,store,zone,location,expired-date,temperature-mismatch",
            "storage=active-zone-must-match-product-temperature;non-ambient-cold-chain-evidence-unverified",
            "classification=independent-product-category-attestation-for-exact-org-product-sku-category-version",
            "missing-evidence=fail-closed-to-review;hard-blocks-take-precedence",
            "result=point-in-time-metadata-assessment;never-an-executable-stock-operation-permission");

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

    public boolean matches(String signedId,byte[] signedDigest) {
        return Objects.equals(id,signedId) && signedDigest!=null
                && MessageDigest.isEqual(digest(),signedDigest);
    }

    private static String digest(String manifest) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the runtime",impossible);
        }
    }
}
