package com.stockrescue.catalog.application;

import java.time.Clock;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.Operation;
import com.stockrescue.catalog.persistence.FoodSafetyPolicyRepository;
import com.stockrescue.catalog.persistence.FoodSafetyPolicyApprovalRepository;
import com.stockrescue.catalog.persistence.FoodSafetyProductClassificationRepository;
import com.stockrescue.catalog.persistence.LotRepository;
import com.stockrescue.catalog.persistence.ProductRepository;
import com.stockrescue.stores.persistence.StoreRepository;
import com.stockrescue.stores.persistence.ZoneRepository;
import com.stockrescue.stores.persistence.StorageLocationRepository;
import com.stockrescue.catalog.domain.CatalogFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FoodSafetyEvaluationService {
    private final AccessPolicy access; private final LotRepository lots; private final ProductRepository products;
    private final StoreRepository stores; private final ZoneRepository zones; private final FoodSafetyPolicyRepository policies; private final Clock clock;
    private final FoodSafetyPolicyApprovalRepository approvals; private final FoodSafetyPolicyApprovalVerifier approvalVerifier;
    private final FoodSafetyProductClassificationRepository classifications; private final FoodSafetyProductClassificationVerifier classificationVerifier;
    private final StorageLocationRepository locations;
    private final FoodSafetyEvaluator evaluator=new FoodSafetyEvaluator();
    public FoodSafetyEvaluationService(AccessPolicy access,LotRepository lots,ProductRepository products,StoreRepository stores,ZoneRepository zones,
            StorageLocationRepository locations,FoodSafetyPolicyRepository policies,FoodSafetyPolicyApprovalRepository approvals,
            FoodSafetyPolicyApprovalVerifier approvalVerifier,FoodSafetyProductClassificationRepository classifications,
            FoodSafetyProductClassificationVerifier classificationVerifier,Clock clock) {
        this.access=access;this.lots=lots;this.products=products;this.stores=stores;this.zones=zones;this.locations=locations;
        this.policies=policies;this.approvals=approvals;this.approvalVerifier=approvalVerifier;
        this.classifications=classifications;this.classificationVerifier=classificationVerifier;this.clock=clock;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public FoodSafetyEvaluation evaluate(UUID actor,UUID org,UUID lotId,UUID storeId,UUID zoneId,UUID locationId,Operation operation) {
        access.requireOrganizationAction(actor,org,OrganizationAction.READ_CATALOG,false);
        access.requireStoreIncludingArchived(actor,org,storeId,StoreAction.READ_STORE,false);
        if (zoneId!=null && locationId!=null) throw new CatalogFailure(CatalogFailure.Kind.INVALID_STORAGE_CONTEXT);
        var lot=lots.detail(org,lotId).orElseThrow(OrganizationUnavailableException::new);
        var product=products.detail(org,lot.data().productId(),false).orElseThrow(OrganizationUnavailableException::new);
        var store=stores.detail(org,storeId).orElseThrow(OrganizationUnavailableException::new);
        var location=locationId==null?null:locations.detail(org,storeId,locationId,false).orElseThrow(OrganizationUnavailableException::new);
        var actualZone=location==null?zoneId:location.data().zoneId();
        var zone=actualZone==null?null:zones.detail(org,storeId,actualZone,false).orElseThrow(OrganizationUnavailableException::new);
        var policy=policies.current(org,product.data().category(),product.data().temperatureClass()).orElse(null);
        var evaluatedAt=clock.instant();
        var approval=policy==null?null:approvals.forPolicy(org,policy.id()).orElse(null);
        var revocation=approval==null?null:approvals.revocation(org,approval.id()).orElse(null);
        var verification=policy==null?null:approvalVerifier.verify(policy,org,approval,revocation,operation,evaluatedAt);
        var classification=classifications.latest(org,product.id()).orElse(null);
        var classificationRevocation=classification==null?null:classifications.revocation(org,classification.id()).orElse(null);
        var classificationEvidence=classificationVerifier.verify(org,product,classification,classificationRevocation,evaluatedAt);
        return evaluator.evaluate(new FoodSafetyEvaluator.Context(lot,product,store,zone,policy,location),operation,evaluatedAt,verification,classificationEvidence);
    }
}
