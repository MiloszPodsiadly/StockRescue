package com.stockrescue.access.application;

import java.util.List;
import java.util.UUID;

public record EffectivePermissions(OrganizationAccess organization, List<OrganizationAction> organizationActions,
        List<StoreAction> allStoreActions, List<StorePermission> storeGrants) {
    public enum OrganizationAction { READ_ORGANIZATION, MANAGE_ACCESS, CREATE_STORE, MANAGE_TRANSPORT_LANES }
    public enum StoreAction { READ_STORE, EDIT_STORE, ARCHIVE_STORE, MANAGE_STORAGE_ZONES }
    public enum StoreRole { STORE_MANAGER, INVENTORY_OPERATOR, READ_ONLY_ANALYST }
    public record StorePermission(UUID storeId, StoreRole role, List<StoreAction> actions) { }
}
