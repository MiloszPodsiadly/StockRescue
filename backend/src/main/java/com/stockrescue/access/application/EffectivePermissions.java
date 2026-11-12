package com.stockrescue.access.application;

import java.util.List;
import java.util.UUID;

public record EffectivePermissions(OrganizationAccess organization, List<OrganizationAction> organizationActions,
        List<StoreAction> allStoreActions, List<StorePermission> storeGrants) {
    public enum OrganizationAction { READ_ORGANIZATION, MANAGE_ACCESS, CREATE_STORE, MANAGE_TRANSPORT_LANES, READ_CATALOG, MANAGE_CATALOG,
        MANAGE_FOOD_SAFETY_POLICY, RECORD_LOT_SAFETY_EVIDENCE }
    public enum StoreAction { READ_STORE, EDIT_STORE, ARCHIVE_STORE, MANAGE_STORAGE_ZONES, REGISTER_SOURCE_EVIDENCE, MANAGE_STORAGE_LOCATIONS, REGISTER_STORE_LOT,
        READ_INVENTORY, RECEIVE_STOCK, RESERVE_STOCK, RELEASE_RESERVATION, ADJUST_STOCK, WRITE_OFF_STOCK, QUARANTINE_STOCK,
        RECORD_SALE, MANAGE_INVENTORY_ALLOCATIONS, RECONCILE_INVENTORY, REMEDIATE_INVENTORY }
    public enum StoreRole { STORE_MANAGER, INVENTORY_OPERATOR, READ_ONLY_ANALYST }
    public record StorePermission(UUID storeId, StoreRole role, List<StoreAction> actions) { }
}
