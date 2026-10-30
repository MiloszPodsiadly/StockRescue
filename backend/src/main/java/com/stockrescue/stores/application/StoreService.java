package com.stockrescue.stores.application;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions;
import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.stores.domain.StoreData;
import com.stockrescue.stores.domain.StoreFailure;
import com.stockrescue.stores.persistence.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreService {
    private final AccessPolicy access;
    private final StoreRepository stores;
    private final AuditWriter audit;
    public StoreService(AccessPolicy access, StoreRepository stores, AuditWriter audit) { this.access = access; this.stores = stores; this.audit = audit; }
    public enum State { ACTIVE, ARCHIVED, ALL }

    @Transactional
    public Creation create(UUID actor, UUID org, UUID request, StoreData data) {
        access.requireAdmin(actor, org, true);
        if (request == null) throw new StoreFailure(StoreFailure.Kind.INVALID_INPUT);
        var digest = data.fingerprint();
        var previous = stores.creation(actor, org, request);
        if (previous.isPresent()) {
            var recorded = previous.orElseThrow();
            if (!MessageDigest.isEqual(recorded.digest(), digest)) conflict();
            return new Creation(view(actor, org, existing(org, recorded.storeId())), false);
        }
        var created = stores.create(org, data).orElseThrow(() -> new StoreFailure(StoreFailure.Kind.CONFLICT));
        stores.remember(actor, org, request, created.id(), digest);
        audit.securityChange(actor, org, "store", created.id(), "STORE_CREATED",
                Map.of("outcome", "SUCCEEDED", "after", created), "Store configured");
        return new Creation(view(actor, org, created), true);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(UUID actor, UUID org, State state, String search, int offset, int limit) {
        if (state == null || search == null || search.length() > 100 || offset < 0 || offset > 10000 || limit < 1 || limit > 100) throw new StoreFailure(StoreFailure.Kind.INVALID_INPUT);
        var permissions = access.permissions(actor, org);
        var rows = stores.list(org, permissions.organization().membershipId(), permissions.allStoreActions().contains(StoreAction.READ_STORE), state.name(), search.strip(), offset, limit + 1);
        return new Page(rows.stream().limit(limit).map(store -> view(permissions, store)).toList(), rows.size() > limit,
                permissions.organizationActions().contains(OrganizationAction.CREATE_STORE));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View detail(UUID actor, UUID org, UUID store) {
        access.requireStoreIncludingArchived(actor, org, store, StoreAction.READ_STORE, false);
        return view(actor, org, existing(org, store));
    }

    @Transactional
    public View update(UUID actor, UUID org, UUID id, String name, String address, String city, String postal, String country, String zone, long version, String reason) {
        access.requireStoreIncludingArchived(actor, org, id, StoreAction.EDIT_STORE, true);
        var before = existing(org, id);
        editable(before, version, reason);
        var data = new StoreData(before.data().code(), name, address, city, postal, country, zone);
        if (data.equals(before.data())) return view(actor, org, before);
        var after = stores.update(org, id, version, data).orElseThrow(() -> new StoreFailure(StoreFailure.Kind.CONFLICT));
        audit.securityChange(actor, org, "store", id, "STORE_UPDATED", Map.of("outcome", "SUCCEEDED", "before", before, "after", after), reason);
        return view(actor, org, after);
    }

    @Transactional
    public View archive(UUID actor, UUID org, UUID id, long version, String reason) {
        access.requireStoreIncludingArchived(actor, org, id, StoreAction.ARCHIVE_STORE, true);
        var before = existing(org, id);
        editable(before, version, reason);
        var after = stores.archive(org, id, version).orElseThrow(() -> new StoreFailure(StoreFailure.Kind.CONFLICT));
        audit.securityChange(actor, org, "store", id, "STORE_ARCHIVED", Map.of("outcome", "SUCCEEDED", "before", before, "after", after), reason);
        return view(actor, org, after);
    }

    private void editable(Store store, long version, String reason) {
        if (!store.active()) throw new StoreFailure(StoreFailure.Kind.INACTIVE);
        if (store.version() != version) conflict();
        if (reason == null || reason.isBlank() || reason.length() > 500 || reason.indexOf('\0') >= 0) throw new StoreFailure(StoreFailure.Kind.INVALID_INPUT);
    }
    private Store existing(UUID org, UUID store) { return stores.detail(org, store).orElseThrow(OrganizationUnavailableException::new); }
    private View view(UUID actor, UUID org, Store store) { return view(access.permissions(actor, org), store); }
    private View view(EffectivePermissions permissions, Store store) { return new View(store, access.storeActions(permissions, store.id(), store.active())); }
    private void conflict() { throw new StoreFailure(StoreFailure.Kind.CONFLICT); }
    public record View(Store store, List<StoreAction> allowedActions) { }
    public record Creation(View view, boolean created) { }
    public record Page(List<View> items, boolean hasMore, boolean canCreate) { }
}
