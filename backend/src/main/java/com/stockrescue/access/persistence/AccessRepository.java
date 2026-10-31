package com.stockrescue.access.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.stockrescue.access.application.OrganizationAccess;
import com.stockrescue.access.application.OrganizationAccess.OrganizationRole;
import com.stockrescue.access.application.EffectivePermissions.StoreRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccessRepository {
    // Every projection starts from the current actor, membership and organization.
    // The selected session ID is never substituted for these predicates.
    private static final String MEMBERSHIP = """
            FROM stockrescue.membership m
            JOIN stockrescue.organization o ON o.id=m.org_id AND o.status='ACTIVE'
            JOIN stockrescue.user_account u ON u.id=m.user_id AND u.status='ACTIVE'
            """;
    private static final String COLUMNS = "o.id,o.name,o.created_at,m.id AS membership_id,m.org_role";
    private final JdbcClient jdbc;

    public AccessRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<OrganizationAccess> organizations(UUID actor, int offset, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " " + MEMBERSHIP + """
                WHERE m.user_id=:actor AND m.state='ACTIVE'
                ORDER BY o.created_at,o.id LIMIT :limit OFFSET :offset
                """).param("actor", actor).param("limit", limit).param("offset", offset)
                .query((row, index) -> organization(row)).list();
    }

    public Optional<OrganizationAccess> organization(UUID actor, UUID organization) {
        return jdbc.sql("SELECT " + COLUMNS + " " + MEMBERSHIP + """
                WHERE m.user_id=:actor AND m.org_id=:org AND m.state='ACTIVE'
                """).param("actor", actor).param("org", organization)
                .query((row, index) -> organization(row)).optional();
    }

    public List<PermissionRow> permissions(UUID actor, UUID organization) {
        // One SQL snapshot binds every grant and role to the same membership.
        return jdbc.sql("SELECT " + COLUMNS + ",s.id AS store_id,g.role AS store_role " + MEMBERSHIP + """
                LEFT JOIN stockrescue.store_grant g ON g.org_id=m.org_id AND g.membership_id=m.id AND g.state='ACTIVE'
                LEFT JOIN stockrescue.store s ON s.org_id=g.org_id AND s.id=g.store_id AND s.active
                WHERE m.user_id=:actor AND m.org_id=:org AND m.state='ACTIVE'
                ORDER BY s.id
                """).param("actor", actor).param("org", organization)
                .query((row, index) -> new PermissionRow(organization(row), row.getObject("store_id", UUID.class),
                        row.getObject("store_id") == null ? null : StoreRole.valueOf(row.getString("store_role"))))
                .list();
    }

    public UUID assignInitialOwner(UUID actor, UUID organization) {
        return jdbc.sql("""
                INSERT INTO stockrescue.membership(id,org_id,user_id,org_role)
                SELECT :id,o.id,:actor,'ORG_ADMIN' FROM stockrescue.organization o
                WHERE o.id=:org AND o.created_by_user_id=:actor AND o.status='ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM stockrescue.membership m WHERE m.org_id=o.id)
                RETURNING id
                """).param("id", UUID.randomUUID()).param("actor", actor).param("org", organization)
                .query(UUID.class).optional().orElseThrow(IllegalStateException::new);
    }

    public void lockOrganization(UUID organization, boolean exclusive) {
        if (jdbc.sql("SELECT id FROM stockrescue.organization WHERE id=:org AND status='ACTIVE' "
                        + (exclusive ? "FOR UPDATE" : "FOR SHARE"))
                .param("org", organization).query(UUID.class).optional().isEmpty()) {
            throw new com.stockrescue.access.application.OrganizationUnavailableException();
        }
    }

    public Optional<OrganizationAccess> lockMembership(UUID actor, UUID organization) {
        return jdbc.sql("SELECT " + COLUMNS + " " + MEMBERSHIP + """
                WHERE m.user_id=:actor AND m.org_id=:org AND m.state='ACTIVE' FOR SHARE OF u,m
                """).param("actor", actor).param("org", organization)
                .query((row, index) -> organization(row)).optional();
    }

    public boolean store(UUID organization, UUID store, boolean lock) {
        return store(organization, store, lock, false);
    }

    public boolean store(UUID organization, UUID store, boolean lock, boolean includeArchived) {
        return jdbc.sql("SELECT id FROM stockrescue.store WHERE org_id=:org AND id=:store" + (includeArchived ? "" : " AND active") + (lock ? " FOR UPDATE" : ""))
                .param("org", organization).param("store", store).query(UUID.class).optional().isPresent();
    }

    public boolean activeAccount(UUID actor) {
        return jdbc.sql("SELECT id FROM stockrescue.user_account WHERE id=:id AND status='ACTIVE' FOR SHARE")
                .param("id", actor).query(UUID.class).optional().isPresent();
    }

    public Optional<StoreRole> storeRole(UUID organization, UUID membership, UUID store, boolean lock) {
        return jdbc.sql("""
                SELECT role FROM stockrescue.store_grant WHERE org_id=:org AND membership_id=:member
                  AND store_id=:store AND state='ACTIVE'
                """ + (lock ? " FOR SHARE" : ""))
                .param("org", organization).param("member", membership).param("store", store)
                .query(String.class).optional().map(StoreRole::valueOf);
    }

    private OrganizationAccess organization(ResultSet row) throws SQLException {
        return new OrganizationAccess(row.getObject("id", UUID.class), row.getString("name"),
                row.getTimestamp("created_at").toInstant(), row.getObject("membership_id", UUID.class),
                OrganizationRole.valueOf(row.getString("org_role")));
    }

    public record PermissionRow(OrganizationAccess organization, UUID storeId, StoreRole role) { }
}
