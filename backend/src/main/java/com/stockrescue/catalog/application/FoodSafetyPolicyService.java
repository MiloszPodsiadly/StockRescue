package com.stockrescue.catalog.application;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.persistence.FoodSafetyPolicyRepository;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FoodSafetyPolicyService {
    public record ReviewResult(FoodSafetyPolicyVersion version,boolean created) { }
    public record History(FoodSafetyPolicyVersion current,List<FoodSafetyPolicyVersion> items,boolean hasMore) { }
    private final AccessPolicy access; private final FoodSafetyPolicyRepository policies; private final AuditWriter audit;
    public FoodSafetyPolicyService(AccessPolicy access,FoodSafetyPolicyRepository policies,AuditWriter audit) { this.access=access;this.policies=policies;this.audit=audit; }
    @Transactional
    public ReviewResult review(UUID actor,UUID org,TemperatureClass temperature,UUID request,FoodSafetyPolicyReview review) {
        // The exclusive authority lock serializes policy appends and membership changes in organization-first order.
        var administrator=access.requireOrganizationAction(actor,org,OrganizationAction.MANAGE_FOOD_SAFETY_POLICY,true);
        if (temperature==null || request==null || review==null) throw new CatalogFailure(CatalogFailure.Kind.INVALID_POLICY);
        var recorded=policies.receipt(org,actor,request);
        if (recorded.isPresent()) {
            if (!MessageDigest.isEqual(recorded.orElseThrow().digest(),review.fingerprint(temperature))) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
            return new ReviewResult(recorded.orElseThrow().version(),false);
        }
        var before=policies.current(org,temperature);
        if (before.map(FoodSafetyPolicyVersion::version).orElse(0L)!=review.expectedVersion()) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
        var after=policies.append(org,actor,administrator.membershipId(),request,before.map(FoodSafetyPolicyVersion::profileId).orElseGet(UUID::randomUUID),temperature,review);
        audit.securityChange(actor,org,"food-safety-profile",after.profileId(),"FOOD_SAFETY_POLICY_REVIEWED",
                Map.of("outcome","SUCCEEDED","previousVersion",review.expectedVersion(),"after",after),review.reason());
        return new ReviewResult(after,true);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public History history(UUID actor,UUID org,TemperatureClass temperature,int offset,int limit) {
        access.requireOrganizationAction(actor,org,OrganizationAction.READ_CATALOG,false);
        LotService.page(offset,limit);
        var rows=policies.history(org,temperature,offset,limit+1);
        return new History(policies.current(org,temperature).orElse(null),rows.stream().limit(limit).toList(),rows.size()>limit);
    }
}
