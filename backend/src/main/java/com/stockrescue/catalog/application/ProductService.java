package com.stockrescue.catalog.application;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions;
import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.catalog.persistence.ProductRepository;
import com.stockrescue.catalog.persistence.ProductRepository.Operation;
import com.stockrescue.platform.audit.AuditWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
    public enum State { ACTIVE, ARCHIVED, ALL }
    public record View(Product product, List<OrganizationAction> allowedActions) { }
    public record Creation(View view, boolean created) { }
    public record Page(List<View> items, boolean hasMore, boolean canCreate) { }
    private final AccessPolicy access;
    private final ProductRepository products;
    private final AuditWriter audit;
    public ProductService(AccessPolicy access, ProductRepository products, AuditWriter audit) {
        this.access=access; this.products=products; this.audit=audit;
    }

    @Transactional
    public Creation create(UUID actor, UUID org, UUID request, ProductData data) {
        access.requireOrganizationAction(actor,org,OrganizationAction.MANAGE_CATALOG,true);
        if (request == null || data == null) invalid();
        var digest = data.fingerprint();
        var id = UUID.randomUUID();
        if (!products.reserve(actor,org,Operation.CREATE,request,id,0,digest)) {
            var previous = products.receipt(actor,org,Operation.CREATE,request);
            samePayload(previous,digest);
            return new Creation(view(actor,org,existing(org,previous.productId(),false)),false);
        }
        var created = products.create(org,id,data).orElseThrow(ProductService::conflict);
        audit.securityChange(actor,org,"product",id,"PRODUCT_CREATED",Map.of("outcome","SUCCEEDED","after",created),"Product catalog registration");
        return new Creation(view(actor,org,created),true);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View detail(UUID actor, UUID org, UUID id) {
        access.requireOrganizationAction(actor,org,OrganizationAction.READ_CATALOG,false);
        return view(actor,org,existing(org,id,false));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(UUID actor, UUID org, State state, String q, int offset, int limit) {
        access.requireOrganizationAction(actor,org,OrganizationAction.READ_CATALOG,false);
        if (state == null || q == null || q.length()>100 || q.codePoints().anyMatch(Character::isISOControl)
                || offset<0 || offset>10000 || limit<1 || limit>100) invalid();
        var permissions=access.permissions(actor,org);
        var rows=products.list(org,state.name(),q.strip(),offset,limit+1);
        return new Page(rows.stream().limit(limit).map(product -> view(permissions,product)).toList(),rows.size()>limit,
                permissions.organizationActions().contains(OrganizationAction.MANAGE_CATALOG));
    }

    @Transactional
    public View archive(UUID actor, UUID org, UUID id, UUID request, long expectedVersion, String reason) {
        access.requireOrganizationAction(actor,org,OrganizationAction.MANAGE_CATALOG,true);
        if (request == null || expectedVersion<0 || expectedVersion==Long.MAX_VALUE || reason == null || reason.isBlank()
                || reason.length()>500 || reason.codePoints().anyMatch(Character::isISOControl)) invalid();
        reason=reason.strip();
        var digest=CatalogCommandDigest.of(id.toString(),Long.toString(expectedVersion),reason);
        if (!products.reserve(actor,org,Operation.ARCHIVE,request,id,expectedVersion+1,digest)) {
            var previous=products.receipt(actor,org,Operation.ARCHIVE,request);
            samePayload(previous,digest);
            return view(actor,org,existing(org,previous.productId(),false));
        }
        var before=existing(org,id,true);
        if (!before.active()) throw new CatalogFailure(CatalogFailure.Kind.INACTIVE);
        if (before.version()!=expectedVersion) throw conflict();
        var after=products.archive(org,id,expectedVersion).orElseThrow(ProductService::conflict);
        audit.securityChange(actor,org,"product",id,"PRODUCT_ARCHIVED",Map.of("outcome","SUCCEEDED","before",before,"after",after),reason);
        return view(actor,org,after);
    }

    @Transactional
    public View reclassify(UUID actor, UUID org, UUID id, UUID request, long expectedVersion, ProductCategory category, String reason) {
        access.requireOrganizationAction(actor,org,OrganizationAction.MANAGE_CATALOG,true);
        if (request==null || expectedVersion<0 || expectedVersion==Long.MAX_VALUE || category==null
                || reason==null || reason.isBlank() || reason.length()>500 || reason.codePoints().anyMatch(Character::isISOControl)) invalid();
        reason=reason.strip();
        var digest=CatalogCommandDigest.of(id.toString(),Long.toString(expectedVersion),category.name(),reason);
        if (!products.reserve(actor,org,Operation.RECLASSIFY,request,id,expectedVersion+1,digest)) {
            var previous=products.receipt(actor,org,Operation.RECLASSIFY,request);
            samePayload(previous,digest);
            return view(actor,org,existing(org,previous.productId(),false));
        }
        var before=existing(org,id,true);
        if (!before.active()) throw new CatalogFailure(CatalogFailure.Kind.INACTIVE);
        if (before.version()!=expectedVersion || before.data().category()==category) throw conflict();
        var after=products.reclassify(org,id,expectedVersion,category).orElseThrow(ProductService::conflict);
        audit.securityChange(actor,org,"product",id,"PRODUCT_CATEGORY_CORRECTED",
                Map.of("outcome","SUCCEEDED","before",before,"after",after),reason);
        return view(actor,org,after);
    }

    private Product existing(UUID org, UUID id, boolean lock) { return products.detail(org,id,lock).orElseThrow(OrganizationUnavailableException::new); }
    private View view(UUID actor, UUID org, Product product) { return view(access.permissions(actor,org),product); }
    private View view(EffectivePermissions permissions, Product product) {
        return new View(product,product.active() && permissions.organizationActions().contains(OrganizationAction.MANAGE_CATALOG)
                ? List.of(OrganizationAction.READ_CATALOG,OrganizationAction.MANAGE_CATALOG) : List.of(OrganizationAction.READ_CATALOG));
    }
    private void samePayload(ProductRepository.Receipt receipt, byte[] digest) { if (!MessageDigest.isEqual(receipt.digest(),digest)) throw conflict(); }
    private static CatalogFailure conflict() { return new CatalogFailure(CatalogFailure.Kind.CONFLICT); }
    private static void invalid() { throw new CatalogFailure(CatalogFailure.Kind.INVALID_PRODUCT); }
}
