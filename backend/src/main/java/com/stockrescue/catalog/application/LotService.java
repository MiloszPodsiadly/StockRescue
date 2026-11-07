package com.stockrescue.catalog.application;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.persistence.LotRepository;
import com.stockrescue.catalog.persistence.LotCorrectionRepository;
import com.stockrescue.catalog.persistence.ProductRepository;
import com.stockrescue.platform.audit.AuditWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LotService {
    public record Registration(ManufacturerLot lot,boolean created) { }
    public record Page(List<ManufacturerLot> items,boolean hasMore,boolean canRegister) { }
    public record CorrectionResult(ManufacturerLot lot,LotCorrection correction,boolean created) { }
    public record History(long currentVersion,List<LotCorrection> items,boolean hasMore) { }
    private final AccessPolicy access; private final ProductRepository products; private final LotRepository lots;
    private final LotCorrectionRepository corrections; private final AuditWriter audit;
    public LotService(AccessPolicy access,ProductRepository products,LotRepository lots,LotCorrectionRepository corrections,AuditWriter audit) {
        this.access=access;this.products=products;this.lots=lots;this.corrections=corrections;this.audit=audit;
    }
    @Transactional
    public CorrectionResult correct(UUID actor,UUID org,UUID id,UUID request,LotMetadataCorrection command) {
        var administrator=access.requireOrganizationAction(actor,org,OrganizationAction.MANAGE_CATALOG,true);
        if (request==null || command==null) throw new CatalogFailure(CatalogFailure.Kind.INVALID_LOT);
        var product=products.lockForReference(org,existing(org,id).data().productId()).orElseThrow(OrganizationUnavailableException::new);
        var current=lots.lockForCorrection(org,id).orElseThrow(OrganizationUnavailableException::new);
        var digest=command.fingerprint(id);
        var recorded=corrections.receipt(actor,org,request);
        if (recorded.isPresent()) {
            if (!MessageDigest.isEqual(recorded.orElseThrow().digest(),digest)) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
            return new CorrectionResult(current,recorded.orElseThrow().correction(),false);
        }
        if (!product.active() || !current.active()) throw new CatalogFailure(CatalogFailure.Kind.INACTIVE);
        if (current.version()!=command.expectedVersion()) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
        var correction=corrections.correct(actor,administrator.membershipId(),org,id,request,digest,command,command.revisedData(current));
        audit.securityChange(actor,org,"manufacturer-lot",id,"LOT_METADATA_CORRECTED",
                Map.of("outcome","SUCCEEDED","correctionId",correction.id(),"before",correction.before(),"after",correction.after()),command.reason());
        return new CorrectionResult(correction.after(),correction,true);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public History history(UUID actor,UUID org,UUID id,int offset,int limit) {
        access.requireOrganizationAction(actor,org,OrganizationAction.READ_CATALOG,false);
        page(offset,limit);
        var current=existing(org,id);
        var rows=corrections.history(org,id,offset,limit+1);
        return new History(current.version(),rows.stream().limit(limit).toList(),rows.size()>limit);
    }
    @Transactional
    public CorrectionResult recordSafetyEvidence(UUID actor,UUID org,UUID id,UUID request,LotSafetyEvidence command) {
        var administrator=access.requireOrganizationAction(actor,org,OrganizationAction.RECORD_LOT_SAFETY_EVIDENCE,true);
        if (request==null || command==null) throw new CatalogFailure(CatalogFailure.Kind.INVALID_SAFETY_EVIDENCE);
        var product=products.lockForReference(org,existing(org,id).data().productId()).orElseThrow(OrganizationUnavailableException::new);
        var current=lots.lockForCorrection(org,id).orElseThrow(OrganizationUnavailableException::new);
        var digest=command.fingerprint(id); var recorded=corrections.receipt(actor,org,request);
        if (recorded.isPresent()) {
            if (!MessageDigest.isEqual(recorded.orElseThrow().digest(),digest)) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
            return new CorrectionResult(current,recorded.orElseThrow().correction(),false);
        }
        if (!product.active() || !current.active()) throw new CatalogFailure(CatalogFailure.Kind.INACTIVE);
        if (current.version()!=command.expectedVersion()) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
        command.requireTransition(current);
        var correction=corrections.recordSafetyEvidence(actor,administrator.membershipId(),org,id,request,command);
        audit.securityChange(actor,org,"manufacturer-lot",id,"LOT_SAFETY_EVIDENCE_RECORDED",
                Map.of("outcome","SUCCEEDED","correctionId",correction.id(),"before",correction.before(),"after",correction.after()),command.reason());
        return new CorrectionResult(correction.after(),correction,true);
    }
    @Transactional
    public Registration register(UUID actor,UUID org,UUID request,LotData data) {
        var administrator=access.requireOrganizationAction(actor,org,OrganizationAction.MANAGE_CATALOG,true);
        if (request==null || data==null || data.manufacturerProvenance()==null || data.dateMarking().normalizedCutoff().isPresent()) throw new CatalogFailure(CatalogFailure.Kind.INVALID_LOT);
        var digest=data.fingerprint(); var id=UUID.randomUUID();
        if (!lots.reserve(actor,org,request,id,digest)) {
            var recorded=lots.receipt(actor,org,request);
            if (!MessageDigest.isEqual(recorded.digest(),digest)) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
            return new Registration(existing(org,recorded.lotId()),false);
        }
        var product=products.lockForReference(org,data.productId()).orElseThrow(OrganizationUnavailableException::new);
        if (!product.active()) throw new CatalogFailure(CatalogFailure.Kind.INACTIVE);
        var lot=lots.create(org,id,administrator.membershipId(),data).orElseThrow(() -> new CatalogFailure(CatalogFailure.Kind.CONFLICT));
        audit.securityChange(actor,org,"manufacturer-lot",id,"LOT_REGISTERED",Map.of("outcome","SUCCEEDED","after",lot),"Manufacturer lot metadata registered");
        return new Registration(lot,true);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ManufacturerLot detail(UUID actor,UUID org,UUID id) {
        access.requireOrganizationAction(actor,org,OrganizationAction.READ_CATALOG,false);
        return existing(org,id);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(UUID actor,UUID org,UUID product,boolean includeArchived,int offset,int limit) {
        access.requireOrganizationAction(actor,org,OrganizationAction.READ_CATALOG,false);
        page(offset,limit);
        var productActive=product==null || products.detail(org,product,false).orElseThrow(OrganizationUnavailableException::new).active();
        var rows=lots.list(org,product,includeArchived,offset,limit+1);
        var permissions=access.permissions(actor,org);
        return new Page(rows.stream().limit(limit).toList(),rows.size()>limit,productActive && permissions.organizationActions().contains(OrganizationAction.MANAGE_CATALOG));
    }
    private ManufacturerLot existing(UUID org,UUID id) { return lots.detail(org,id).orElseThrow(OrganizationUnavailableException::new); }
    static void page(int offset,int limit) { if (offset<0 || offset>10000 || limit<1 || limit>100) throw new CatalogFailure(CatalogFailure.Kind.INVALID_LOT); }
}
