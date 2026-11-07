package com.stockrescue.catalog.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationAccess.OrganizationRole;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.domain.LotIdentityState;
import com.stockrescue.catalog.persistence.LotRepository;
import com.stockrescue.catalog.persistence.LotCorrectionRepository;
import com.stockrescue.catalog.persistence.ProductRepository;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.stores.persistence.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LotService {
    public record Registration(ManufacturerLot lot,boolean created) { }
    public record ArchiveResult(ManufacturerLot lot,boolean created) { }
    public record Page(List<ManufacturerLot> items,boolean hasMore,boolean canRegister) { }
    public record CorrectionResult(ManufacturerLot lot,LotCorrection correction,boolean created) { }
    public record History(long currentVersion,List<LotCorrection> items,boolean hasMore) { }
    private final AccessPolicy access; private final ProductRepository products; private final LotRepository lots;
    private final LotCorrectionRepository corrections; private final StoreRepository stores; private final AuditWriter audit;
    public LotService(AccessPolicy access,ProductRepository products,LotRepository lots,LotCorrectionRepository corrections,StoreRepository stores,AuditWriter audit) {
        this.access=access;this.products=products;this.lots=lots;this.corrections=corrections;this.stores=stores;this.audit=audit;
    }
    @Transactional
    public ArchiveResult archive(UUID actor,UUID org,UUID id,UUID request,long expectedVersion,String reason) {
        access.requireOrganizationAction(actor,org,OrganizationAction.MANAGE_CATALOG,true);
        if (request==null || expectedVersion<0 || expectedVersion==Long.MAX_VALUE || reason==null || reason.isBlank()
                || reason.length()>500 || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw new CatalogFailure(CatalogFailure.Kind.INVALID_LOT);
        }
        reason=reason.strip();
        var observed=existing(org,id);
        var product=products.lockForReference(org,observed.data().productId()).orElseThrow(OrganizationUnavailableException::new);
        var current=lots.lockForCorrection(org,id).orElseThrow(OrganizationUnavailableException::new);
        var digest=CatalogCommandDigest.of("LOT_ARCHIVE",id.toString(),Long.toString(expectedVersion),reason);
        var previous=lots.archiveReceipt(actor,org,request);
        if (previous.isPresent()) return archivedReplay(previous.orElseThrow(),digest,current,id);
        if (!product.active() || !current.active()) throw new CatalogFailure(CatalogFailure.Kind.INACTIVE);
        if (current.version()!=expectedVersion) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
        if (!lots.reserveArchive(actor,org,request,id,expectedVersion+1,digest)) {
            var receipt=lots.archiveReceipt(actor,org,request).orElseThrow();
            return archivedReplay(receipt,digest,current,id);
        }
        var archived=lots.archive(org,id,expectedVersion).orElseThrow(() -> new CatalogFailure(CatalogFailure.Kind.CONFLICT));
        audit.securityChange(actor,org,"manufacturer-lot",id,"LOT_ARCHIVED",
                Map.of("outcome","SUCCEEDED","before",current,"after",archived),reason);
        return new ArchiveResult(archived,true);
    }
    private ArchiveResult archivedReplay(LotRepository.ArchiveReceipt receipt,byte[] digest,ManufacturerLot current,UUID requestedLot) {
        if (!MessageDigest.isEqual(receipt.digest(),digest) || !receipt.lotId().equals(requestedLot))
            throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
        if (current.active() || current.version()!=receipt.resultVersion()) throw new IllegalStateException("Archived lot command result is inconsistent");
        return new ArchiveResult(current,false);
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
    public Registration register(UUID actor,UUID org,UUID request,UUID storeId,LotData data) {
        var membership=storeId==null
                ? access.requireOrganizationAction(actor,org,OrganizationAction.MANAGE_CATALOG,true)
                : access.requireStoreIncludingArchived(actor,org,storeId,StoreAction.REGISTER_STORE_LOT,true);
        if (membership.role()!=OrganizationRole.ORG_ADMIN
                && (storeId==null || data==null || data.identityState()!=LotIdentityState.CANDIDATE
                    || data.manufacturerVerified() || data.manufacturerIdentityKey()!=null)) {
            throw new org.springframework.security.access.AccessDeniedException("Action unavailable");
        }
        if (request==null || data==null || data.manufacturerProvenance()==null || data.dateMarking().normalizedCutoff().isPresent()) throw new CatalogFailure(CatalogFailure.Kind.INVALID_LOT);
        var digest=registrationDigest(storeId,data); var id=UUID.randomUUID();
        if (!lots.reserve(actor,org,request,storeId,id,digest)) {
            var recorded=lots.receipt(org,request);
            if (!recorded.actorUserId().equals(actor) || !Objects.equals(recorded.storeId(),storeId)
                    || !MessageDigest.isEqual(recorded.digest(),digest)) throw new CatalogFailure(CatalogFailure.Kind.CONFLICT);
            return new Registration(existing(org,recorded.lotId()),false);
        }
        if (storeId!=null && !stores.detail(org,storeId).orElseThrow(OrganizationUnavailableException::new).active())
            throw new CatalogFailure(CatalogFailure.Kind.INACTIVE);
        var product=products.lockForReference(org,data.productId()).orElseThrow(OrganizationUnavailableException::new);
        if (!product.active()) throw new CatalogFailure(CatalogFailure.Kind.INACTIVE);
        var lot=lots.create(org,id,membership.membershipId(),data).orElseThrow(() -> new CatalogFailure(CatalogFailure.Kind.CONFLICT));
        var details=new java.util.LinkedHashMap<String,Object>(); details.put("outcome","SUCCEEDED"); details.put("after",lot);
        if (storeId!=null) details.put("storeId",storeId);
        audit.securityChange(actor,org,"manufacturer-lot",id,"LOT_REGISTERED",details,"Manufacturer lot metadata registered");
        return new Registration(lot,true);
    }
    private byte[] registrationDigest(UUID storeId,LotData data) {
        var dataDigest=data.fingerprint();
        if (storeId==null) return dataDigest; // Preserve exact global-registration retries across this schema change.
        var scope=storeId.toString().getBytes(StandardCharsets.US_ASCII);
        var value=ByteBuffer.allocate("store-lot-registration:v1:".length()+scope.length+dataDigest.length)
                .put("store-lot-registration:v1:".getBytes(StandardCharsets.US_ASCII)).put(scope).put(dataDigest).array();
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
