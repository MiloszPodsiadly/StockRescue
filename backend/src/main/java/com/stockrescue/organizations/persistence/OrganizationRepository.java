package com.stockrescue.organizations.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationRepository {
    private final JdbcClient jdbc;

    public OrganizationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void lockCreator(UUID actor) {
        // Serialize first bootstrap, even when concurrent requests use different keys.
        if (jdbc.sql("SELECT id FROM stockrescue.user_account WHERE id=:actor AND status='ACTIVE' FOR UPDATE")
                .param("actor", actor).query(UUID.class).optional().isEmpty()) {
            throw new BadCredentialsException("Account unavailable");
        }
    }

    public Optional<Onboarding> onboarding(UUID actor) {
        return jdbc.sql("SELECT request_id,requested_name,org_id FROM stockrescue.organization_onboarding WHERE user_id=:actor")
                .param("actor", actor).query((row, index) -> new Onboarding(row.getObject("request_id", UUID.class),
                        row.getString("requested_name"), row.getObject("org_id", UUID.class))).optional();
    }

    public UUID create(UUID actor, String name) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,:name,:actor)")
                .param("id", id).param("name", name).param("actor", actor).update();
        return id;
    }

    public void remember(UUID actor, UUID request, String name, UUID organization) {
        jdbc.sql("""
                INSERT INTO stockrescue.organization_onboarding(user_id,request_id,requested_name,org_id)
                VALUES (:actor,:request,:name,:org)
                """).param("actor", actor).param("request", request).param("name", name).param("org", organization).update();
    }

    public record Onboarding(UUID requestId, String name, UUID organizationId) { }
}
