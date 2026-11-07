package com.stockrescue.catalog.application;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Shared Ed25519 verification against deployment-owned issuer trust for distinct evidence domains. */
@Component
public final class FoodSafetySignatureVerifier {
    public enum Outcome { TRUST_REGISTRY_DISABLED, TRUSTED_ISSUER_UNKNOWN, TRUSTED_KEY_DISABLED, SIGNATURE_INVALID, VERIFIED }
    private final FoodSafetyApprovalProperties properties;

    public FoodSafetySignatureVerifier(FoodSafetyApprovalProperties properties) { this.properties=properties; }

    public Outcome verify(String issuerId,String keyId,byte[] canonicalPayload,byte[] signature) {
        if (!properties.isEnabled()) return Outcome.TRUST_REGISTRY_DISABLED;
        var trusted=properties.getTrustedIssuers().get(keyId);
        if (trusted==null || trusted.getIssuerId()==null || !trusted.getIssuerId().equals(issuerId)) return Outcome.TRUSTED_ISSUER_UNKNOWN;
        if (!trusted.isEnabled()) return Outcome.TRUSTED_KEY_DISABLED;
        return signatureValid(canonicalPayload,signature,trusted.getPublicKeyX509())?Outcome.VERIFIED:Outcome.SIGNATURE_INVALID;
    }

    private boolean signatureValid(byte[] canonicalPayload,byte[] signature,String encodedKey) {
        if (encodedKey==null || encodedKey.isBlank()) return false;
        try {
            var keyBytes=Base64.getDecoder().decode(encodedKey);
            var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
            var verifier=Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(canonicalPayload);
            return verifier.verify(signature);
        } catch (IllegalArgumentException | GeneralSecurityException invalidConfigurationOrSignature) {
            return false;
        }
    }
}
