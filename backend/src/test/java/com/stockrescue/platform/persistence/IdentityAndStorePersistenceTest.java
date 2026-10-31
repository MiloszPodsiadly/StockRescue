package com.stockrescue.platform.persistence;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stockrescue.support.InfrastructureTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityAndStorePersistenceTest extends InfrastructureTestBase {

    private Connection connection;
    private Fixture f;

    @BeforeAll
    static void migrate() {
        var flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "stockrescue_app", "test-app-password")
                .defaultSchema("public").cleanDisabled(true).load();
        flyway.migrate();
        flyway.validate();
    }

    @BeforeEach
    void startTransaction() throws Exception {
        connection = open();
        connection.setAutoCommit(false);
        f = fixture();
    }

    @AfterEach
    void rollback() throws Exception {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void emailsCredentialsAndMembershipsHaveRealUniquenessAndStateConstraints() throws Exception {
        var user = UUID.randomUUID();
        sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (?,?)", user, "  Owner@Example.TEST  ");
        assertThat(text("SELECT email_normalized FROM stockrescue.user_account WHERE id=?", user)).isEqualTo("owner@example.test");
        reject("23505", "INSERT INTO stockrescue.user_account(id,email_display) VALUES (?,?)", UUID.randomUUID(), "OWNER@example.test");
        for (var email : new String[]{"missing-at", "two@@example.test", "a b@example.test", "ą@example.test"}) {
            reject("23514", "INSERT INTO stockrescue.user_account(id,email_display) VALUES (?,?)", UUID.randomUUID(), email);
        }
        reject("23514", "UPDATE stockrescue.user_account SET status='ADMIN' WHERE id=?", f.userA);
        reject("23503", "INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (?,?)", UUID.randomUUID(), "test-only-hash-long-enough");
        sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (?,?)", f.userA, "test-only-hash-long-enough");
        reject("23505", "INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (?,?)", f.userA, "test-only-hash-long-enough");
        reject("23514", "UPDATE stockrescue.credential SET failed_attempt_count=-1 WHERE user_id=?", f.userA);
        reject("23514", "UPDATE stockrescue.credential SET password_hash='' WHERE user_id=?", f.userA);
        reject("23505", "INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (?,?,?,'MEMBER')", UUID.randomUUID(), f.orgA, f.userA);
        sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (?,?,?,'MEMBER')", UUID.randomUUID(), f.orgB, f.userA);
        reject("23514", "UPDATE stockrescue.membership SET org_role='SUPERUSER' WHERE id=?", f.memberA);
        reject("23514", "UPDATE stockrescue.membership SET state='REVOKED' WHERE id=?", f.memberA);
        reject("23514", "UPDATE stockrescue.membership SET state='UNKNOWN' WHERE id=?", f.memberA);
        reject("23514", "UPDATE stockrescue.membership SET version=-1 WHERE id=?", f.memberA);
        reject("23503", "UPDATE stockrescue.membership SET user_id=? WHERE id=?", UUID.randomUUID(), f.memberA);
        reject("23503", "UPDATE stockrescue.membership SET org_id=? WHERE id=?", UUID.randomUUID(), f.memberA);
        reject("23514", "UPDATE stockrescue.organization SET status='DELETED' WHERE id=?", f.orgA);
        reject("23514", "UPDATE stockrescue.organization SET name='  ' WHERE id=?", f.orgA);
        reject("23514", "UPDATE stockrescue.organization SET version=-1 WHERE id=?", f.orgA);
        reject("23514", "UPDATE stockrescue.organization SET updated_at=created_at-interval '1 second' WHERE id=?", f.orgA);
        reject("23503", "UPDATE stockrescue.organization SET created_by_user_id=? WHERE id=?", UUID.randomUUID(), f.orgA);
    }

    @Test
    void storesUseOrganizationScopedCodesAndExplicitRealTimezones() throws Exception {
        assertThat(count("SELECT count(*) FROM stockrescue.organization WHERE id IN (?,?) AND name='Same Retailer'", f.orgA, f.orgB)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM stockrescue.store WHERE org_id=? AND code_normalized='NORTH'", f.orgA)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM stockrescue.store WHERE org_id=? AND id=?", f.orgA, f.storeB)).isZero();
        reject("23505", "INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (?,?,' north ','Same Store','UTC')", UUID.randomUUID(), f.orgA);
        reject("23514", "UPDATE stockrescue.store SET code='bad code' WHERE id=?", f.storeA);
        reject("23514", "UPDATE stockrescue.store SET zone_id='Mars/Olympus' WHERE id=?", f.storeA);
        reject("23514", "UPDATE stockrescue.store SET zone_id='CET' WHERE id=?", f.storeA);
        reject("23514", "UPDATE stockrescue.store SET zone_id='posix/Europe/Warsaw' WHERE id=?", f.storeA);
        reject("23514", "UPDATE stockrescue.store SET country_code='zz' WHERE id=?", f.storeA);
        reject("23514", "UPDATE stockrescue.store SET version=-1 WHERE id=?", f.storeA);
        reject("23514", "UPDATE stockrescue.store SET name=' ' WHERE id=?", f.storeA);
        reject("23514", "UPDATE stockrescue.store SET updated_at=created_at-interval '1 second' WHERE id=?", f.storeA);
        sql("UPDATE stockrescue.store SET zone_id='America/New_York' WHERE id=?", f.storeA);
        assertThat(text("SELECT to_char(timestamptz '2026-03-29 01:30:00+00' AT TIME ZONE zone_id, 'HH24:MI') FROM stockrescue.store WHERE id=?", f.storeA2)).isEqualTo("03:30");
    }

    @Test
    void everyStoreAssociationRejectsForeignOrganizationResources() throws Exception {
        reject("23503", "INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (?,?,?,'Cold','CHILLED')", UUID.randomUUID(), f.orgA, f.storeB);
        var zone = UUID.randomUUID();
        sql("INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (?,?,?,'Cold','CHILLED')", zone, f.orgA, f.storeA);
        reject("23505", "INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (?,?,?,'Cold','CHILLED')", UUID.randomUUID(), f.orgA, f.storeA);
        reject("23514", "UPDATE stockrescue.storage_zone SET temperature_class='UNKNOWN' WHERE id=?", zone);
        reject("23514", "UPDATE stockrescue.storage_zone SET name=' ' WHERE id=?", zone);
        reject("23514", "UPDATE stockrescue.storage_zone SET version=-1 WHERE id=?", zone);
        reject("23503", "INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (?,?,?,?,'STORE_MANAGER')", UUID.randomUUID(), f.orgA, f.memberB, f.storeA);
        reject("23503", "INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (?,?,?,?,'STORE_MANAGER')", UUID.randomUUID(), f.orgA, f.memberA, f.storeB);
        reject("23505", "INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (?,?,?,?,'STORE_MANAGER')", UUID.randomUUID(), f.orgA, f.memberA, f.storeA);
        reject("23514", "UPDATE stockrescue.store_grant SET role='ORG_ADMIN' WHERE id=?", f.grantA);
        reject("23514", "UPDATE stockrescue.store_grant SET state='REVOKED' WHERE id=?", f.grantA);
        reject("23514", "UPDATE stockrescue.store_grant SET version=-1 WHERE id=?", f.grantA);
        reject("23503", "INSERT INTO stockrescue.transport_lane(id,org_id,origin_store_id,destination_store_id) VALUES (?,?,?,?)", UUID.randomUUID(), f.orgA, f.storeA, f.storeB);
        reject("23503", "INSERT INTO stockrescue.transport_lane(id,org_id,origin_store_id,destination_store_id) VALUES (?,?,?,?)", UUID.randomUUID(), f.orgA, f.storeB, f.storeA);
        reject("23514", "INSERT INTO stockrescue.transport_lane(id,org_id,origin_store_id,destination_store_id) VALUES (?,?,?,?)", UUID.randomUUID(), f.orgA, f.storeA, f.storeA);
        reject("23505", "INSERT INTO stockrescue.transport_lane(id,org_id,origin_store_id,destination_store_id) VALUES (?,?,?,?)", UUID.randomUUID(), f.orgA, f.storeA, f.storeA2);
        reject("23514", "UPDATE stockrescue.transport_lane SET version=-1 WHERE id=?", f.laneA);
    }

    @Test
    void invitationsBindRecipientTenantAndImmutableGrantIntent() throws Exception {
        var invite = invitation(f.orgA, f.memberA);
        reject("23505", "INSERT INTO stockrescue.organization_invitation(id,org_id,email_normalized,token_digest,invited_by_membership_id,expires_at) SELECT ?,org_id,email_normalized,?,invited_by_membership_id,expires_at FROM stockrescue.organization_invitation WHERE id=?", UUID.randomUUID(), digest(), invite);
        reject("23505", "INSERT INTO stockrescue.organization_invitation(id,org_id,email_normalized,token_digest,invited_by_membership_id,expires_at) SELECT ?,org_id,'another@example.test',token_digest,invited_by_membership_id,expires_at FROM stockrescue.organization_invitation WHERE id=?", UUID.randomUUID(), invite);
        reject("23503", "INSERT INTO stockrescue.organization_invitation(id,org_id,email_normalized,token_digest,invited_by_membership_id,expires_at) VALUES (?,?,'x@example.test',?, ?,now()+interval '1 hour')", UUID.randomUUID(), f.orgA, digest(), f.memberB);
        reject("23514", "INSERT INTO stockrescue.organization_invitation(id,org_id,email_normalized,token_digest,invited_by_membership_id,expires_at) VALUES (?,?,'UPPER@example.test',?,?,now()+interval '1 hour')", UUID.randomUUID(), f.orgA, digest(), f.memberA);
        reject("23514", "INSERT INTO stockrescue.organization_invitation(id,org_id,email_normalized,token_digest,invited_by_membership_id,expires_at) VALUES (?,?,'x@example.test',?,?,now()-interval '1 hour')", UUID.randomUUID(), f.orgA, digest(), f.memberA);
        reject("23514", "INSERT INTO stockrescue.organization_invitation(id,org_id,email_normalized,token_digest,invited_by_membership_id,expires_at) VALUES (?,?,'x@example.test',?,?,now()+interval '1 hour')", UUID.randomUUID(), f.orgA, new byte[1], f.memberA);
        var foreignInvite = invitation(f.orgB, f.memberB);
        reject("23503", "INSERT INTO stockrescue.invitation_store_grant(org_id,invitation_id,store_id,role) VALUES (?,?,?,'READ_ONLY_ANALYST')", f.orgA, foreignInvite, f.storeA);
        reject("23503", "INSERT INTO stockrescue.invitation_store_grant(org_id,invitation_id,store_id,role) VALUES (?,?,?,'READ_ONLY_ANALYST')", f.orgA, invite, f.storeB);
        sql("INSERT INTO stockrescue.invitation_store_grant(org_id,invitation_id,store_id,role) VALUES (?,?,?,'READ_ONLY_ANALYST')", f.orgA, invite, f.storeA);
        reject("23505", "INSERT INTO stockrescue.invitation_store_grant(org_id,invitation_id,store_id,role) VALUES (?,?,?,'READ_ONLY_ANALYST')", f.orgA, invite, f.storeA);
        reject("23514", "INSERT INTO stockrescue.invitation_store_grant(org_id,invitation_id,store_id,role) VALUES (?,?,?,'ORG_ADMIN')", f.orgA, invite, f.storeA2);
        reject("55000", "UPDATE stockrescue.invitation_store_grant SET role='STORE_MANAGER' WHERE invitation_id=?", invite);
        reject("55000", "DELETE FROM stockrescue.invitation_store_grant WHERE invitation_id=?", invite);
        reject("23503", "UPDATE stockrescue.organization_invitation SET status='ACCEPTED',accepted_by_user_id=?,accepted_at=clock_timestamp() WHERE id=?", f.userB, invite);
        reject("23514", "UPDATE stockrescue.organization_invitation SET status='ACCEPTED' WHERE id=?", invite);
        sql("UPDATE stockrescue.organization_invitation SET status='ACCEPTED',accepted_by_user_id=?,accepted_at=clock_timestamp() WHERE id=?", f.invitee, invite);
        reject("55000", "UPDATE stockrescue.organization_invitation SET status='PENDING',accepted_by_user_id=NULL,accepted_at=NULL WHERE id=?", invite);
        reject("55000", "INSERT INTO stockrescue.invitation_store_grant(org_id,invitation_id,store_id,role) VALUES (?,?,?,'READ_ONLY_ANALYST')", f.orgA, invite, f.storeA2);
        reject("55000", "DELETE FROM stockrescue.organization_invitation WHERE id=?", invite);
    }

    @Test
    void expiredAndConcurrentInvitationAcceptanceCannotReplay() throws Exception {
        var expired = UUID.randomUUID();
        sql("INSERT INTO stockrescue.organization_invitation(id,org_id,email_normalized,token_digest,invited_by_membership_id,created_at,expires_at) SELECT ?,?,email_normalized,?,?,now()-interval '2 hours',now()-interval '1 hour' FROM stockrescue.user_account WHERE id=?", expired, f.orgA, digest(), f.memberA, f.invitee);
        reject("23514", "UPDATE stockrescue.organization_invitation SET status='ACCEPTED',accepted_by_user_id=?,accepted_at=created_at+interval '1 minute' WHERE id=?", f.invitee, expired);
        sql("UPDATE stockrescue.organization_invitation SET status='EXPIRED' WHERE id=?", expired);
        var invite = invitation(f.orgA, f.memberA);
        connection.commit();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> accept(invite, ready, start));
            var second = workers.submit(() -> accept(invite, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(text("SELECT status FROM stockrescue.organization_invitation WHERE org_id=? AND id=?", f.orgA, invite)).isEqualTo("ACCEPTED");
    }

    @Test
    void costRevisionsArePreciseConsecutiveAndCannotRewriteHistory() throws Exception {
        assertThat(count("SELECT count(*) FROM stockrescue.lane_revision WHERE org_id=? AND lane_id=?", f.orgA, f.laneA)).isZero();
        for (var amount : new String[]{"-0.0001", "NaN", "Infinity", "0.00001", "1000000000000000"}) {
            reject("23514", revisionSql(), UUID.randomUUID(), f.orgA, f.laneA, amount, "EUR", 20, f.memberA);
        }
        // SQL text casts allow NaN without pretending that Java BigDecimal supports it.
        reject("23514", revisionSql(), UUID.randomUUID(), f.orgA, f.laneA, BigDecimal.ONE, "eur", 20, f.memberA);
        reject("23514", revisionSql(), UUID.randomUUID(), f.orgA, f.laneA, BigDecimal.ONE, "XXX", 20, f.memberA);
        for (var duration : new int[]{0, -1}) {
            reject("23514", revisionSql(), UUID.randomUUID(), f.orgA, f.laneA, BigDecimal.ONE, "PLN", duration, f.memberA);
        }
        reject("23503", revisionSql(), UUID.randomUUID(), f.orgA, f.laneA, BigDecimal.ONE, "PLN", 20, f.memberB);
        reject("23503", revisionSql(), UUID.randomUUID(), f.orgB, f.laneA, BigDecimal.ONE, "PLN", 20, f.memberB);
        var revision = UUID.randomUUID();
        sql(revisionSql(), revision, f.orgA, f.laneA, new BigDecimal("12.3456"), "PLN", 20, f.memberA);
        assertThat(text("SELECT cost_amount::text FROM stockrescue.lane_revision WHERE id=? AND org_id=?", revision, f.orgA)).isEqualTo("12.3456");
        reject("23514", revisionSql(), UUID.randomUUID(), f.orgA, f.laneA, BigDecimal.ONE, "PLN", 20, f.memberA);
        reject("PZ002", "INSERT INTO stockrescue.lane_revision(id,org_id,lane_id,revision_no,cost_amount,currency_code,duration_minutes,effective_at,recorded_by_membership_id) VALUES (?,?,?,2,10,'PLN',20,now()-interval '1 day',?)", UUID.randomUUID(), f.orgA, f.laneA, f.memberA);
        var scheduledRevision=UUID.randomUUID();
        sql("INSERT INTO stockrescue.lane_revision(id,org_id,lane_id,revision_no,cost_amount,currency_code,duration_minutes,effective_at,recorded_by_membership_id) VALUES (?,?,?,2,15,'PLN',25,now()+interval '1 day',?)", scheduledRevision, f.orgA, f.laneA, f.memberA);
        String cancel="INSERT INTO stockrescue.lane_revision_cancellation(id,org_id,lane_id,revision_id,cancelled_by_membership_id,reason,correlation_id) VALUES (?,?,?,?,?,?,'scheduled-cost-test')";
        var cancellation=UUID.randomUUID();
        reject("23503", cancel, cancellation, f.orgB, f.laneA, scheduledRevision, f.memberB, "reviewed schedule change");
        reject("23503", cancel, cancellation, f.orgA, f.laneA, scheduledRevision, f.memberB, "reviewed schedule change");
        reject("PZ001", cancel, cancellation, f.orgA, f.laneA, revision, f.memberA, "reviewed schedule change");
        sql(cancel, cancellation, f.orgA, f.laneA, scheduledRevision, f.memberA, "reviewed schedule change");
        assertThat(text("SELECT correlation_id FROM stockrescue.lane_revision_cancellation WHERE id=?", cancellation)).isEqualTo("scheduled-cost-test");
        reject("23505", cancel, UUID.randomUUID(), f.orgA, f.laneA, scheduledRevision, f.memberA, "duplicate cancellation");
        reject("55000", "UPDATE stockrescue.lane_revision_cancellation SET reason='rewritten' WHERE id=?", cancellation);
        reject("55000", "DELETE FROM stockrescue.lane_revision_cancellation WHERE id=?", cancellation);
        reject("55000", "TRUNCATE stockrescue.lane_revision_cancellation");
        sql("INSERT INTO stockrescue.lane_revision(id,org_id,lane_id,revision_no,cost_amount,currency_code,duration_minutes,effective_at,recorded_by_membership_id) VALUES (?,?,?,3,16,'PLN',30,now()+interval '1 day',?)", UUID.randomUUID(), f.orgA, f.laneA, f.memberA);
        reject("55000", "UPDATE stockrescue.lane_revision SET cost_amount=0 WHERE id=?", revision);
        reject("55000", "DELETE FROM stockrescue.lane_revision WHERE id=?", revision);
        reject("0A000", "TRUNCATE stockrescue.lane_revision");
        reject("23001", "DELETE FROM stockrescue.transport_lane WHERE id=?", f.laneA);
        assertThat(count("SELECT count(*) FROM stockrescue.lane_revision WHERE org_id=? AND lane_id=?", f.orgA, f.laneA)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM stockrescue.lane_revision_cancellation WHERE org_id=? AND lane_id=?", f.orgA, f.laneA)).isEqualTo(1);
    }

    @Test
    void revocationsHaveOneTenantScopedTargetAndPreserveHistory() throws Exception {
        var revocation = UUID.randomUUID();
        String insert = "INSERT INTO stockrescue.revocation_record(id,org_id,membership_id,store_grant_id,revoked_by_membership_id,reason) VALUES (?,?,?,?,?,'access removed')";
        reject("23514", insert, revocation, f.orgA, null, null, f.memberA);
        reject("23514", insert, revocation, f.orgA, f.memberA, f.grantA, f.memberA);
        reject("23503", insert, revocation, f.orgA, f.memberB, null, f.memberA);
        reject("23503", insert, revocation, f.orgA, null, f.grantB, f.memberA);
        reject("23503", insert, revocation, f.orgA, f.memberA, null, f.memberB);
        sql(insert, revocation, f.orgA, null, f.grantA, f.memberA);
        reject("23514", "INSERT INTO stockrescue.revocation_record(id,org_id,membership_id,revoked_by_membership_id,reason) VALUES (?,?,?,?,' ')", UUID.randomUUID(), f.orgA, f.memberA, f.memberA);
        reject("55000", "UPDATE stockrescue.revocation_record SET reason='changed' WHERE id=?", revocation);
        reject("55000", "DELETE FROM stockrescue.revocation_record WHERE id=?", revocation);
        reject("23001", "DELETE FROM stockrescue.membership WHERE id=?", f.memberA);
        reject("23001", "DELETE FROM stockrescue.store WHERE id=?", f.storeA);
        reject("23001", "DELETE FROM stockrescue.organization WHERE id=?", f.orgA);
        reject("23001", "DELETE FROM stockrescue.user_account WHERE id=?", f.userA);
    }

    @Test
    void businessWriteAndAuditCommitOrRollbackTogether() throws Exception {
        connection.commit();
        var failedStore = UUID.randomUUID();
        var failedAudit = UUID.randomUUID();
        sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (?,?,'TX-FAIL','Transactional','UTC')", failedStore, f.orgA);
        assertThatThrownBy(() -> audit(failedAudit, failedStore, "unsafe correlation id"))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
        connection.rollback();
        assertThat(count("SELECT count(*) FROM stockrescue.store WHERE org_id=? AND id=?", f.orgA, failedStore)).isZero();
        assertThat(count("SELECT count(*) FROM stockrescue.audit_entry WHERE id=?", failedAudit)).isZero();

        var store = UUID.randomUUID();
        var audit = UUID.randomUUID();
        sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (?,?,'TX-PASS','Transactional','UTC')", store, f.orgA);
        audit(audit, store, "store-transaction");
        connection.commit();
        try (var observer = open(); var statement = observer.prepareStatement("SELECT count(*) FROM stockrescue.store s JOIN stockrescue.audit_entry a ON a.org_id=s.org_id AND a.resource_id=s.id WHERE s.org_id=? AND s.id=? AND a.id=?")) {
            statement.setObject(1, f.orgA);
            statement.setObject(2, store);
            statement.setObject(3, audit);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
        }
        reject("55000", "UPDATE stockrescue.audit_entry SET action='REWRITE' WHERE id=?", audit);
        reject("55000", "DELETE FROM stockrescue.audit_entry WHERE id=?", audit);
        reject("55000", "TRUNCATE stockrescue.audit_entry");
        reject("23514", "INSERT INTO stockrescue.audit_entry(id,actor_user_id,resource_type,resource_id,action,correlation_id,changes) VALUES (?,?,'store',?,'CREATE','audit-contract','[]')", UUID.randomUUID(), f.userA, store);
        reject("23503", "INSERT INTO stockrescue.audit_entry(id,actor_user_id,resource_type,resource_id,action,correlation_id) VALUES (?,?,'store',?,'CREATE','audit-contract')", UUID.randomUUID(), UUID.randomUUID(), store);
        reject("23503", "INSERT INTO stockrescue.audit_entry(id,actor_user_id,org_id,resource_type,resource_id,action,correlation_id) VALUES (?,?,?,'store',?,'CREATE','audit-contract')", UUID.randomUUID(), f.userA, UUID.randomUUID(), store);
        reject("23514", "INSERT INTO stockrescue.audit_entry(id,actor_user_id,resource_type,resource_id,action,correlation_id) VALUES (?,?,' ',?,'CREATE','audit-contract')", UUID.randomUUID(), f.userA, store);
        reject("23514", "INSERT INTO stockrescue.audit_entry(id,actor_user_id,resource_type,resource_id,action,correlation_id) VALUES (?,?,'store',?,' ','audit-contract')", UUID.randomUUID(), f.userA, store);
    }

    @Test
    void jdbcSessionsHaveUniqueIdsExpiryAndAttributeLifecycle() throws Exception {
        var primary = UUID.randomUUID().toString();
        var session = UUID.randomUUID().toString();
        String insert = "INSERT INTO stockrescue.spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) VALUES (?,?,1000,1000,1800,1801000,?)";
        sql(insert, primary, session, f.userA.toString());
        reject("23505", insert, UUID.randomUUID().toString(), session, f.userA.toString());
        reject("23514", "UPDATE stockrescue.spring_session SET max_inactive_interval=0 WHERE primary_id=?", primary);
        reject("23514", "UPDATE stockrescue.spring_session SET creation_time=-1 WHERE primary_id=?", primary);
        reject("23514", "UPDATE stockrescue.spring_session SET last_access_time=0 WHERE primary_id=?", primary);
        reject("23514", "UPDATE stockrescue.spring_session SET expiry_time=0 WHERE primary_id=?", primary);
        reject("23503", "INSERT INTO stockrescue.spring_session_attributes VALUES (?,'identity',?)", UUID.randomUUID().toString(), new byte[]{1});
        sql("INSERT INTO stockrescue.spring_session_attributes VALUES (?,'identity',?)", primary, new byte[]{1});
        reject("23505", "INSERT INTO stockrescue.spring_session_attributes VALUES (?,'identity',?)", primary, new byte[]{2});
        sql("DELETE FROM stockrescue.spring_session WHERE primary_id=?", primary);
        assertThat(count("SELECT count(*) FROM stockrescue.spring_session_attributes WHERE session_primary_id=?", primary)).isZero();
    }

    private int accept(UUID invite, CountDownLatch ready, CountDownLatch start) throws Exception {
        try (var other = open()) {
            other.setAutoCommit(false);
            try (var statement = other.prepareStatement("UPDATE stockrescue.organization_invitation SET status='ACCEPTED',accepted_by_user_id=?,accepted_at=clock_timestamp() WHERE org_id=? AND id=? AND status='PENDING'")) {
                statement.setObject(1, f.invitee);
                statement.setObject(2, f.orgA);
                statement.setObject(3, invite);
                statement.setQueryTimeout(10);
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Acceptance start timed out");
                var changed = statement.executeUpdate();
                other.commit();
                return changed;
            }
        }
    }

    private UUID invitation(UUID org, UUID member) throws Exception {
        var id = UUID.randomUUID();
        sql("INSERT INTO stockrescue.organization_invitation(id,org_id,email_normalized,token_digest,invited_by_membership_id,expires_at) SELECT ?,?,email_normalized,?,?,now()+interval '1 hour' FROM stockrescue.user_account WHERE id=?", id, org, digest(), member, f.invitee);
        return id;
    }

    private void audit(UUID id, UUID store, String correlation) throws Exception {
        sql("INSERT INTO stockrescue.audit_entry(id,actor_user_id,org_id,resource_type,resource_id,action,correlation_id) VALUES (?,?,?,'store',?,'CREATE',?)", id, f.userA, f.orgA, store, correlation);
    }

    private String revisionSql() {
        return "INSERT INTO stockrescue.lane_revision(id,org_id,lane_id,revision_no,cost_amount,currency_code,duration_minutes,effective_at,recorded_by_membership_id) VALUES (?,?,?,1,?::numeric,?,?,NULL,?)";
    }

    private Fixture fixture() throws Exception {
        var fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (var user : new UUID[]{fixture.userA, fixture.userB, fixture.invitee}) sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (?,?)", user, user + "@example.test");
        sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (?,'Same Retailer',?),(?,'Same Retailer',?)", fixture.orgA, fixture.userA, fixture.orgB, fixture.userB);
        sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (?,?,?,'ORG_ADMIN'),(?,?,?,'ORG_ADMIN')", fixture.memberA, fixture.orgA, fixture.userA, fixture.memberB, fixture.orgB, fixture.userB);
        sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (?,?,'NORTH','Same Store','Europe/Warsaw'),(?,?,'SOUTH','Second Store','Europe/Warsaw'),(?,?,'NORTH','Same Store','UTC')", fixture.storeA, fixture.orgA, fixture.storeA2, fixture.orgA, fixture.storeB, fixture.orgB);
        sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (?,?,?,?,'STORE_MANAGER'),(?,?,?,?,'STORE_MANAGER')", fixture.grantA, fixture.orgA, fixture.memberA, fixture.storeA, fixture.grantB, fixture.orgB, fixture.memberB, fixture.storeB);
        sql("INSERT INTO stockrescue.transport_lane(id,org_id,origin_store_id,destination_store_id) VALUES (?,?,?,?)", fixture.laneA, fixture.orgA, fixture.storeA, fixture.storeA2);
        return fixture;
    }

    private void reject(String state, String query, Object... parameters) throws Exception {
        var savepoint = connection.setSavepoint();
        try {
            assertThatThrownBy(() -> sql(query, parameters))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo(state));
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    private void sql(String query, Object... parameters) throws Exception {
        try (var statement = connection.prepareStatement(query)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            statement.executeUpdate();
        }
    }

    private String text(String query, Object... parameters) throws Exception {
        try (var statement = connection.prepareStatement(query)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private long count(String query, Object... parameters) throws Exception {
        return Long.parseLong(text(query, parameters));
    }

    private static Connection open() throws SQLException {
        var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "stockrescue_app", "test-app-password");
        connection.setSchema("stockrescue");
        return connection;
    }

    private byte[] digest() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        return ByteBuffer.allocate(32).putLong(first.getMostSignificantBits()).putLong(first.getLeastSignificantBits())
                .putLong(second.getMostSignificantBits()).putLong(second.getLeastSignificantBits()).array();
    }

    private record Fixture(UUID userA, UUID userB, UUID invitee, UUID orgA, UUID orgB, UUID memberA, UUID memberB,
                           UUID storeA, UUID storeA2, UUID storeB, UUID grantA, UUID grantB, UUID laneA) { }
}
