package com.stockrescue.access.persistence;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import com.stockrescue.access.application.StaffAccess.*;
import com.stockrescue.access.application.OrganizationAccess.OrganizationRole;
import com.stockrescue.access.application.EffectivePermissions.StoreRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StaffRepository {
    private final JdbcClient jdbc;
    public StaffRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Member> members(UUID org, int offset, int limit, String state) {
        return jdbc.sql("""
                SELECT m.id,m.user_id,u.email_display,m.org_role,m.state,m.version,m.pending_invitation_id
                FROM stockrescue.membership m JOIN stockrescue.user_account u ON u.id=m.user_id
                WHERE m.org_id=:org AND (:state='' OR m.state=:state) ORDER BY m.created_at,m.id LIMIT :limit OFFSET :offset
                """).param("org", org).param("offset", offset).param("limit", limit).param("state", state)
                .query((row, index) -> new Member(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                        row.getString("email_display"), OrganizationRole.valueOf(row.getString("org_role")), MembershipState.valueOf(row.getString("state")),
                        row.getLong("version"), row.getObject("pending_invitation_id", UUID.class), List.of(), List.of(), List.of())).list();
    }

    public Optional<Member> member(UUID org, UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT m.id,m.user_id,u.email_display,m.org_role,m.state,m.version,m.pending_invitation_id
                FROM stockrescue.membership m JOIN stockrescue.user_account u ON u.id=m.user_id
                WHERE m.org_id=:org AND m.id=:id
                """ + (lock ? " FOR UPDATE OF m" : ""))
                .param("org", org).param("id", id).query((row, index) -> new Member(row.getObject("id", UUID.class),
                        row.getObject("user_id", UUID.class), row.getString("email_display"), OrganizationRole.valueOf(row.getString("org_role")),
                        MembershipState.valueOf(row.getString("state")), row.getLong("version"), row.getObject("pending_invitation_id", UUID.class), List.of(), List.of(), List.of())).optional();
    }

    public List<GrantRow> grants(UUID org, List<UUID> members) {
        if (members.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT membership_id,store_id,role,state,version FROM stockrescue.store_grant
                WHERE org_id=:org AND membership_id IN (:members) ORDER BY membership_id,store_id
                """).param("org", org).param("members", members).query((row, index) -> new GrantRow(row.getObject("membership_id", UUID.class),
                        new Grant(row.getObject("store_id", UUID.class), StoreRole.valueOf(row.getString("role")), row.getString("state"), row.getLong("version")))).list();
    }

    public long activeAdmins(UUID org) {
        return jdbc.sql("""
                SELECT count(*) FROM stockrescue.membership m JOIN stockrescue.user_account u ON u.id=m.user_id AND u.status='ACTIVE'
                WHERE m.org_id=:org AND m.org_role='ORG_ADMIN' AND m.state='ACTIVE'
                """).param("org", org).query(Long.class).single();
    }

    public List<PlannedGrantRow> plannedGrants(UUID org, List<UUID> members) {
        if (members.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT m.id,g.store_id,g.role FROM stockrescue.membership m
                JOIN stockrescue.organization_invitation i ON i.org_id=m.org_id AND i.id=m.pending_invitation_id
                  AND i.status='ACCEPTED' AND i.accepted_by_user_id=m.user_id
                JOIN stockrescue.invitation_store_grant g ON g.org_id=i.org_id AND g.invitation_id=i.id
                WHERE m.org_id=:org AND m.id IN (:members) AND m.state='PENDING' ORDER BY m.id,g.store_id
                """).param("org", org).param("members", members).query((row, index) -> new PlannedGrantRow(row.getObject("id", UUID.class),
                        new PlannedGrant(row.getObject("store_id", UUID.class), StoreRole.valueOf(row.getString("role"))))).list();
    }

    public void role(UUID org, UUID id, OrganizationRole role) {
        jdbc.sql("UPDATE stockrescue.membership SET org_role=:role,version=version+1 WHERE org_id=:org AND id=:id")
                .param("org", org).param("id", id).param("role", role.name()).update();
    }

    public void revoke(UUID org, UUID id) {
        jdbc.sql("""
                UPDATE stockrescue.membership SET state='REVOKED',pending_invitation_id=NULL,revoked_at=clock_timestamp(),version=version+1
                WHERE org_id=:org AND id=:id
                """).param("org", org).param("id", id).update();
        jdbc.sql("""
                UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1
                WHERE org_id=:org AND membership_id=:id AND state='ACTIVE'
                """).param("org", org).param("id", id).update();
    }

    public Optional<UUID> pending(UUID actor, UUID org, UUID invitation) {
        var member = jdbc.sql("""
                INSERT INTO stockrescue.membership(id,org_id,user_id,org_role,state,pending_invitation_id)
                VALUES (:id,:org,:actor,'MEMBER','PENDING',:invitation)
                ON CONFLICT (org_id,user_id) DO UPDATE SET org_role='MEMBER',state='PENDING',revoked_at=NULL,
                  pending_invitation_id=:invitation,version=stockrescue.membership.version+1
                WHERE stockrescue.membership.state='REVOKED'
                RETURNING id
                """).param("id", UUID.randomUUID()).param("org", org).param("actor", actor).param("invitation", invitation)
                .query(UUID.class).optional();
        member.ifPresent(id -> jdbc.sql("""
                UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1
                WHERE org_id=:org AND membership_id=:id AND state='ACTIVE'
                """).param("org", org).param("id", id).update());
        return member;
    }

    public void approve(UUID org, UUID id) {
        jdbc.sql("UPDATE stockrescue.membership SET state='ACTIVE',pending_invitation_id=NULL,version=version+1 WHERE org_id=:org AND id=:id")
                .param("org", org).param("id", id).update();
    }

    public boolean activeAccount(UUID user) {
        return jdbc.sql("SELECT id FROM stockrescue.user_account WHERE id=:id AND status='ACTIVE' FOR SHARE")
                .param("id", user).query(UUID.class).optional().isPresent();
    }

    public void grant(UUID org, UUID member, UUID store, StoreRole role) {
        jdbc.sql("""
                INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role)
                VALUES (:id,:org,:member,:store,:role)
                ON CONFLICT (org_id,membership_id,store_id) DO UPDATE SET role=:role,state='ACTIVE',revoked_at=NULL,
                  version=stockrescue.store_grant.version+1
                """).param("id", UUID.randomUUID()).param("org", org).param("member", member).param("store", store).param("role", role.name()).update();
    }

    public UUID revokeGrant(UUID org, UUID member, UUID store) {
        return jdbc.sql("""
                UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1
                WHERE org_id=:org AND membership_id=:member AND store_id=:store AND state='ACTIVE' RETURNING id
                """).param("org", org).param("member", member).param("store", store).query(UUID.class).single();
    }

    public void touch(UUID org, UUID member) {
        jdbc.sql("UPDATE stockrescue.membership SET version=version+1 WHERE org_id=:org AND id=:member")
                .param("org", org).param("member", member).update();
    }

    public void revocation(UUID org, UUID target, boolean grant, UUID actorMembership, String reason) {
        jdbc.sql("""
                INSERT INTO stockrescue.revocation_record(id,org_id,membership_id,store_grant_id,revoked_by_membership_id,reason)
                VALUES (:id,:org,:membership,:grant,:actor,:reason)
                """).param("id", UUID.randomUUID()).param("org", org).param("membership", grant ? null : target, java.sql.Types.OTHER)
                .param("grant", grant ? target : null, java.sql.Types.OTHER).param("actor", actorMembership).param("reason", reason).update();
    }

    public List<StoreOption> stores(UUID org, int offset, int limit) {
        return jdbc.sql("SELECT id,code,name FROM stockrescue.store WHERE org_id=:org AND active ORDER BY code_normalized,id LIMIT :limit OFFSET :offset")
                .param("org", org).param("offset", offset).param("limit", limit)
                .query((row, index) -> new StoreOption(row.getObject("id", UUID.class), row.getString("code"), row.getString("name"))).list();
    }

    public record GrantRow(UUID membershipId, Grant grant) { }
    public record PlannedGrantRow(UUID membershipId, PlannedGrant grant) { }
}
