package com.stockrescue.identity.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.stockrescue.identity.application.Account;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<UUID> insert(String email, String hash) {
        var id = UUID.randomUUID();
        var inserted = jdbc.sql("""
                INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)
                ON CONFLICT (email_normalized) DO NOTHING RETURNING id
                """).param("id", id).param("email", email).query(UUID.class).optional();
        inserted.ifPresent(user -> jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)")
                .param("id", user).param("hash", hash).update());
        return inserted;
    }

    public Optional<Credential> credential(String email, boolean lock) {
        return jdbc.sql("""
                SELECT u.id,u.status,c.password_hash,c.failed_attempt_count,c.failure_window_started_at,c.locked_until
                FROM stockrescue.user_account u JOIN stockrescue.credential c ON c.user_id=u.id
                WHERE u.email_normalized=:email
                """ + (lock ? " FOR UPDATE OF c" : ""))
                .param("email", email).query((row, index) -> new Credential(row.getObject("id", UUID.class),
                        row.getString("status").equals("ACTIVE"), row.getString("password_hash"),
                        row.getInt("failed_attempt_count"), instant(row.getTimestamp("failure_window_started_at")),
                        instant(row.getTimestamp("locked_until")))).optional();
    }

    public void failedAttempt(Credential credential, Instant now) {
        boolean reset = credential.windowStarted() == null || !credential.windowStarted().plusSeconds(900).isAfter(now);
        int attempts = reset ? 1 : Math.min(credential.failedAttempts() + 1, 5);
        jdbc.sql("""
                UPDATE stockrescue.credential SET failed_attempt_count=:attempts,
                    failure_window_started_at=:window,locked_until=:locked WHERE user_id=:id
                """).param("attempts", attempts).param("window", java.sql.Timestamp.from(reset ? now : credential.windowStarted()))
                .param("locked", attempts >= 5 ? java.sql.Timestamp.from(now.plusSeconds(900)) : null)
                .param("id", credential.id()).update();
    }

    public void resetAttempts(UUID user) {
        jdbc.sql("UPDATE stockrescue.credential SET failed_attempt_count=0,failure_window_started_at=NULL,locked_until=NULL WHERE user_id=:id")
                .param("id", user).update();
    }

    public Optional<Account> activeAccount(UUID user) {
        return jdbc.sql("SELECT id,email_normalized,created_at FROM stockrescue.user_account WHERE id=:id AND status='ACTIVE'")
                .param("id", user).query((row, index) -> new Account(row.getObject("id", UUID.class),
                        row.getString("email_normalized"), row.getTimestamp("created_at").toInstant())).optional();
    }

    private Instant instant(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }

    public record Credential(UUID id, boolean active, String hash, int failedAttempts, Instant windowStarted, Instant lockedUntil) {
        @Override public String toString() { return "Credential[redacted]"; }
    }
}
