package com.stockrescue.catalog.application;

import com.stockrescue.catalog.domain.ProductCategory;

/** Digest of the exact safety rule and scope selected in one immutable policy version. */
public final class FoodSafetyPolicyDigest {
    private FoodSafetyPolicyDigest() { }
    public static byte[] of(FoodSafetyPolicyVersion policy) {
        return CatalogCommandDigest.of("stockrescue-food-safety-policy-rules-v1",policy.productCategory().name(),
                policy.temperatureClass().name(),policy.partialDateRule().name());
    }
    public static String hex(FoodSafetyPolicyVersion policy) {
        return java.util.HexFormat.of().formatHex(of(policy));
    }
}
