package com.stockrescue.access.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.InvitationTokens;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class StaffAccessSecurityTest extends InfrastructureTestBase {
    private static final String PASSWORD = "staff-access-test-passphrase";
    private static final String REASON = "Reviewed staff responsibility change";
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder encoder;
    @Autowired private AccessPolicy policy;
    @Autowired private TransactionTemplate transactions;
    @Autowired private InvitationTokens tokens;
    @MockitoSpyBean private AuditWriter audit;
    private String hash;
    private Fixture f;

    @BeforeAll void passwordFixture() { hash = encoder.encode(PASSWORD); }
    @BeforeEach void organizationFixture() throws Exception { f = new Fixture(); }

    @Test
    void everyRoleUsesCurrentOrganizationAndExplicitStoreScope() throws Exception {
        for (var actor : List.of(f.ownerA, f.manager, f.operator, f.analyst)) {
            assertThat(actor.browser().get(base(f.orgA) + "/members").statusCode()).isEqualTo(actor == f.ownerA ? 200 : 403);
            problem(actor.browser().get(base(f.orgB) + "/members"), 404, "RESOURCE_NOT_FOUND");
            policy.requireStore(actor.id, f.orgA, f.a1, StoreAction.READ_STORE, false);
            if (actor == f.ownerA || actor == f.manager) {
                transactions.executeWithoutResult(status -> policy.requireStore(actor.id, f.orgA, f.a1, StoreAction.EDIT_STORE, true));
            } else assertThatThrownBy(() -> transactions.executeWithoutResult(status -> policy.requireStore(actor.id, f.orgA, f.a1, StoreAction.EDIT_STORE, true))).isInstanceOf(AccessDeniedException.class);
            if (actor != f.ownerA) {
                assertThatThrownBy(() -> policy.requireStore(actor.id, f.orgA, f.a2, StoreAction.READ_STORE, false)).isInstanceOf(OrganizationUnavailableException.class);
                assertThatThrownBy(() -> transactions.executeWithoutResult(status -> policy.requireStore(actor.id, f.orgA, f.a1, StoreAction.ARCHIVE_STORE, true))).isInstanceOf(AccessDeniedException.class);
            }
            assertThatThrownBy(() -> policy.requireStore(actor.id, f.orgA, f.b1, StoreAction.READ_STORE, false)).isInstanceOf(OrganizationUnavailableException.class);
        }
        var stores = tree(f.ownerA.browser().get(base(f.orgA) + "/access-stores?limit=1"));
        assertThat(stores.path("items")).hasSize(1);
        assertThat(stores.path("hasMore").asBoolean()).isTrue();
        assertThat(stores.toString()).doesNotContain(f.b1.toString(), f.b2.toString());
        var all = tree(f.ownerA.browser().get(base(f.orgA) + "/members?userId=" + f.ownerB.id + "&organizationId=" + f.orgB));
        assertThat(all.toString()).doesNotContain(f.ownerB.email, f.memberB.toString(), "total");
        assertThat(tree(f.ownerA.browser().get(base(f.orgA) + "/members?offset=9999")).path("items").isEmpty()).isTrue();
        for (var invalid : List.of("?limit=101", "?offset=-1", "?state=UNKNOWN")) problem(f.ownerA.browser().get(base(f.orgA) + "/members" + invalid), 400, "INVALID_REQUEST");
    }

    @Test
    void directCrossTenantRequestsCannotReadOrChangeMembersInvitesOrGrants() throws Exception {
        var owner = f.ownerA.browser();
        problem(owner.get(base(f.orgA) + "/members/" + f.memberB), 404, "RESOURCE_NOT_FOUND");
        problem(owner.write("PATCH", member(f.orgA, f.memberB) + "/role", role("ORG_ADMIN", 0)), 404, "RESOURCE_NOT_FOUND");
        problem(owner.write("DELETE", member(f.orgA, f.memberB), version(0)), 404, "RESOURCE_NOT_FOUND");
        problem(owner.write("POST", base(f.orgB) + "/invitations", inviteBody(f.newUser(), List.of())), 404, "RESOURCE_NOT_FOUND");
        problem(owner.write("PUT", grant(f.orgA, f.memberTarget, f.b1), grantBody("STORE_MANAGER", 0, null)), 404, "RESOURCE_NOT_FOUND");
        problem(owner.write("DELETE", grant(f.orgA, f.memberTarget, f.b1), Map.of("memberVersion", 0, "grantVersion", 0, "reason", REASON)), 404, "RESOURCE_NOT_FOUND");
        var foreignInvite = f.ownerB.browser().write("POST", base(f.orgB) + "/invitations", inviteBody(f.newUser(), List.of()));
        var id = tree(foreignInvite).path("id").asString();
        problem(owner.write("DELETE", base(f.orgA) + "/invitations/" + id, version(0)), 404, "RESOURCE_NOT_FOUND");
        problem(owner.write("POST", base(f.orgA) + "/invitations", inviteBody(f.newUser(), List.of(Map.of("storeId", f.b1, "role", "READ_ONLY_ANALYST")))), 404, "RESOURCE_NOT_FOUND");
        for (var actor : List.of(f.manager, f.operator, f.analyst)) {
            problem(actor.browser().write("PATCH", member(f.orgA, f.memberTarget) + "/role", role("ORG_ADMIN", 0)), 403, "ACCESS_DENIED");
            problem(actor.browser().write("DELETE", member(f.orgA, f.memberTarget), version(0)), 403, "ACCESS_DENIED");
            problem(actor.browser().write("PUT", grant(f.orgA, f.memberTarget, f.a1), grantBody("STORE_MANAGER", 0, null)), 403, "ACCESS_DENIED");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.store_grant WHERE membership_id=:id").param("id", f.memberTarget).query(Long.class).single()).isZero();
    }

    @Test
    void claimingAnInviteGivesNoAuthorityUntilAnAdminVerifiesTheRecipientAccount(CapturedOutput logs) throws Exception {
        var recipient = f.newUser();
        var issued = issue(f.ownerA, f.orgA, recipient, List.of(Map.of("storeId", f.a2, "role", "INVENTORY_OPERATOR")));
        var token = issued.path("token").asString();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        var digest = jdbc.sql("SELECT token_digest FROM stockrescue.organization_invitation WHERE id=:id").param("id", UUID.fromString(issued.path("id").asString())).query(byte[].class).single();
        assertThat(digest).hasSize(32).isEqualTo(tokens.digest(token));
        problem(f.ownerA.browser().write("POST", base(f.orgA) + "/invitations", inviteBody(recipient, List.of())), 409, "ACCESS_CONFLICT");
        problem(f.target.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", token)), 404, "INVITATION_UNAVAILABLE");
        var claim = recipient.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", token));
        assertThat(claim.statusCode()).isEqualTo(202);
        var membership = UUID.fromString(tree(claim).path("membershipId").asString());
        problem(recipient.browser().get(base(f.orgA)), 404, "RESOURCE_NOT_FOUND");
        problem(recipient.browser().write("PATCH", member(f.orgA, membership) + "/role", role("ORG_ADMIN", 0)), 404, "RESOURCE_NOT_FOUND");
        var pending = tree(f.ownerA.browser().get(member(f.orgA, membership)));
        assertThat(pending.path("state").asString()).isEqualTo("PENDING");
        assertThat(pending.path("grants").isEmpty()).isTrue();
        assertThat(pending.path("plannedGrants")).hasSize(1);
        problem(f.ownerA.browser().write("POST", member(f.orgA, membership) + "/approve", Map.of("verifiedUserId", f.target.id, "version", 0, "reason", REASON)), 409, "ACCESS_CONFLICT");
        var approved = f.ownerA.browser().write("POST", member(f.orgA, membership) + "/approve", Map.of("verifiedUserId", recipient.id, "version", 0, "reason", REASON));
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(tree(approved).path("role").asString()).isEqualTo("MEMBER");
        assertThat(tree(approved).path("grants")).hasSize(1);
        policy.requireStore(recipient.id, f.orgA, f.a2, StoreAction.READ_STORE, false);
        assertThatThrownBy(() -> policy.requireStore(recipient.id, f.orgA, f.a1, StoreAction.READ_STORE, false)).isInstanceOf(OrganizationUnavailableException.class);
        problem(recipient.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", token)), 404, "INVITATION_UNAVAILABLE");
        var history = f.ownerA.browser().get(base(f.orgA) + "/invitations");
        assertThat(history.body()).doesNotContain(token, "token_digest");
        assertThat(logs.getAll()).doesNotContain(token, recipient.email, PASSWORD);
        var changes = jdbc.sql("SELECT changes::text FROM stockrescue.audit_entry WHERE resource_id=:id ORDER BY occurred_at")
                .param("id", membership).query(String.class).list();
        assertThat(changes).anySatisfy(change -> assertThat(change).contains("PENDING", "ACTIVE", "INVENTORY_OPERATOR").doesNotContain(recipient.email, token));
    }

    @Test
    void concurrentAcceptanceConsumesOneSecretAndExpiredRevokedOrFormerAdminInvitesStayUnusable() throws Exception {
        var recipient = f.newUser();
        var issued = issue(f.ownerA, f.orgA, recipient, List.of());
        var token = issued.path("token").asString();
        var anotherSession = new Browser(recipient);
        var results = concurrent(() -> recipient.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", token)),
                () -> anotherSession.write("POST", "/api/v1/invitations/accept", Map.of("token", token)));
        assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(202, 404);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.membership WHERE user_id=:id AND org_id=:org").param("id", recipient.id).param("org", f.orgA).query(Long.class).single()).isEqualTo(1);
        var revokedRecipient = f.newUser();
        var revoked = issue(f.ownerA, f.orgA, revokedRecipient, List.of());
        assertThat(f.ownerA.browser().write("DELETE", base(f.orgA) + "/invitations/" + revoked.path("id").asString(), version(0)).statusCode()).isEqualTo(204);
        problem(revokedRecipient.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", revoked.path("token").asString())), 404, "INVITATION_UNAVAILABLE");
        var expiredRecipient = f.newUser();
        var expiredToken = tokens.generate();
        jdbc.sql("""
                INSERT INTO stockrescue.organization_invitation(id,org_id,email_normalized,token_digest,invited_by_membership_id,created_at,expires_at)
                VALUES (:id,:org,:email,:digest,:member,clock_timestamp()-INTERVAL '2 days',clock_timestamp()-INTERVAL '1 day')
                """).param("id", UUID.randomUUID()).param("org", f.orgA).param("email", expiredRecipient.email).param("digest", tokens.digest(expiredToken)).param("member", f.adminA).update();
        problem(expiredRecipient.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", expiredToken)), 404, "INVITATION_UNAVAILABLE");
        var formerRecipient = f.newUser();
        var former = issue(f.ownerA, f.orgA, formerRecipient, List.of());
        var admin = f.newUser();
        f.member(admin, f.orgA, "ORG_ADMIN");
        assertThat(admin.browser().write("PATCH", member(f.orgA, f.adminA) + "/role", role("MEMBER", 0)).statusCode()).isEqualTo(200);
        problem(formerRecipient.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", former.path("token").asString())), 404, "INVITATION_UNAVAILABLE");
    }

    @Test
    void aRevokedMembershipCanRejoinOnlyThroughReviewAndOldGrantsNeverReactivate() throws Exception {
        var secondMembership = f.member(f.manager, f.orgB, "MEMBER");
        var managerMembership = f.membership(f.manager, f.orgA);
        assertThat(f.ownerA.browser().write("DELETE", member(f.orgA, managerMembership), version(0)).statusCode()).isEqualTo(200);
        problem(f.manager.browser().get(base(f.orgA)), 404, "RESOURCE_NOT_FOUND");
        assertThat(f.manager.browser().get(base(f.orgB)).statusCode()).isEqualTo(200);
        assertThat(f.manager.browser().get("/api/v1/auth/me").statusCode()).isEqualTo(200);
        var issued = issue(f.ownerA, f.orgA, f.manager, List.of(Map.of("storeId", f.a2, "role", "READ_ONLY_ANALYST")));
        var claim = f.manager.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", issued.path("token").asString()));
        assertThat(claim.statusCode()).isEqualTo(202);
        var pending = tree(f.ownerA.browser().get(member(f.orgA, managerMembership)));
        var approval = f.ownerA.browser().write("POST", member(f.orgA, managerMembership) + "/approve", Map.of("verifiedUserId", f.manager.id, "version", pending.path("version").asLong(), "reason", REASON));
        assertThat(approval.statusCode()).isEqualTo(200);
        policy.requireStore(f.manager.id, f.orgA, f.a2, StoreAction.READ_STORE, false);
        assertThatThrownBy(() -> policy.requireStore(f.manager.id, f.orgA, f.a1, StoreAction.READ_STORE, false)).isInstanceOf(OrganizationUnavailableException.class);
        assertThat(tree(approval).path("grants").toString()).contains("REVOKED", "READ_ONLY_ANALYST");
        assertThat(jdbc.sql("SELECT state FROM stockrescue.membership WHERE id=:id").param("id", secondMembership).query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    void concurrentLastAdministratorChangesAndSelfGrantAttemptsCannotElevateOrRemoveAllAdmins() throws Exception {
        problem(f.ownerA.browser().write("DELETE", member(f.orgA, f.adminA), version(0)), 409, "ACCESS_CONFLICT");
        problem(f.ownerA.browser().write("PATCH", member(f.orgA, f.adminA) + "/role", role("MEMBER", 0)), 409, "ACCESS_CONFLICT");
        problem(f.ownerA.browser().write("PUT", grant(f.orgA, f.adminA, f.a1), grantBody("STORE_MANAGER", 0, null)), 403, "ACCESS_DENIED");
        problem(f.manager.browser().write("PATCH", member(f.orgA, f.membership(f.manager, f.orgA)) + "/role", role("ORG_ADMIN", 0)), 403, "ACCESS_DENIED");
        var second = f.newUser();
        var secondMembership = f.member(second, f.orgA, "ORG_ADMIN");
        var responses = concurrent(() -> f.ownerA.browser().write("PATCH", member(f.orgA, f.adminA) + "/role", role("MEMBER", 0)),
                () -> second.browser().write("PATCH", member(f.orgA, secondMembership) + "/role", role("MEMBER", 0)));
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.membership WHERE org_id=:org AND state='ACTIVE' AND org_role='ORG_ADMIN'")
                .param("org", f.orgA).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void currentVersionsGrantRevocationAndRoleDowngradeOverrideOldSessions() throws Exception {
        var owner = f.ownerA.browser();
        var set = owner.write("PUT", grant(f.orgA, f.memberTarget, f.a1), grantBody("STORE_MANAGER", 0, null));
        assertThat(set.statusCode()).isEqualTo(200);
        problem(owner.write("PUT", grant(f.orgA, f.memberTarget, f.a1), grantBody("READ_ONLY_ANALYST", 0, 0L)), 409, "ACCESS_CONFLICT");
        var revoked = owner.write("DELETE", grant(f.orgA, f.memberTarget, f.a1), Map.of("memberVersion", 1, "grantVersion", 0, "reason", REASON));
        assertThat(revoked.statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> policy.requireStore(f.target.id, f.orgA, f.a1, StoreAction.READ_STORE, false)).isInstanceOf(OrganizationUnavailableException.class);
        assertThat(owner.write("PATCH", member(f.orgA, f.memberTarget) + "/role", role("ORG_ADMIN", 2)).statusCode()).isEqualTo(200);
        assertThat(f.target.browser().get(base(f.orgA) + "/members").statusCode()).isEqualTo(200);
        assertThat(owner.write("PATCH", member(f.orgA, f.memberTarget) + "/role", role("MEMBER", 3)).statusCode()).isEqualTo(200);
        problem(f.target.browser().write("PUT", grant(f.orgA, f.membership(f.manager, f.orgA), f.a2), grantBody("STORE_MANAGER", 0, null)), 403, "ACCESS_DENIED");
        assertThat(f.target.browser().get("/api/v1/auth/me").statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.revocation_record WHERE org_id=:org").param("org", f.orgA).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aPriorCommittedRevocationRejectsAWriteAlreadyWaitingForAuthorization() throws Exception {
        orderedRevocation(false);
    }

    @Test
    void anAuthorizedWriteCommitsBeforeTheRevocationWaitingBehindIt() throws Exception {
        orderedRevocation(true);
    }

    private void orderedRevocation(boolean writeFirst) throws Exception {
        var second = f.newUser();
        var membership = f.member(second, f.orgA, "ORG_ADMIN");
        var writer = second.browser();
        var revoker = f.ownerA.browser();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blockedAction = writeFirst ? "STORE_GRANT_CHANGED" : "MEMBERSHIP_REVOKED";
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test authorization barrier timed out");
            return call.callRealMethod();
        }).when(audit).securityChange(any(), eq(f.orgA), any(), any(), eq(blockedAction), anyMap(), anyString());
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HttpResponse<String>> write = () -> writer.write("PUT", grant(f.orgA, f.memberTarget, f.a1), grantBody("READ_ONLY_ANALYST", 0, null));
            java.util.concurrent.Callable<HttpResponse<String>> revoke = () -> revoker.write("DELETE", member(f.orgA, membership), version(0));
            var first = workers.submit(writeFirst ? write : revoke);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var secondRequest = workers.submit(writeFirst ? revoke : write);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(jdbc.sql("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                      AND query LIKE '%stockrescue.organization%'
                    """).query(Long.class).single()).isPositive());
            assertThat(secondRequest.isDone()).isFalse();
            release.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(secondRequest.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(writeFirst ? 200 : 404);
        } finally { release.countDown(); }
        problem(writer.write("PUT", grant(f.orgA, f.memberTarget, f.a2), grantBody("STORE_MANAGER", writeFirst ? 1 : 0, null)), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action='STORE_GRANT_CHANGED'")
                .param("org", f.orgA).query(Long.class).single()).isEqualTo(writeFirst ? 1 : 0);
    }

    @Test
    void failedSecurityAuditRollsBackInvitationsRolesAndGrants() throws Exception {
        var recipient = f.newUser();
        doThrow(new IllegalStateException("Test audit failure")).when(audit).securityChange(any(), eq(f.orgA), any(), any(), anyString(), anyMap(), anyString());
        problem(f.ownerA.browser().write("POST", base(f.orgA) + "/invitations", inviteBody(recipient, List.of())), 500, "INTERNAL_ERROR");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.organization_invitation WHERE org_id=:org").param("org", f.orgA).query(Long.class).single()).isZero();
        problem(f.ownerA.browser().write("PATCH", member(f.orgA, f.memberTarget) + "/role", role("ORG_ADMIN", 0)), 500, "INTERNAL_ERROR");
        problem(f.ownerA.browser().write("PUT", grant(f.orgA, f.memberTarget, f.a1), grantBody("STORE_MANAGER", 0, null)), 500, "INTERNAL_ERROR");
        assertThat(jdbc.sql("SELECT org_role FROM stockrescue.membership WHERE id=:id").param("id", f.memberTarget).query(String.class).single()).isEqualTo("MEMBER");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.store_grant WHERE membership_id=:id").param("id", f.memberTarget).query(Long.class).single()).isZero();
    }

    @Test
    void failedClaimAndApprovalAuditCannotConsumeASecretOrActivatePendingAccess() throws Exception {
        var recipient = f.newUser();
        var issued = issue(f.ownerA, f.orgA, recipient, List.of(Map.of("storeId", f.a1, "role", "STORE_MANAGER")));
        var invitation = UUID.fromString(issued.path("id").asString());
        doThrow(new IllegalStateException("Test claim audit failure")).when(audit).securityChange(eq(recipient.id), eq(f.orgA), any(), any(), eq("INVITATION_CLAIMED"), anyMap(), anyString());
        problem(recipient.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", issued.path("token").asString())), 500, "INTERNAL_ERROR");
        assertThat(jdbc.sql("SELECT status FROM stockrescue.organization_invitation WHERE id=:id").param("id", invitation).query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.membership WHERE org_id=:org AND user_id=:user").param("org", f.orgA).param("user", recipient.id).query(Long.class).single()).isZero();
        doCallRealMethod().when(audit).securityChange(eq(recipient.id), eq(f.orgA), any(), any(), eq("INVITATION_CLAIMED"), anyMap(), anyString());
        var claim = recipient.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", issued.path("token").asString()));
        var membership = UUID.fromString(tree(claim).path("membershipId").asString());
        doThrow(new IllegalStateException("Test approval audit failure")).when(audit).securityChange(eq(f.ownerA.id), eq(f.orgA), any(), any(), eq("MEMBERSHIP_APPROVED"), anyMap(), anyString());
        problem(f.ownerA.browser().write("POST", member(f.orgA, membership) + "/approve", Map.of("verifiedUserId", recipient.id, "version", 0, "reason", REASON)), 500, "INTERNAL_ERROR");
        assertThat(jdbc.sql("SELECT state FROM stockrescue.membership WHERE id=:id").param("id", membership).query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.store_grant WHERE membership_id=:id").param("id", membership).query(Long.class).single()).isZero();
    }

    @Test
    void csrfForgedInputsAndDurableInvitationLimitsRejectUnsafeRequests() throws Exception {
        var owner = f.ownerA.browser();
        problem(owner.send("PATCH", member(f.orgA, f.memberTarget) + "/role", json.writeValueAsString(role("ORG_ADMIN", 0)), null), 403, "CSRF_INVALID");
        problem(owner.write("PATCH", member(f.orgA, f.memberTarget) + "/role", Map.of("role", "ORG_ADMIN", "reason", REASON)), 400, "INVALID_REQUEST");
        problem(owner.write("PATCH", member(f.orgA, f.memberTarget) + "/role", Map.of("role", "ORG_ADMIN", "version", 0, "reason", REASON, "actorId", f.ownerB.id)), 400, "INVALID_REQUEST");
        problem(owner.write("PATCH", member(f.orgA, f.memberTarget) + "/role", Map.of("role", "ORG_ADMIN", "version", 0, "reason", "Invalid\u0000reason")), 400, "INVALID_REQUEST");
        assertThat(tree(owner.get(member(f.orgA, f.memberTarget))).path("version").asLong()).isZero();
        var recipient = f.newUser();
        for (int attempt = 0; attempt < 20; attempt++) problem(recipient.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", tokens.generate())), 404, "INVITATION_UNAVAILABLE");
        problem(recipient.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", tokens.generate())), 429, "ACCESS_RATE_LIMITED");
        assertThat(jdbc.sql("SELECT attempts FROM stockrescue.access_rate_limit WHERE actor_user_id=:id AND operation='REDEEM'").param("id", recipient.id).query(Integer.class).single()).isEqualTo(21);
        problem(f.target.browser().write("POST", "/api/v1/invitations/accept", Map.of("token", tokens.generate())), 404, "INVITATION_UNAVAILABLE");
        var body = inviteBody(f.newUser(), List.of());
        for (int attempt = 0; attempt < 10; attempt++) assertThat(owner.write("POST", base(f.orgA) + "/invitations", body).statusCode()).isEqualTo(attempt == 0 ? 201 : 409);
        problem(owner.write("POST", base(f.orgA) + "/invitations", body), 429, "ACCESS_RATE_LIMITED");
    }

    private List<HttpResponse<String>> concurrent(java.util.concurrent.Callable<HttpResponse<String>> one, java.util.concurrent.Callable<HttpResponse<String>> two) throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return one.call(); });
            var second = workers.submit(() -> { start.await(); return two.call(); });
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }
    private JsonNode issue(User owner, UUID org, User recipient, List<Map<String, Object>> grants) throws Exception {
        var response = owner.browser().write("POST", base(org) + "/invitations", inviteBody(recipient, grants));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return tree(response);
    }
    private Map<String, Object> inviteBody(User user, List<?> grants) { return Map.of("email", user.email, "storeGrants", grants, "reason", REASON); }
    private Map<String, Object> role(String role, long version) { return Map.of("role", role, "version", version, "reason", REASON); }
    private Map<String, Object> version(long version) { return Map.of("version", version, "reason", REASON); }
    private Map<String, Object> grantBody(String role, long memberVersion, Long grantVersion) {
        var body = new java.util.HashMap<String, Object>(Map.of("role", role, "memberVersion", memberVersion, "reason", REASON));
        if (grantVersion != null) body.put("grantVersion", grantVersion);
        return body;
    }
    private String base(UUID org) { return "/api/v1/organizations/" + org; }
    private String member(UUID org, UUID member) { return base(org) + "/members/" + member; }
    private String grant(UUID org, UUID member, UUID store) { return member(org, member) + "/store-grants/" + store; }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(tree(response).path("code").asString()).isEqualTo(code);
        assertThat(tree(response).path("correlationId").asString()).isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
    }

    private class Fixture {
        final User ownerA = newUser(), ownerB = newUser(), manager = newUser(), operator = newUser(), analyst = newUser(), target = newUser();
        final UUID orgA = org(ownerA), orgB = org(ownerB);
        final UUID adminA = member(ownerA, orgA, "ORG_ADMIN"), memberB = member(ownerB, orgB, "ORG_ADMIN"), memberTarget = member(target, orgA, "MEMBER");
        final UUID a1 = store(orgA), a2 = store(orgA), b1 = store(orgB), b2 = store(orgB);
        Fixture() {
            for (var pair : List.of(Map.entry(manager, "STORE_MANAGER"), Map.entry(operator, "INVENTORY_OPERATOR"), Map.entry(analyst, "READ_ONLY_ANALYST"))) {
                var member = member(pair.getKey(), orgA, "MEMBER");
                jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,:role)")
                        .param("id", UUID.randomUUID()).param("org", orgA).param("member", member).param("store", a1).param("role", pair.getValue()).update();
            }
        }
        User newUser() {
            var user = new User(UUID.randomUUID(), UUID.randomUUID() + "@example.test");
            jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)").param("id", user.id).param("email", user.email).update();
            jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)").param("id", user.id).param("hash", hash).update();
            return user;
        }
        UUID org(User owner) {
            var id = UUID.randomUUID();
            jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Security matrix cooperative',:owner)").param("id", id).param("owner", owner.id).update();
            return id;
        }
        UUID member(User user, UUID org, String role) {
            var id = UUID.randomUUID();
            jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:user,:role)").param("id", id).param("org", org).param("user", user.id).param("role", role).update();
            return id;
        }
        UUID membership(User user, UUID org) { return jdbc.sql("SELECT id FROM stockrescue.membership WHERE org_id=:org AND user_id=:user").param("org", org).param("user", user.id).query(UUID.class).single(); }
        UUID store(UUID org) {
            var id = UUID.randomUUID();
            jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:id,:org,:code,'Scoped store','Europe/Warsaw')")
                    .param("id", id).param("org", org).param("code", id.toString().replace("-", "")).update();
            return id;
        }
    }
    private class User {
        final UUID id;
        final String email;
        private Browser browser;
        User(UUID id, String email) { this.id = id; this.email = email; }
        Browser browser() throws Exception { if (browser == null) browser = new Browser(this); return browser; }
    }
    private class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        Browser(User user) throws Exception {
            var response = write("POST", "/api/v1/auth/sign-in", Map.of("email", user.email, "password", PASSWORD));
            assertThat(response.statusCode()).isEqualTo(200);
        }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, "", null); }
        HttpResponse<String> write(String method, String path, Object body) throws Exception {
            var token = tree(get("/api/v1/auth/csrf")).path("token").asString();
            return send(method, path, json.writeValueAsString(body), token);
        }
        HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json");
            if (token != null) request.header("X-CSRF-TOKEN", token);
            return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
