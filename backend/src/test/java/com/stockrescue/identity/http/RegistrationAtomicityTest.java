package com.stockrescue.identity.http;

import java.util.UUID;

import com.stockrescue.identity.application.AccountService;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationAtomicityTest extends InfrastructureTestBase {
    @Autowired private AccountService accounts;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AuditWriter audit;

    @Test
    void failedAuditLeavesNeitherAccountNorCredentials() {
        var email = UUID.randomUUID() + "@example.test";
        doThrow(new IllegalStateException("Test audit failure")).when(audit).accountAction(any(), eq("ACCOUNT_REGISTERED"));
        try (var ignored = MDC.putCloseable("correlationId", "registration-rollback")) {
            assertThatThrownBy(() -> accounts.register(email, "registration-test-password")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.user_account WHERE email_normalized=:email").param("email", email).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.credential c LEFT JOIN stockrescue.user_account u ON u.id=c.user_id WHERE u.id IS NULL").query(Long.class).single()).isZero();
    }
}
