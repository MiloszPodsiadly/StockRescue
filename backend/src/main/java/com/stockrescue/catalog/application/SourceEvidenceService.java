package com.stockrescue.catalog.application;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions;
import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.persistence.LotRepository;
import com.stockrescue.catalog.persistence.ProductRepository;
import com.stockrescue.catalog.persistence.SourceEvidenceRepository;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.stores.persistence.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceEvidenceService {
    public enum VisibilityScope { ALL_ORGANIZATION_SOURCES, ASSIGNED_STORE_SOURCES }
    public enum Coverage { RECORDED_EVIDENCE_ONLY }
    public record Registration(ConsignmentEvidence evidence,boolean created) { }
    public record Trace(UUID lotId,List<ConsignmentEvidence> items,boolean hasMore,VisibilityScope visibilityScope,Coverage coverage) { }
    private final AccessPolicy access; private final SourceEvidenceRepository sources; private final LotRepository lots;
    private final ProductRepository products; private final StoreRepository stores; private final AuditWriter audit;
    public SourceEvidenceService(AccessPolicy access,SourceEvidenceRepository sources,LotRepository lots,ProductRepository products,StoreRepository stores,AuditWriter audit) {
        this.access=access;this.sources=sources;this.lots=lots;this.products=products;this.stores=stores;this.audit=audit;
    }
    @Transactional
    public Registration register(UUID actor,UUID org,UUID lotId,UUID request,SourceEvidenceData data) {
        if (data==null || request==null) throw new CatalogFailure(CatalogFailure.Kind.INVALID_SOURCE);
        if (data.storeId()==null) access.requireOrganizationAction(actor,org,OrganizationAction.MANAGE_CATALOG,true);
        else access.requireStoreIncludingArchived(actor,org,data.storeId(),StoreAction.REGISTER_SOURCE_EVIDENCE,true);
        var digest=data.fingerprint(lotId); var id=UUID.randomUUID();
        if (!sources.reserve(actor,org,request,id,digest)) {
            var recorded=sources.receipt(actor,org,request);
            if (!MessageDigest.isEqual(recorded.digest(),digest)) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
            return new Registration(existing(org,lotId,recorded.evidenceId(),access.permissions(actor,org)),false);
        }
        if (data.storeId()!=null && !stores.detail(org,data.storeId()).orElseThrow(OrganizationUnavailableException::new).active()) throw new CatalogFailure(CatalogFailure.Kind.INACTIVE);
        var observed=lots.detail(org,lotId).orElseThrow(OrganizationUnavailableException::new);
        var product=products.lockForReference(org,observed.data().productId()).orElseThrow(OrganizationUnavailableException::new);
        var lot=lots.lockForSourceRegistration(org,lotId).orElseThrow(OrganizationUnavailableException::new);
        if (!product.active() || !lot.active()) throw new CatalogFailure(CatalogFailure.Kind.INACTIVE);
        var evidence=sources.create(org,lotId,id,data);
        audit.securityChange(actor,org,"consignment-evidence",id,"SOURCE_EVIDENCE_REGISTERED",Map.of("outcome","SUCCEEDED","after",evidence),"Independent source metadata recorded");
        return new Registration(evidence,true);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ConsignmentEvidence detail(UUID actor,UUID org,UUID lot,UUID id) {
        access.requireOrganizationAction(actor,org,OrganizationAction.READ_CATALOG,false);
        lots.detail(org,lot).orElseThrow(OrganizationUnavailableException::new);
        return existing(org,lot,id,access.permissions(actor,org));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Trace trace(UUID actor,UUID org,UUID lot,int offset,int limit) {
        access.requireOrganizationAction(actor,org,OrganizationAction.READ_CATALOG,false);
        LotService.page(offset,limit);
        lots.detail(org,lot).orElseThrow(OrganizationUnavailableException::new);
        var permissions=access.permissions(actor,org); var all=all(permissions);
        var rows=sources.trace(org,lot,permissions.organization().membershipId(),all,offset,limit+1);
        return new Trace(lot,rows.stream().limit(limit).toList(),rows.size()>limit,
                all?VisibilityScope.ALL_ORGANIZATION_SOURCES:VisibilityScope.ASSIGNED_STORE_SOURCES,Coverage.RECORDED_EVIDENCE_ONLY);
    }
    private ConsignmentEvidence existing(UUID org,UUID lot,UUID id,EffectivePermissions permissions) {
        return sources.detail(org,lot,id,permissions.organization().membershipId(),all(permissions)).orElseThrow(OrganizationUnavailableException::new);
    }
    private boolean all(EffectivePermissions permissions) { return permissions.organizationActions().contains(OrganizationAction.MANAGE_CATALOG); }
}
