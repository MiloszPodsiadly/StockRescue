package com.stockrescue.catalog.application;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.catalog.persistence.FoodSafetyPolicyApprovalRepository;
import com.stockrescue.catalog.persistence.FoodSafetyPolicyRepository;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Restricted append-only submission and revocation of externally signed policy evidence. */
@Service
public class FoodSafetyPolicyApprovalService {
    public record Evidence(UUID approvalId,FoodSafetyPolicyApprovalVerification verification) { }
    public record SubmissionResult(Evidence evidence,boolean created) { }
    public record RevocationResult(Evidence evidence,boolean created) { }
    private final AccessPolicy access;
    private final FoodSafetyPolicyRepository policies;
    private final FoodSafetyPolicyApprovalRepository approvals;
    private final FoodSafetyPolicyApprovalVerifier verifier;
    private final AuditWriter audit;
    private final Clock clock;

    public FoodSafetyPolicyApprovalService(AccessPolicy access,FoodSafetyPolicyRepository policies,
            FoodSafetyPolicyApprovalRepository approvals,FoodSafetyPolicyApprovalVerifier verifier,AuditWriter audit,Clock clock) {
        this.access=access;this.policies=policies;this.approvals=approvals;this.verifier=verifier;this.audit=audit;this.clock=clock;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Evidence evidence(UUID actor,UUID org,ProductCategory category,TemperatureClass temperature,UUID policyId) {
        access.requireOrganizationAction(actor,org,OrganizationAction.READ_CATALOG,false);
        var policy=policy(org,category,temperature,policyId);
        var approval=approvals.forPolicy(org,policyId).orElse(null);
        var revocation=approval==null?null:approvals.revocation(org,approval.id()).orElse(null);
        return new Evidence(approval==null?null:approval.id(),verifier.verify(policy,org,approval,revocation,null,clock.instant()));
    }

    @Transactional
    public SubmissionResult submit(UUID actor,UUID org,ProductCategory category,TemperatureClass temperature,UUID policyId,
            FoodSafetyPolicyApprovalSubmission submission) {
        var membership=access.requireOrganizationAction(actor,org,OrganizationAction.SUBMIT_FOOD_SAFETY_APPROVAL_EVIDENCE,true);
        var policy=policy(org,category,temperature,policyId);
        if (submission==null) throw new CatalogFailure(CatalogFailure.Kind.INVALID_POLICY);
        var fingerprint=submission.fingerprint(org,policy);
        var receipt=approvals.receipt(org,actor,submission.requestId());
        if (receipt.isPresent()) {
            var previous=receipt.orElseThrow();
            if (!MessageDigest.isEqual(previous.payloadDigest(),fingerprint)) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
            return new SubmissionResult(currentEvidence(org,policy,previous),false);
        }
        var current=policies.current(org,category,temperature).orElseThrow(OrganizationUnavailableException::new);
        if (!current.id().equals(policyId)) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
        if (approvals.forPolicy(org,policyId).isPresent()) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);

        var candidate=new FoodSafetyPolicyApproval(UUID.randomUUID(),org,policy.id(),policy.profileId(),category,temperature,
                submission.allowedOperations(),HexFormat.of().parseHex(submission.policyDigest()),2,submission.rulesetId(),
                HexFormat.of().parseHex(submission.rulesetDigest()),submission.issuerId(),submission.keyId(),
                submission.issuedAt(),submission.validFrom(),submission.validUntil(),submission.evidenceReference(),submission.signature(),
                actor,membership.membershipId(),submission.requestId(),fingerprint,null);
        var verification=verifier.verify(policy,org,candidate,null,null,clock.instant());
        if (verification.outcome()!=FoodSafetyPolicyApprovalVerification.Outcome.VERIFIED)
            throw new CatalogFailure(CatalogFailure.Kind.INVALID_POLICY);

        var stored=approvals.append(candidate);
        audit.securityChange(actor,org,"food-safety-policy-approval",stored.id(),"FOOD_SAFETY_POLICY_APPROVAL_SUBMITTED",
                Map.ofEntries(Map.entry("outcome","SUCCEEDED"),Map.entry("policyVersionId",policy.id()),Map.entry("policyVersion",policy.version()),
                        Map.entry("productCategory",category),Map.entry("temperatureClass",temperature),Map.entry("policyDigest",submission.policyDigest()),
                        Map.entry("rulesetId",stored.rulesetId()),Map.entry("rulesetDigest",HexFormat.of().formatHex(stored.rulesetDigest())),
                        Map.entry("issuerId",stored.issuerId()),Map.entry("keyId",stored.keyId()),
                        Map.entry("allowedOperations",stored.allowedOperations().stream().sorted().toList()),Map.entry("issuedAt",stored.issuedAt()),
                        Map.entry("validFrom",stored.validFrom()),Map.entry("validUntil",stored.validUntil()),
                        Map.entry("evidenceReference",stored.evidenceReference()),Map.entry("verification","VERIFIED")),"Independent policy evidence submitted");
        return new SubmissionResult(currentEvidence(org,policy,stored),true);
    }

    @Transactional
    public RevocationResult revoke(UUID actor,UUID org,UUID approvalId,FoodSafetyPolicyApprovalRevocationCommand command) {
        var membership=access.requireOrganizationAction(actor,org,OrganizationAction.REVOKE_FOOD_SAFETY_APPROVAL,true);
        var approval=approvals.byId(org,approvalId).orElseThrow(OrganizationUnavailableException::new);
        if (command==null) throw new CatalogFailure(CatalogFailure.Kind.INVALID_POLICY);
        var fingerprint=command.fingerprint(org,approvalId);
        var receipt=approvals.revocationReceipt(org,actor,command.requestId());
        if (receipt.isPresent()) {
            var previous=receipt.orElseThrow();
            if (!previous.approvalId().equals(approvalId) || !MessageDigest.isEqual(previous.payloadDigest(),fingerprint))
                throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
            return new RevocationResult(currentEvidence(org,approvalForPolicy(org,approval),approval),false);
        }
        if (approvals.revocation(org,approvalId).isPresent()) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
        var revoked=approvals.revoke(new FoodSafetyPolicyApprovalRevocation(UUID.randomUUID(),org,approvalId,actor,
                membership.membershipId(),command.requestId(),command.reason(),fingerprint,null));
        var policy=approvalForPolicy(org,approval);
        audit.securityChange(actor,org,"food-safety-policy-approval",approvalId,"FOOD_SAFETY_POLICY_APPROVAL_REVOKED",
                Map.of("outcome","SUCCEEDED","policyVersionId",approval.policyVersionId(),"issuerId",approval.issuerId(),
                        "keyId",approval.keyId(),"policyDigest",HexFormat.of().formatHex(approval.policyDigest()),
                        "evidenceReference",approval.evidenceReference(),"verification","REVOKED",
                        "recordedAt",revoked.recordedAt(),"recordedByMembershipId",revoked.recordedByMembershipId()),command.reason());
        return new RevocationResult(new Evidence(approvalId,verifier.verify(policy,org,approval,revoked,null,clock.instant())),true);
    }

    private Evidence currentEvidence(UUID org,FoodSafetyPolicyVersion policy,FoodSafetyPolicyApproval approval) {
        var revocation=approvals.revocation(org,approval.id()).orElse(null);
        return new Evidence(approval.id(),verifier.verify(policy,org,approval,revocation,null,clock.instant()));
    }

    private FoodSafetyPolicyVersion policy(UUID org,ProductCategory category,TemperatureClass temperature,UUID policyId) {
        var policy=policies.byId(org,policyId).orElseThrow(OrganizationUnavailableException::new);
        if (policy.productCategory()!=category || policy.temperatureClass()!=temperature) throw new OrganizationUnavailableException();
        return policy;
    }

    private FoodSafetyPolicyVersion approvalForPolicy(UUID org,FoodSafetyPolicyApproval approval) {
        return policies.byId(org,approval.policyVersionId()).orElseThrow(OrganizationUnavailableException::new);
    }
}
