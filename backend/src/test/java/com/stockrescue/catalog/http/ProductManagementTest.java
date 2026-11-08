package com.stockrescue.catalog.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.stockrescue.catalog.persistence.ProductRepository;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductManagementTest extends InfrastructureTestBase {
    private static final String PASSWORD="product-catalog-test-passphrase";
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ProductRepository products;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private AuditWriter audit;
    private String hash;
    private Fixture f;
    @BeforeAll void credentials() { hash=encoder.encode(PASSWORD); }
    @BeforeEach void fixture() { f=new Fixture(); }

    @Test
    void organizationCatalogRightsDoNotTurnStoreGrantsIntoCatalogManagement() throws Exception {
        var owner=f.ownerA.browser();
        var product=create(owner,"DAIRY");
        var anonymous=new Browser();
        problem(anonymous.get(base(f.orgA)),401,"AUTH_REQUIRED");
        problem(owner.send("POST",base(f.orgA),body(UUID.randomUUID(),"MISSING-CSRF"),null),403,"CSRF_INVALID");
        for (var member:List.of(f.member,f.manager,f.operator,f.analyst)) {
            var browser=member.browser();
            var read=browser.get(path(f.orgA,product));
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(tree(read).path("allowedActions").toString()).isEqualTo("[\"READ_CATALOG\"]");
            assertThat(tree(browser.get(base(f.orgA))).path("canCreate").asBoolean()).isFalse();
            problem(browser.write("POST",base(f.orgA),body(UUID.randomUUID(),"FORBIDDEN")),403,"ACCESS_DENIED");
            problem(browser.write("POST",path(f.orgA,product)+"/archive",archive(UUID.randomUUID(),0)),403,"ACCESS_DENIED");
            problem(browser.write("POST",path(f.orgA,product)+"/classification-evidence/operator-confirmations",categoryConfirmation(UUID.randomUUID(),0,"BAKERY")),403,"ACCESS_DENIED");
        }
        problem(f.ownerB.browser().get(path(f.orgA,product)),404,"RESOURCE_NOT_FOUND");
        problem(owner.get(path(f.orgB,product)),404,"RESOURCE_NOT_FOUND");
        problem(f.ownerB.browser().get(path(f.orgB,product)),404,"RESOURCE_NOT_FOUND");
        problem(f.ownerB.browser().write("POST",path(f.orgB,product)+"/archive",archive(UUID.randomUUID(),0)),404,"RESOURCE_NOT_FOUND");
        problem(f.ownerB.browser().write("POST",base(f.orgA),body(UUID.randomUUID(),"FOREIGN")),404,"RESOURCE_NOT_FOUND");
        jdbc.sql("UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE org_id=:org").param("org",f.orgA).update();
        assertThat(f.manager.browser().get(path(f.orgA,product)).statusCode()).isEqualTo(200);
        problem(f.manager.browser().write("POST",base(f.orgA),body(UUID.randomUUID(),"STILL-FORBIDDEN")),403,"ACCESS_DENIED");
    }

    @Test
    void invalidCatalogValuesAndUnsupportedUnitsLeaveNoDurableCommand() throws Exception {
        var owner=f.ownerA.browser();
        for (var change:List.of(Map.of("baseUnit","KG"),Map.of("gtin","036000291453"),Map.of("sku","café"),Map.of("name","bad\u0085name"))) {
            var body=body(UUID.randomUUID(),"INVALID");
            body.putAll(change);
            problem(owner.write("POST",base(f.orgA),body),422,"INVALID_REQUEST");
        }
        problem(owner.get(base(f.orgA)+"?limit=0"),400,"INVALID_REQUEST");
        problem(owner.get(base(f.orgA)+"?limit=101"),400,"INVALID_REQUEST");
        problem(owner.get(base(f.orgA)+"?state=UNKNOWN"),400,"INVALID_REQUEST");
        assertThat(count("product")).isZero();
        assertThat(count("product_command")).isZero();
    }

    @Test
    void canonicalCreationReplayRecoversOneIdentityAndRejectsChangedPayloads() throws Exception {
        var owner=f.ownerA.browser();
        var request=UUID.randomUUID();
        var body=body(request," milk-1 ");
        body.put("name","  Fresh milk  ");
        var created=owner.write("POST",base(f.orgA),body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var expected=tree(created);
        assertThat(expected.path("sku").asString()).isEqualTo("MILK-1");
        assertThat(expected.path("name").asString()).isEqualTo("Fresh milk");
        assertThat(expected.path("gtin").asString()).isEqualTo("036000291452");
        body.put("sku","MILK-1"); body.put("name","Fresh milk");
        var replay=owner.write("POST",base(f.orgA),body);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(tree(replay)).isEqualTo(expected);
        body.put("name","Altered intent");
        problem(owner.write("POST",base(f.orgA),body),409,"CATALOG_CONFLICT");
        problem(owner.write("POST",base(f.orgA),bodyWithoutGtin(UUID.randomUUID(),"MILK-1")),409,"CATALOG_CONFLICT");
        assertThat(count("product")).isEqualTo(1);
        assertThat(count("product_command")).isEqualTo(1);
        assertThat(audits("PRODUCT_CREATED")).isEqualTo(1);
        var secondOrganizationPreview=tree(f.ownerB.browser().get(gtinReview(f.orgB,"036000291452")));
        assertThat(secondOrganizationPreview.path("matches")).isEmpty();
        assertThat(secondOrganizationPreview.path("matchSetRevision").isNull()).isTrue();
        assertThat(f.ownerB.browser().write("POST",base(f.orgB),body(request,"MILK-1")).statusCode()).isEqualTo(201);
        f.member(f.ownerB,f.orgA,"ORG_ADMIN",null);
        var duplicateOwner=f.ownerB.browser();var duplicate=body(UUID.randomUUID(),"OTHER-ACTOR");
        var duplicateReview=tree(duplicateOwner.get(gtinReview(f.orgA,"036000291452")));
        duplicate.put("gtinReviewAcknowledged",true);
        duplicate.put("gtinReviewRevision",duplicateReview.path("matchSetRevision").asString());
        assertThat(duplicateOwner.write("POST",base(f.orgA),duplicate).statusCode()).isEqualTo(201);
    }

    @Test
    void concurrentDuplicateRequestsCommitOneProductOutcomeAndAudit() throws Exception {
        var first=f.ownerA.browser();
        var second=new Browser(f.ownerA);
        var body=body(UUID.randomUUID(),"SAME-REQUEST");
        var responses=concurrent(() -> first.write("POST",base(f.orgA),body),() -> second.write("POST",base(f.orgA),body));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,200);
        assertThat(tree(responses.get(0)).path("id")).isEqualTo(tree(responses.get(1)).path("id"));
        assertThat(count("product")).isEqualTo(1);
        assertThat(count("product_command")).isEqualTo(1);
        assertThat(audits("PRODUCT_CREATED")).isEqualTo(1);
        var collisions=concurrent(() -> first.write("POST",base(f.orgA),bodyWithoutGtin(UUID.randomUUID(),"SKU-RACE")),
                () -> second.write("POST",base(f.orgA),bodyWithoutGtin(UUID.randomUUID(),"sku-race")));
        assertThat(collisions.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,409);
        assertThat(count("product")).isEqualTo(2);
        assertThat(count("product_command")).isEqualTo(2);
    }

    @Test
    void archiveReplaysItsCommittedOutcomeWhilePreservingSkuAndVersionHistory() throws Exception {
        var owner=f.ownerA.browser();
        var request=UUID.randomUUID();
        var create=body(request,"ARCHIVE");
        var product=UUID.fromString(tree(owner.write("POST",base(f.orgA),create)).path("id").asString());
        var archive=archive(request,0);
        var result=owner.write("POST",path(f.orgA,product)+"/archive",archive);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(tree(result).path("active").asBoolean()).isFalse();
        assertThat(tree(result).path("version").asLong()).isEqualTo(1);
        assertThat(tree(result).path("archivedAt").isNull()).isFalse();
        assertThat(tree(owner.write("POST",path(f.orgA,product)+"/archive",archive))).isEqualTo(tree(result));
        archive.put("reason","Changed intent");
        problem(owner.write("POST",path(f.orgA,product)+"/archive",archive),409,"CATALOG_CONFLICT");
        problem(owner.write("POST",path(f.orgA,product)+"/archive",archive(UUID.randomUUID(),0)),409,"CATALOG_INACTIVE");
        problem(owner.write("POST",base(f.orgA),bodyWithoutGtin(UUID.randomUUID(),"ARCHIVE")),409,"CATALOG_CONFLICT");
        assertThat(tree(owner.write("POST",base(f.orgA),create))).isEqualTo(tree(result));
        assertThat(tree(owner.get(path(f.orgA,product)))).isEqualTo(tree(result));
        assertThat(audits("PRODUCT_ARCHIVED")).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND resource_id=:id
                    AND actor_user_id=:actor AND action='PRODUCT_ARCHIVED' AND reason='Discontinued product reviewed'
                    AND changes#>>'{before,active}'='true' AND changes#>>'{after,active}'='false'
                    AND changes#>>'{before,version}'='0' AND changes#>>'{after,version}'='1'
                """).param("org",f.orgA).param("id",product).param("actor",f.ownerA.id).query(Long.class).single()).isEqualTo(1);
        assertThat(count("product_command")).isEqualTo(2);
        var other=create(owner,"STALE");
        problem(owner.write("POST",path(f.orgA,other)+"/archive",archive(UUID.randomUUID(),1)),409,"CATALOG_CONFLICT");
        assertThat(tree(owner.get(path(f.orgA,other))).path("version").asLong()).isZero();
    }

    @Test
    void operatorSafetyAttributeConfirmationUsesSourceEvidenceVersionedReplayAndImmutableAudit() throws Exception {
        var owner=f.ownerA.browser();
        var product=create(owner,"CATEGORY-CONFIRMATION");
        var request=UUID.randomUUID();
        var confirmation=categoryConfirmation(request,0,"BAKERY");
        var endpoint=path(f.orgA,product)+"/classification-evidence/operator-confirmations";
        var saved=owner.write("POST",endpoint,confirmation);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);
        var expected=tree(saved);
        assertThat(expected.path("product").path("category").asString()).isEqualTo("BAKERY");
        assertThat(expected.path("product").path("version").asLong()).isEqualTo(1);
        assertThat(expected.path("confirmation").path("originalCategory").asString()).isEqualTo("DAIRY");
        assertThat(expected.path("confirmation").path("confirmedCategory").asString()).isEqualTo("BAKERY");
        assertThat(expected.path("confirmation").path("originalTemperatureClass").asString()).isEqualTo("CHILLED");
        assertThat(expected.path("confirmation").path("confirmedTemperatureClass").asString()).isEqualTo("CHILLED");
        assertThat(expected.path("confirmation").path("productProfile").asString()).isEqualTo("PACKAGED_SHELF_STABLE_DRY_BAKERY");
        assertThat(expected.path("confirmation").path("productVersion").asLong()).isEqualTo(1);
        assertThat(expected.path("confirmation").path("evidenceReference").asString()).isEqualTo("package:category-label");
        assertThat(expected.path("status").asString()).isEqualTo("CURRENT_OPERATOR_CONFIRMED");
        var confirmationId=UUID.fromString(expected.path("confirmation").path("id").asString());
        for (var sql:List.of("UPDATE stockrescue.food_safety_product_classification_confirmation SET evidence_reference='rewritten' WHERE org_id=:org AND id=:id",
                "DELETE FROM stockrescue.food_safety_product_classification_confirmation WHERE org_id=:org AND id=:id"))
            assertThatThrownBy(() -> jdbc.sql(sql).param("org",f.orgA).param("id",confirmationId).update())
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var missingSource=new HashMap<String,Object>();missingSource.put("requestId",UUID.randomUUID());missingSource.put("expectedProductVersion",1);
        missingSource.put("category","DAIRY");missingSource.put("temperatureClass","CHILLED");missingSource.put("productProfile","OUTSIDE_CURRENT_PILOT");
        missingSource.put("sourceType",null);missingSource.put("evidenceReference",null);
        missingSource.put("reason","Source reference has not been recorded");
        problem(owner.write("POST",endpoint,missingSource),400,"INVALID_REQUEST");
        assertThat(tree(owner.get(path(f.orgA,product))).path("version").asLong()).isEqualTo(1);
        assertThat(tree(owner.write("POST",endpoint,confirmation))).isEqualTo(expected);
        var changed=categoryConfirmation(request,0,"UNCLASSIFIED");
        problem(owner.write("POST",endpoint,changed),409,"CATALOG_CONFLICT");
        problem(owner.write("POST",endpoint,categoryConfirmation(UUID.randomUUID(),0,"UNCLASSIFIED")),409,"CATALOG_CONFLICT");
        problem(owner.write("POST",path(f.orgB,product)+"/classification-evidence/operator-confirmations",categoryConfirmation(UUID.randomUUID(),0,"BAKERY")),404,"RESOURCE_NOT_FOUND");
        assertThat(audits("PRODUCT_CLASSIFICATION_CONFIRMED")).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND resource_id=:id
                    AND action='PRODUCT_CLASSIFICATION_CONFIRMED' AND reason='Confirmed from the package label'
                    AND changes#>>'{before,data,category}'='DAIRY'
                    AND changes#>>'{after,data,category}'='BAKERY'
                    AND changes#>>'{before,version}'='0' AND changes#>>'{after,version}'='1'
                """).param("org",f.orgA).param("id",product).query(Long.class).single()).isEqualTo(1);
        f.member(f.ownerB,f.orgA,"ORG_ADMIN",null);
        jdbc.sql("UPDATE stockrescue.membership SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE org_id=:org AND user_id=:actor")
                .param("org",f.orgA).param("actor",f.ownerA.id).update();
        problem(owner.write("POST",endpoint,categoryConfirmation(UUID.randomUUID(),1,"UNCLASSIFIED")),404,"RESOURCE_NOT_FOUND");
    }

    @Test
    void operatorConfirmationRejectsBlankOversizedAndControlCharacterSourceReferences() throws Exception {
        var owner=f.ownerA.browser();
        var product=create(owner,"SOURCE-REFERENCE-VALIDATION");
        var endpoint=path(f.orgA,product)+"/classification-evidence/operator-confirmations";
        var blank=categoryConfirmation(UUID.randomUUID(),0,"BAKERY");blank.put("evidenceReference","  ");
        var oversized=categoryConfirmation(UUID.randomUUID(),0,"BAKERY");oversized.put("evidenceReference","x".repeat(257));
        var controlCharacter=categoryConfirmation(UUID.randomUUID(),0,"BAKERY");controlCharacter.put("evidenceReference","package:front-label\nforged");

        problem(owner.write("POST",endpoint,blank),400,"INVALID_REQUEST");
        problem(owner.write("POST",endpoint,oversized),400,"INVALID_REQUEST");
        problem(owner.write("POST",endpoint,controlCharacter),422,"INVALID_REQUEST");

        assertThat(tree(owner.get(path(f.orgA,product))).path("version").asLong()).isZero();
        assertThat(tree(owner.get(endpoint)).path("status").asString()).isEqualTo("NOT_RECORDED");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.food_safety_product_classification_confirmation WHERE org_id=:org AND product_id=:product")
                .param("org",f.orgA).param("product",product).query(Long.class).single()).isZero();
    }

    @Test
    void concurrentClassificationEditsAgainstOneProductVersionLeaveOneCurrentConfirmation() throws Exception {
        var first=f.ownerA.browser();
        var second=new Browser(f.ownerA);
        var product=create(first,"CLASSIFICATION-RACE");
        var endpoint=path(f.orgA,product)+"/classification-evidence/operator-confirmations";
        var outcomes=concurrent(
                () -> first.write("POST",endpoint,categoryConfirmation(UUID.randomUUID(),0,"BAKERY")),
                () -> second.write("POST",endpoint,categoryConfirmation(UUID.randomUUID(),0,"BAKERY")));

        assertThat(outcomes.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,409);
        var conflict=outcomes.stream().filter(response -> response.statusCode()==409).findFirst().orElseThrow();
        problem(conflict,409,"CATALOG_CONFLICT");
        var current=tree(first.get(endpoint));
        assertThat(current.path("product").path("category").asString()).isEqualTo("BAKERY");
        assertThat(current.path("product").path("version").asLong()).isEqualTo(1);
        assertThat(current.path("status").asString()).isEqualTo("CURRENT_OPERATOR_CONFIRMED");
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.food_safety_product_classification_confirmation WHERE org_id=:org AND product_id=:product")
                .param("org",f.orgA).param("product",product).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND resource_id=:product AND action='PRODUCT_CLASSIFICATION_CONFIRMED'")
                .param("org",f.orgA).param("product",product).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void duplicateGtinNeedsARecentServerSnapshotAndArchivedMatchesRemainVisible() throws Exception {
        var owner=f.ownerA.browser();
        var original=body(UUID.randomUUID(),"GTIN-ORIGINAL");
        assertThat(owner.write("POST",base(f.orgA),original).statusCode()).isEqualTo(201);
        var preview=owner.get(gtinReview(f.orgA,"036000291452"));
        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        var initial=tree(preview);
        assertThat(initial.path("matches")).hasSize(1);
        var candidate=initial.path("matches").get(0);
        assertThat(candidate.path("sku").asString()).isEqualTo("GTIN-ORIGINAL");
        assertThat(candidate.path("name").asString()).isEqualTo("Fresh milk");
        assertThat(candidate.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(candidate.path("gtin").asString()).isEqualTo("036000291452");
        assertThat(initial.path("matchSetRevision").asString()).matches("[0-9a-f]{64}");
        problem(owner.write("POST",base(f.orgA),body(UUID.randomUUID(),"GTIN-BYPASS")),409,"GTIN_REVIEW_REQUIRED");
        assertThat(count("product")).isEqualTo(1);
        assertThat(count("product_command")).isEqualTo(1);
        assertThat(audits("PRODUCT_CREATED")).isEqualTo(1);

        var confirmed=body(UUID.randomUUID(),"GTIN-CONFIRMED");
        confirmed.put("gtinReviewAcknowledged",true);
        confirmed.put("gtinReviewRevision",initial.path("matchSetRevision").asString());
        var created=owner.write("POST",base(f.orgA),confirmed);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(tree(owner.write("POST",base(f.orgA),confirmed))).isEqualTo(tree(created));
        var changed=new HashMap<>(confirmed);changed.put("name","Different operator intent");
        problem(owner.write("POST",base(f.orgA),changed),409,"CATALOG_CONFLICT");
        var changedAcknowledgement=new HashMap<>(confirmed);changedAcknowledgement.put("gtinReviewAcknowledged",false);
        problem(owner.write("POST",base(f.orgA),changedAcknowledgement),409,"CATALOG_CONFLICT");
        var stale=body(UUID.randomUUID(),"GTIN-STALE");
        stale.put("gtinReviewAcknowledged",true);
        stale.put("gtinReviewRevision",initial.path("matchSetRevision").asString());
        problem(owner.write("POST",base(f.orgA),stale),409,"GTIN_REVIEW_REQUIRED");
        assertThat(count("product")).isEqualTo(2);
        assertThat(count("product_command")).isEqualTo(2);
        assertThat(audits("PRODUCT_CREATED")).isEqualTo(2);

        var beforeArchive=tree(owner.get(gtinReview(f.orgA,"036000291452"))).path("matchSetRevision").asString();
        var productId=UUID.fromString(tree(created).path("id").asString());
        assertThat(owner.write("POST",path(f.orgA,productId)+"/archive",archive(UUID.randomUUID(),0)).statusCode()).isEqualTo(200);
        var archived=tree(owner.get(gtinReview(f.orgA,"036000291452")));
        assertThat(archived.path("matchSetRevision").asString()).isNotEqualTo(beforeArchive);
        assertThat(archived.path("matches")).hasSize(2);
        assertThat(java.util.stream.StreamSupport.stream(archived.path("matches").spliterator(),false)
                .map(match -> match.path("status").asString()).toList()).containsExactlyInAnyOrder("ACTIVE","ARCHIVED");
        assertThat(archived.path("matches").toString()).contains("GTIN-ORIGINAL","GTIN-CONFIRMED");
        problem(owner.write("POST",base(f.orgA),body(UUID.randomUUID(),"GTIN-ARCHIVED-BYPASS")),409,"GTIN_REVIEW_REQUIRED");
    }

    @Test
    void emptyMatchesNeedNoConsentAndBoundedPagesShareTheCompleteSetRevision() throws Exception {
        var owner=f.ownerA.browser();
        var empty=tree(owner.get(gtinReviewPage(f.orgA,"036000291452",0,1)));
        assertThat(empty.path("matches")).isEmpty();
        assertThat(empty.path("matchSetRevision").isNull()).isTrue();
        var invalidAcknowledgement=body(UUID.randomUUID(),"GTIN-EMPTY-ACK");
        invalidAcknowledgement.put("gtinReviewAcknowledged",true);
        invalidAcknowledgement.put("gtinReviewRevision","0".repeat(64));
        problem(owner.write("POST",base(f.orgA),invalidAcknowledgement),409,"GTIN_REVIEW_REQUIRED");
        assertThat(count("product")).isZero();
        assertThat(count("product_command")).isZero();

        assertThat(owner.write("POST",base(f.orgA),body(UUID.randomUUID(),"GTIN-PAGE-1")).statusCode()).isEqualTo(201);
        assertThat(createWithGtin(owner,f.orgA,"GTIN-PAGE-2")).isNotNull();
        assertThat(createWithGtin(owner,f.orgA,"GTIN-PAGE-3")).isNotNull();
        var first=tree(owner.get(gtinReviewPage(f.orgA,"036000291452",0,1)));
        var second=tree(owner.get(gtinReviewPage(f.orgA,"036000291452",1,1)));
        var third=tree(owner.get(gtinReviewPage(f.orgA,"036000291452",2,1)));
        assertThat(first.path("matches")).hasSize(1);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(second.path("matches")).hasSize(1);
        assertThat(second.path("hasMore").asBoolean()).isTrue();
        assertThat(third.path("matches")).hasSize(1);
        assertThat(third.path("hasMore").asBoolean()).isFalse();
        assertThat(first.path("matchSetRevision").asString()).matches("[0-9a-f]{64}");
        assertThat(second.path("matchSetRevision").asString()).isEqualTo(first.path("matchSetRevision").asString());
        assertThat(third.path("matchSetRevision").asString()).isEqualTo(first.path("matchSetRevision").asString());
        assertThat(first.path("matches").get(0).path("gtin").asString()).isEqualTo("036000291452");
    }

    @Test
    void concurrentNewProductsWithOneGtinRequireTheSecondOperatorToReview() throws Exception {
        var first=f.ownerA.browser();var second=new Browser(f.ownerA);
        var responses=concurrent(() -> first.write("POST",base(f.orgA),body(UUID.randomUUID(),"GTIN-RACE-A")),
                () -> second.write("POST",base(f.orgA),body(UUID.randomUUID(),"GTIN-RACE-B")));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,409);
        var rejected=responses.stream().filter(response -> response.statusCode()==409).findFirst().orElseThrow();
        assertThat(tree(rejected).path("code").asString()).isEqualTo("GTIN_REVIEW_REQUIRED");
        assertThat(count("product")).isEqualTo(1);
        assertThat(count("product_command")).isEqualTo(1);
        assertThat(audits("PRODUCT_CREATED")).isEqualTo(1);
    }

    @Test
    void gtinPreviewPreservesLeadingZeroesAndRejectsMalformedValues() throws Exception {
        var owner=f.ownerA.browser();
        var created=owner.write("POST",base(f.orgA),body(UUID.randomUUID(),"GTIN-LEADING-ZERO"));
        assertThat(created.statusCode()).isEqualTo(201);
        var preview=tree(owner.get(gtinReview(f.orgA,"036000291452")));
        assertThat(preview.path("gtin").asString()).isEqualTo("036000291452");
        assertThat(preview.path("matches").get(0).path("productId").asString()).isEqualTo(tree(created).path("id").asString());
        problem(owner.get(gtinReview(f.orgA,"036000291453")),422,"INVALID_REQUEST");
        assertThat(count("product")).isEqualTo(1);
    }

    @Test
    void categoryConfirmationAuditFailureRollsBackProductEvidenceAndRetryReceipt() throws Exception {
        var owner=f.ownerA.browser();
        var product=create(owner,"CATEGORY-ROLLBACK");
        var confirmation=categoryConfirmation(UUID.randomUUID(),0,"BAKERY");
        var endpoint=path(f.orgA,product)+"/classification-evidence/operator-confirmations";
        doThrow(new IllegalStateException("audit unavailable")).when(audit).securityChange(any(),eq(f.orgA),eq("product"),eq(product),eq("PRODUCT_CLASSIFICATION_CONFIRMED"),anyMap(),anyString());
        problem(owner.write("POST",endpoint,confirmation),500,"INTERNAL_ERROR");
        assertThat(tree(owner.get(path(f.orgA,product))).path("category").asString()).isEqualTo("DAIRY");
        assertThat(tree(owner.get(path(f.orgA,product))).path("version").asLong()).isZero();
        assertThat(count("product_command")).isEqualTo(1);
        assertThat(count("food_safety_product_classification_confirmation")).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.orgA),eq("product"),eq(product),eq("PRODUCT_CLASSIFICATION_CONFIRMED"),anyMap(),anyString());
        var retried=owner.write("POST",endpoint,confirmation);
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(201);
        assertThat(tree(retried).path("product").path("category").asString()).isEqualTo("BAKERY");
        assertThat(count("product_command")).isEqualTo(2);
        assertThat(count("food_safety_product_classification_confirmation")).isEqualTo(1);
        assertThat(audits("PRODUCT_CLASSIFICATION_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void searchAndPaginationUseLiteralFiltersAndStableOrganizationScopedOrdering() throws Exception {
        var owner=f.ownerA.browser();
        createWithGtin(owner,f.orgA,"C-THIRD"); createWithGtin(owner,f.orgA,"A-FIRST");
        var middle=body(UUID.randomUUID(),"B-SECOND"); middle.put("name","Offer 100%_fresh");
        var id=UUID.fromString(tree(createWithGtin(owner,f.orgA,middle)).path("id").asString());
        create(f.ownerB.browser(),f.orgB,"FOREIGN");
        var one=tree(owner.get(base(f.orgA)+"?limit=1"));
        assertThat(one.path("items").get(0).path("sku").asString()).isEqualTo("A-FIRST");
        assertThat(one.path("hasMore").asBoolean()).isTrue();
        assertThat(tree(owner.get(base(f.orgA)+"?offset=1&limit=1")).path("items").get(0).path("id").asString()).isEqualTo(id.toString());
        assertThat(tree(owner.get(base(f.orgA)+"?offset=2&limit=1")).path("hasMore").asBoolean()).isFalse();
        for (var q:List.of("b-second","100%25","_fresh")) {
            var filtered=tree(owner.get(base(f.orgA)+"?q="+q)).path("items");
            assertThat(filtered).hasSize(1);
            assertThat(filtered.get(0).path("id").asString()).isEqualTo(id.toString());
        }
        assertThat(tree(owner.get(base(f.orgA)+"?q=036000291452")).path("items")).hasSize(3);
        assertThat(tree(owner.get(base(f.orgA)+"?q=FOREIGN")).path("items")).isEmpty();
        owner.write("POST",path(f.orgA,id)+"/archive",archive(UUID.randomUUID(),0));
        assertThat(tree(owner.get(base(f.orgA))).path("items")).hasSize(2);
        assertThat(tree(owner.get(base(f.orgA)+"?state=ALL")).path("items")).hasSize(3);
        assertThat(tree(owner.get(base(f.orgA)+"?state=ARCHIVED")).path("items")).hasSize(1);
    }

    @Test
    void revokedMembershipInactiveOrganizationAndDisabledAccountCannotReadOrWrite() throws Exception {
        var owner=f.ownerA.browser();
        var member=f.member.browser();
        var product=create(owner,"ACCESS");
        jdbc.sql("UPDATE stockrescue.membership SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE org_id=:org AND user_id=:actor")
                .param("org",f.orgA).param("actor",f.member.id).update();
        problem(member.get(path(f.orgA,product)),404,"RESOURCE_NOT_FOUND");
        var manager=f.manager.browser();
        jdbc.sql("UPDATE stockrescue.user_account SET status='DISABLED' WHERE id=:id").param("id",f.manager.id).update();
        assertThat(manager.get(path(f.orgA,product)).statusCode()).isEqualTo(401);
        jdbc.sql("UPDATE stockrescue.organization SET status='ARCHIVED',version=version+1 WHERE id=:org").param("org",f.orgA).update();
        problem(owner.get(base(f.orgA)),404,"RESOURCE_NOT_FOUND");
        problem(owner.write("POST",base(f.orgA),body(UUID.randomUUID(),"AFTER-ARCHIVE")),404,"RESOURCE_NOT_FOUND");
        assertThat(count("product_command")).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackProductAndRetryReservationAndArchiveVersion() throws Exception {
        var owner=f.ownerA.browser();
        var body=body(UUID.randomUUID(),"ROLLBACK");
        doThrow(new IllegalStateException("audit unavailable")).when(audit).securityChange(any(),eq(f.orgA),eq("product"),any(),anyString(),anyMap(),anyString());
        problem(owner.write("POST",base(f.orgA),body),500,"INTERNAL_ERROR");
        assertThat(count("product")).isZero(); assertThat(count("product_command")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.product_gtin_review_scope WHERE org_id=:org")
                .param("org",f.orgA).query(Long.class).single()).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.orgA),eq("product"),any(),anyString(),anyMap(),anyString());
        var created=owner.write("POST",base(f.orgA),body);
        assertThat(created.statusCode()).isEqualTo(201);
        var id=UUID.fromString(tree(created).path("id").asString());
        var archive=archive(UUID.randomUUID(),0);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).securityChange(any(),eq(f.orgA),eq("product"),any(),eq("PRODUCT_ARCHIVED"),anyMap(),anyString());
        problem(owner.write("POST",path(f.orgA,id)+"/archive",archive),500,"INTERNAL_ERROR");
        assertThat(tree(owner.get(path(f.orgA,id))).path("version").asLong()).isZero();
        assertThat(count("product_command")).isEqualTo(1);
        doCallRealMethod().when(audit).securityChange(any(),eq(f.orgA),eq("product"),any(),eq("PRODUCT_ARCHIVED"),anyMap(),anyString());
        assertThat(owner.write("POST",path(f.orgA,id)+"/archive",archive).statusCode()).isEqualTo(200);
        assertThat(audits("PRODUCT_ARCHIVED")).isEqualTo(1);
    }

    @Test
    void aDatabaseRejectionAndAnOrphanReservationCannotLeavePartialOutcomes() throws Exception {
        var owner=f.ownerA.browser();
        var body=body(UUID.randomUUID(),"SQL-REJECTION");
        jdbc.sql("ALTER TABLE stockrescue.product ADD CONSTRAINT catalog_failure_probe CHECK (org_id<>'"+f.orgA+"'::uuid OR sku<>'SQL-REJECTION')").update();
        try {
            problem(owner.write("POST",base(f.orgA),body),409,"CATALOG_CONFLICT");
            assertThat(count("product")).isZero(); assertThat(count("product_command")).isZero();
        } finally { jdbc.sql("ALTER TABLE stockrescue.product DROP CONSTRAINT catalog_failure_probe").update(); }
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                products.reserve(f.ownerA.id,f.orgA,ProductRepository.Operation.CREATE,UUID.randomUUID(),UUID.randomUUID(),0,new byte[32])))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("product_command")).isZero();
        assertThat(owner.write("POST",base(f.orgA),body).statusCode()).isEqualTo(201);
    }

    @Test void committedMembershipRevocationRejectsAWaitingCatalogWrite() throws Exception { orderedRevocation(false); }
    @Test void anAuthorizedCatalogWriteCommitsBeforeAWaitingRevocation() throws Exception { orderedRevocation(true); }

    private void orderedRevocation(boolean writeFirst) throws Exception {
        f.member(f.ownerB,f.orgA,"ORG_ADMIN",null);
        var author=f.ownerA.browser(); var administrator=f.ownerB.browser();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var action=writeFirst?"PRODUCT_CREATED":"MEMBERSHIP_REVOKED";
        doAnswer(call -> { entered.countDown(); if (!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Authorization barrier timed out"); return call.callRealMethod(); })
                .when(audit).securityChange(any(),eq(f.orgA),anyString(),any(),eq(action),anyMap(),anyString());
        var request=body(UUID.randomUUID(),"ORDERED");
        try (var workers=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HttpResponse<String>> write=() -> author.write("POST",base(f.orgA),request);
            java.util.concurrent.Callable<HttpResponse<String>> revoke=() -> administrator.write("DELETE","/api/v1/organizations/"+f.orgA+"/members/"+f.membership(f.ownerA),Map.of("version",0,"reason","Colleague assignment ended"));
            var first=workers.submit(writeFirst?write:revoke);
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var second=workers.submit(writeFirst?revoke:write);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%stockrescue.organization%'").query(Long.class).single()).isPositive());
            release.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(writeFirst?201:200);
            assertThat(second.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(writeFirst?200:404);
        } finally { release.countDown(); }
        problem(author.write("POST",base(f.orgA),request),404,"RESOURCE_NOT_FOUND");
        assertThat(count("product")).isEqualTo(writeFirst?1:0);
        assertThat(count("product_command")).isEqualTo(writeFirst?1:0);
        assertThat(audits("PRODUCT_CREATED")).isEqualTo(writeFirst?1:0);
    }

    private long count(String table) { return jdbc.sql("SELECT count(*) FROM stockrescue."+table+" WHERE org_id=:org").param("org",f.orgA).query(Long.class).single(); }
    private long audits(String action) { return jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action=:action").param("org",f.orgA).param("action",action).query(Long.class).single(); }
    private Map<String,Object> body(UUID request,String sku) { return new HashMap<>(Map.of("requestId",request,"sku",sku,"name","Fresh milk","gtin","036000291452","baseUnit","EA","category","DAIRY","temperatureClass","CHILLED")); }
    private Map<String,Object> bodyWithoutGtin(UUID request,String sku) { var body=body(request,sku);body.remove("gtin");return body; }
    private Map<String,Object> archive(UUID request,long version) { return new HashMap<>(Map.of("requestId",request,"expectedVersion",version,"reason","Discontinued product reviewed")); }
    private Map<String,Object> categoryConfirmation(UUID request,long version,String category) { return new HashMap<>(Map.of("requestId",request,"expectedProductVersion",version,"category",category,
            "temperatureClass","CHILLED","productProfile",category.equals("BAKERY")?"PACKAGED_SHELF_STABLE_DRY_BAKERY":category.equals("UNCLASSIFIED")?"NOT_ASSESSED":"OUTSIDE_CURRENT_PILOT",
            "sourceType","ORIGINAL_PACKAGE","evidenceReference","package:category-label","reason","Confirmed from the package label")); }
    private UUID create(Browser browser,String sku) throws Exception { return create(browser,f.orgA,sku); }
    private UUID create(Browser browser,UUID org,String sku) throws Exception { var result=browser.write("POST",base(org),bodyWithoutGtin(UUID.randomUUID(),sku)); assertThat(result.statusCode()).as(result.body()).isEqualTo(201); return UUID.fromString(tree(result).path("id").asString()); }
    private UUID createWithGtin(Browser browser,UUID org,String sku) throws Exception {
        var result=createWithGtin(browser,org,body(UUID.randomUUID(),sku));assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        return UUID.fromString(tree(result).path("id").asString());
    }
    private HttpResponse<String> createWithGtin(Browser browser,UUID org,Map<String,Object> command) throws Exception {
        var result=browser.write("POST",base(org),command);
        if (result.statusCode()==409 && "GTIN_REVIEW_REQUIRED".equals(tree(result).path("code").asString())) {
            var review=tree(browser.get(gtinReview(org,(String)command.get("gtin"))));
            command.put("requestId",UUID.randomUUID());
            command.put("gtinReviewAcknowledged",true);
            command.put("gtinReviewRevision",review.path("matchSetRevision").asString());
            result=browser.write("POST",base(org),command);
        }
        return result;
    }
    private String gtinReview(UUID org,String gtin) { return base(org)+"/gtin-review?gtin="+gtin+"&offset=0&limit=25"; }
    private String gtinReviewPage(UUID org,String gtin,int offset,int limit) { return base(org)+"/gtin-review?gtin="+gtin+"&offset="+offset+"&limit="+limit; }
    private String base(UUID org) { return "/api/v1/organizations/"+org+"/products"; }
    private String path(UUID org,UUID product) { return base(org)+"/"+product; }
    private JsonNode tree(HttpResponse<String> result) { return json.readTree(result.body()); }
    private void problem(HttpResponse<String> result,int status,String code) { assertThat(result.statusCode()).as(result.body()).isEqualTo(status); assertThat(tree(result).path("code").asString()).isEqualTo(code); assertThat(tree(result).path("correlationId").asString()).isEqualTo(result.headers().firstValue("X-Correlation-ID").orElseThrow()); }
    private List<HttpResponse<String>> concurrent(java.util.concurrent.Callable<HttpResponse<String>> one,java.util.concurrent.Callable<HttpResponse<String>> two) throws Exception {
        var start=new CountDownLatch(1);
        try (var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(() -> { start.await(); return one.call(); }); var b=workers.submit(() -> { start.await(); return two.call(); });
            start.countDown(); return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }
    private class Fixture {
        final User ownerA=user(),ownerB=user(),member=user(),manager=user(),operator=user(),analyst=user();
        final UUID orgA=org(ownerA),orgB=org(ownerB),store=UUID.randomUUID();
        Fixture() {
            jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:id,:org,'NORTH','North','UTC')").param("id",store).param("org",orgA).update();
            member(ownerA,orgA,"ORG_ADMIN",null); member(ownerB,orgB,"ORG_ADMIN",null); member(member,orgA,"MEMBER",null);
            member(manager,orgA,"MEMBER","STORE_MANAGER"); member(operator,orgA,"MEMBER","INVENTORY_OPERATOR"); member(analyst,orgA,"MEMBER","READ_ONLY_ANALYST");
        }
        User user() { var user=new User(UUID.randomUUID()); jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)").param("id",user.id).param("email",user.email).update(); jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)").param("id",user.id).param("hash",hash).update(); return user; }
        UUID org(User owner) { var org=UUID.randomUUID(); jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:org,'Catalog cooperative',:actor)").param("org",org).param("actor",owner.id).update(); return org; }
        void member(User user,UUID org,String role,String grant) {
            var id=UUID.randomUUID(); jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:user,:role)").param("id",id).param("org",org).param("user",user.id).param("role",role).update();
            if (grant!=null) jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,:role)").param("id",UUID.randomUUID()).param("org",org).param("member",id).param("store",store).param("role",grant).update();
        }
        UUID membership(User user) { return jdbc.sql("SELECT id FROM stockrescue.membership WHERE org_id=:org AND user_id=:user").param("org",orgA).param("user",user.id).query(UUID.class).single(); }
    }
    private class User {
        final UUID id; final String email; Browser browser;
        User(UUID id) { this.id=id; this.email=id+"@example.test"; }
        Browser browser() throws Exception { if (browser==null) browser=new Browser(this); return browser; }
    }
    private class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Browser() { }
        Browser(User user) throws Exception { assertThat(write("POST","/api/v1/auth/sign-in",Map.of("email",user.email,"password",PASSWORD)).statusCode()).isEqualTo(200); }
        HttpResponse<String> get(String path) throws Exception { return send("GET",path,null,null); }
        HttpResponse<String> write(String method,String path,Object body) throws Exception { var token=tree(get("/api/v1/auth/csrf")).path("token").asString(); return send(method,path,body,token); }
        HttpResponse<String> send(String method,String path,Object body,String token) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");
            if (token!=null) request.header("X-CSRF-TOKEN",token);
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
