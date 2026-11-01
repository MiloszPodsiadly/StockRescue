package com.stockrescue.platform.audit;

import java.util.UUID;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AuditWriter {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditWriter(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void accountAction(UUID actor, String action) {
        append(actor, null, "account", actor, action);
    }

    public void organizationAction(UUID actor, UUID organization, String resourceType, UUID resource, String action) {
        append(actor, organization, resourceType, resource, action);
    }

    private void append(UUID actor, UUID organization, String resourceType, UUID resource, String action) {
        securityChange(actor, organization, resourceType, resource, action, Map.of("outcome", "SUCCEEDED"), null);
    }

    public void securityChange(UUID actor, UUID organization, String resourceType, UUID resource, String action,
            Map<String, ?> changes, String reason) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Audit must participate in the command transaction");
        }
        jdbc.sql("""
                INSERT INTO stockrescue.audit_entry(id,actor_user_id,org_id,resource_type,resource_id,action,changes,reason,correlation_id)
                VALUES (:id,:actor,:org,:type,:resource,:action,CAST(:changes AS jsonb),:reason,:correlation)
                """)
                .param("id", UUID.randomUUID()).param("actor", actor).param("action", action)
                .param("org", organization, java.sql.Types.OTHER).param("type", resourceType).param("resource", resource)
                .param("changes", json.writeValueAsString(changes)).param("reason", reason, java.sql.Types.VARCHAR)
                .param("correlation", MDC.get("correlationId")).update();
    }
}
