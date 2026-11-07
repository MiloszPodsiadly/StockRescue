package com.stockrescue.catalog.application;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.catalog.persistence.FoodSafetyProductClassificationRepository;
import com.stockrescue.catalog.persistence.ProductRepository;
import com.stockrescue.platform.audit.AuditWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Submission, request-time verification and revocation of product classification evidence. */
@Service
public class FoodSafetyProductClassificationService {
    public record Evidence(UUID attestationId,FoodSafetyProductClassificationVerification verification) { }
    public record Result(Evidence evidence,boolean created) { }

    private final AccessPolicy access;
    private final ProductRepository products;
    private final FoodSafetyProductClassificationRepository classifications;
    private final FoodSafetyProductClassificationVerifier verifier;
    private final AuditWriter audit;
    private final Clock clock;

    public FoodSafetyProductClassificationService(AccessPolicy access,ProductRepository products,
            FoodSafetyProductClassificationRepository classifications,FoodSafetyProductClassificationVerifier verifier,
            AuditWriter audit,Clock clock) {
        this.access=access;this.products=products;this.classifications=classifications;this.verifier=verifier;this.audit=audit;this.clock=clock;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Evidence evidence(UUID actor,UUID organizationId,UUID productId) {
        access.requireOrganizationAction(actor,organizationId,OrganizationAction.READ_CATALOG,false);
        var product=product(organizationId,productId,false);
        var attestation=classifications.latest(organizationId,productId).orElse(null);
        var revocation=attestation==null?null:classifications.revocation(organizationId,attestation.id()).orElse(null);
        return evidence(organizationId,product,attestation,revocation,clock.instant());
    }

    @Transactional
    public Result submit(UUID actor,UUID organizationId,UUID productId,FoodSafetyProductClassificationSubmission submission) {
        var membership=access.requireOrganizationAction(actor,organizationId,OrganizationAction.SUBMIT_FOOD_SAFETY_APPROVAL_EVIDENCE,true);
        if (submission==null) invalid();
        var fingerprint=submission.fingerprint(organizationId,productId);
        var receipt=classifications.receipt(organizationId,actor,submission.requestId());
        if (receipt.isPresent()) {
            var previous=receipt.orElseThrow();
            if (!previous.productId().equals(productId) || !MessageDigest.isEqual(previous.payloadDigest(),fingerprint)) conflict();
            var currentProduct=product(organizationId,productId,false);
            var revocation=classifications.revocation(organizationId,previous.id()).orElse(null);
            return new Result(evidence(organizationId,currentProduct,previous,revocation,clock.instant()),false);
        }

        // Organization security lock precedes this product row lock, matching category correction's authority order.
        var product=product(organizationId,productId,true);
        if (!product.active()) throw new CatalogFailure(CatalogFailure.Kind.INACTIVE);
        if (product.version()!=submission.expectedProductVersion()) conflict();
        if (product.data().category()==ProductCategory.UNCLASSIFIED) invalid();
        var candidate=new FoodSafetyProductClassification(UUID.randomUUID(),organizationId,productId,product.data().sku().value(),
                product.data().category(),product.version(),1,submission.issuerId(),submission.keyId(),submission.issuedAt(),
                submission.validFrom(),submission.validUntil(),submission.evidenceReference(),submission.signature(),actor,
                membership.membershipId(),submission.requestId(),fingerprint,null);
        var verification=verifier.verify(organizationId,product,candidate,null,clock.instant());
        if (verification.outcome()!=FoodSafetyProductClassificationVerification.Outcome.VERIFIED) invalid();

        var stored=classifications.append(candidate);
        audit.securityChange(actor,organizationId,"product-classification",stored.id(),"PRODUCT_CLASSIFICATION_ATTESTED",
                Map.ofEntries(Map.entry("outcome","SUCCEEDED"),Map.entry("productId",productId),Map.entry("sku",stored.sku()),
                        Map.entry("productCategory",stored.category()),Map.entry("productVersion",stored.productVersion()),
                        Map.entry("issuerId",stored.issuerId()),Map.entry("keyId",stored.keyId()),
                        Map.entry("validFrom",stored.validFrom()),Map.entry("validUntil",stored.validUntil()),
                        Map.entry("evidenceReference",stored.evidenceReference()),Map.entry("verification","VERIFIED")),
                "Independent product classification evidence submitted");
        return new Result(evidence(organizationId,product,stored,null,clock.instant()),true);
    }

    @Transactional
    public Result revoke(UUID actor,UUID organizationId,UUID productId,UUID attestationId,
            FoodSafetyProductClassificationRevocationCommand command) {
        var membership=access.requireOrganizationAction(actor,organizationId,OrganizationAction.REVOKE_FOOD_SAFETY_APPROVAL,true);
        var attestation=classifications.byId(organizationId,productId,attestationId).orElseThrow(OrganizationUnavailableException::new);
        if (command==null) invalid();
        var fingerprint=command.fingerprint(organizationId,productId,attestationId);
        var receipt=classifications.revocationReceipt(organizationId,actor,command.requestId());
        if (receipt.isPresent()) {
            var previous=receipt.orElseThrow();
            if (!previous.attestationId().equals(attestationId) || !MessageDigest.isEqual(previous.payloadDigest(),fingerprint)) conflict();
            var product=product(organizationId,productId,false);
            return new Result(evidence(organizationId,product,attestation,previous,clock.instant()),false);
        }
        if (classifications.revocation(organizationId,attestationId).isPresent()) conflict();
        var revoked=classifications.revoke(new FoodSafetyProductClassificationRevocation(UUID.randomUUID(),organizationId,attestationId,
                actor,membership.membershipId(),command.requestId(),command.reason(),fingerprint,null));
        var product=product(organizationId,productId,false);
        audit.securityChange(actor,organizationId,"product-classification",attestationId,"PRODUCT_CLASSIFICATION_REVOKED",
                Map.of("outcome","SUCCEEDED","productId",productId,"sku",attestation.sku(),"productCategory",attestation.category(),
                        "productVersion",attestation.productVersion(),"issuerId",attestation.issuerId(),"keyId",attestation.keyId(),
                        "recordedAt",revoked.recordedAt(),"recordedByMembershipId",revoked.recordedByMembershipId()),command.reason());
        return new Result(evidence(organizationId,product,attestation,revoked,clock.instant()),true);
    }

    private Evidence evidence(UUID organizationId,Product product,FoodSafetyProductClassification attestation,
            FoodSafetyProductClassificationRevocation revocation,java.time.Instant now) {
        return new Evidence(attestation==null?null:attestation.id(),verifier.verify(organizationId,product,attestation,revocation,now));
    }

    private Product product(UUID organizationId,UUID productId,boolean lock) {
        return products.detail(organizationId,productId,lock).orElseThrow(OrganizationUnavailableException::new);
    }
    private static void invalid() { throw new CatalogFailure(CatalogFailure.Kind.INVALID_POLICY); }
    private static void conflict() { throw new CatalogFailure(CatalogFailure.Kind.CONFLICT); }
}
