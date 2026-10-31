package com.stockrescue.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import com.stockrescue.identity.persistence.AccountRepository;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.platform.security.SessionAccountStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountService implements SessionAccountStatus {
    private final AccountRepository accounts;
    private final AuditWriter audit;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Semaphore hashing = new Semaphore(2);
    private final String dummyHash;

    public AccountService(AccountRepository accounts, AuditWriter audit, PasswordEncoder encoder,
            TransactionTemplate transactions, Clock clock) {
        this.accounts = accounts;
        this.audit = audit;
        this.encoder = encoder;
        this.transactions = transactions;
        this.clock = clock;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public void register(String email, String password) {
        acquireHashSlot();
        try {
            var hash = encoder.encode(password);
            transactions.executeWithoutResult(status -> accounts.insert(normalize(email), hash)
                    .ifPresent(user -> audit.accountAction(user, "ACCOUNT_REGISTERED")));
        } finally {
            hashing.release();
        }
    }

    public Account authenticate(String email, String password) {
        acquireHashSlot();
        try {
            var normalized = normalize(email);
            var snapshot = accounts.credential(normalized, false);
            boolean passwordMatches = encoder.matches(password, snapshot.map(AccountRepository.Credential::hash).orElse(dummyHash));
            UUID authenticated = transactions.execute(status -> {
                var current = accounts.credential(normalized, true);
                if (current.isEmpty()) return null;
                var credential = current.orElseThrow();
                Instant now = clock.instant();
                if (!credential.active() || (credential.lockedUntil() != null && credential.lockedUntil().isAfter(now))) return null;
                if (!passwordMatches || snapshot.isEmpty() || !credential.hash().equals(snapshot.orElseThrow().hash())) {
                    accounts.failedAttempt(credential, now);
                    return null;
                }
                accounts.resetAttempts(credential.id());
                audit.accountAction(credential.id(), "CREDENTIALS_VERIFIED");
                return credential.id();
            });
            if (authenticated == null) throw new BadCredentialsException("Invalid credentials");
            return account(authenticated);
        } finally {
            hashing.release();
        }
    }

    public Account account(UUID user) {
        return accounts.activeAccount(user).orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
    }

    @Override public boolean active(UUID user) { return accounts.activeAccount(user).isPresent(); }

    private void acquireHashSlot() {
        if (!hashing.tryAcquire()) throw new AuthenticationBusyException();
    }

    private String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
}
