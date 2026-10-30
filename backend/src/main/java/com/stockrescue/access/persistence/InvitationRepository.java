package com.stockrescue.access.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.stockrescue.access.application.StaffAccess.Invitation;
import com.stockrescue.access.application.StaffAccess.PlannedGrant;
import com.stockrescue.access.application.EffectivePermissions.StoreRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InvitationRepository {
    private final JdbcClient jdbc;
    public InvitationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Invitation> list(UUID org, int offset, int limit) {
        return jdbc.sql("""
                SELECT id,email_normalized,CASE WHEN status='PENDING' AND expires_at<=clock_timestamp() THEN 'EXPIRED' ELSE status END AS status,
                  expires_at,version FROM stockrescue.organization_invitation
                WHERE org_id=:org ORDER BY created_at DESC,id LIMIT :limit OFFSET :offset
                """).param("org", org).param("offset", offset).param("limit", limit)
                .query((row, index) -> new Invitation(row.getObject("id", UUID.class), row.getString("email_normalized"),
                        row.getString("status"), row.getTimestamp("expires_at").toInstant(), row.getLong("version"))).list();
    }

    public boolean existingRecipient(UUID org, String email) {
        return jdbc.sql("""
                SELECT m.id FROM stockrescue.membership m JOIN stockrescue.user_account u ON u.id=m.user_id
                WHERE m.org_id=:org AND u.email_normalized=:email AND m.state IN ('ACTIVE','PENDING')
                """).param("org", org).param("email", email).query(UUID.class).optional().isPresent();
    }

    public boolean pending(UUID org, String email) {
        return jdbc.sql("""
                SELECT id FROM stockrescue.organization_invitation WHERE org_id=:org AND email_normalized=:email
                  AND status='PENDING' AND expires_at>clock_timestamp()
                """).param("org", org).param("email", email).query(UUID.class).optional().isPresent();
    }

    public List<UUID> expire(UUID org) {
        return jdbc.sql("""
                UPDATE stockrescue.organization_invitation SET status='EXPIRED',version=version+1
                WHERE org_id=:org AND status='PENDING' AND expires_at<=clock_timestamp() RETURNING id
                """).param("org", org).query(UUID.class).list();
    }

    public void create(UUID id, UUID org, String email, byte[] digest, UUID actorMembership, Instant expiry, List<PlannedGrant> grants) {
        jdbc.sql("""
                INSERT INTO stockrescue.organization_invitation(id,org_id,email_normalized,token_digest,invited_by_membership_id,expires_at)
                VALUES (:id,:org,:email,:digest,:actor,:expiry)
                """).param("id", id).param("org", org).param("email", email).param("digest", digest)
                .param("actor", actorMembership).param("expiry", java.sql.Timestamp.from(expiry)).update();
        for (var grant : grants) jdbc.sql("INSERT INTO stockrescue.invitation_store_grant(org_id,invitation_id,store_id,role) VALUES (:org,:id,:store,:role)")
                .param("org", org).param("id", id).param("store", grant.storeId()).param("role", grant.role().name()).update();
    }

    public Optional<TokenInvitation> recipient(UUID actor, byte[] digest, boolean lock) {
        return jdbc.sql("""
                SELECT i.id,i.org_id,i.expires_at FROM stockrescue.organization_invitation i
                JOIN stockrescue.user_account u ON u.id=:actor AND u.email_normalized=i.email_normalized AND u.status='ACTIVE'
                JOIN stockrescue.organization o ON o.id=i.org_id AND o.status='ACTIVE'
                JOIN stockrescue.membership issuer ON issuer.org_id=i.org_id AND issuer.id=i.invited_by_membership_id
                  AND issuer.state='ACTIVE' AND issuer.org_role='ORG_ADMIN'
                JOIN stockrescue.user_account issuer_account ON issuer_account.id=issuer.user_id AND issuer_account.status='ACTIVE'
                WHERE i.token_digest=:digest AND i.status='PENDING' AND i.expires_at>clock_timestamp()
                """ + (lock ? " FOR UPDATE OF i" : ""))
                .param("actor", actor).param("digest", digest).query((row, index) -> new TokenInvitation(row.getObject("id", UUID.class),
                        row.getObject("org_id", UUID.class), row.getTimestamp("expires_at").toInstant())).optional();
    }

    public boolean accept(UUID org, UUID id, UUID actor) {
        return jdbc.sql("""
                UPDATE stockrescue.organization_invitation SET status='ACCEPTED',accepted_by_user_id=:actor,accepted_at=clock_timestamp(),version=version+1
                WHERE org_id=:org AND id=:id AND status='PENDING' AND expires_at>clock_timestamp()
                """).param("org", org).param("id", id).param("actor", actor).update() == 1;
    }

    public Optional<Invitation> lock(UUID org, UUID id) {
        return jdbc.sql("SELECT id,email_normalized,status,expires_at,version FROM stockrescue.organization_invitation WHERE org_id=:org AND id=:id FOR UPDATE")
                .param("org", org).param("id", id).query((row, index) -> new Invitation(row.getObject("id", UUID.class),
                        row.getString("email_normalized"), row.getString("status"), row.getTimestamp("expires_at").toInstant(), row.getLong("version"))).optional();
    }

    public void revoke(UUID org, UUID id) {
        jdbc.sql("UPDATE stockrescue.organization_invitation SET status='REVOKED',version=version+1 WHERE org_id=:org AND id=:id")
                .param("org", org).param("id", id).update();
    }

    public List<PlannedGrant> grants(UUID org, UUID invitation, UUID recipient) {
        return jdbc.sql("""
                SELECT g.store_id,g.role FROM stockrescue.invitation_store_grant g
                JOIN stockrescue.organization_invitation i ON i.org_id=g.org_id AND i.id=g.invitation_id
                WHERE g.org_id=:org AND g.invitation_id=:id AND i.status='ACCEPTED' AND i.accepted_by_user_id=:recipient ORDER BY g.store_id
                """).param("org", org).param("id", invitation).param("recipient", recipient)
                .query((row, index) -> new PlannedGrant(row.getObject("store_id", UUID.class), StoreRole.valueOf(row.getString("role")))).list();
    }

    public boolean claimed(UUID org, UUID invitation, UUID recipient) {
        return jdbc.sql("SELECT id FROM stockrescue.organization_invitation WHERE org_id=:org AND id=:id AND status='ACCEPTED' AND accepted_by_user_id=:recipient")
                .param("org", org).param("id", invitation).param("recipient", recipient).query(UUID.class).optional().isPresent();
    }

    public record TokenInvitation(UUID id, UUID organizationId, Instant expiresAt) { }
}
