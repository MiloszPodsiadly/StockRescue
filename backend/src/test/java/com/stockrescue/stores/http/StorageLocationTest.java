package com.stockrescue.stores.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorageLocationTest extends InfrastructureTestBase {
    private static final String PASSWORD="storage-location-test-passphrase";
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private AuditWriter audit;
    private String hash;
    private Fixture f;
    @BeforeAll void credentials() { hash=encoder.encode(PASSWORD); }
    @BeforeEach void fixture() { f=new Fixture(); }

    @Test
    void locationIdentityIsStoreScopedWithCanonicalCodeAndBoundedLiteralSearch() throws Exception {
        var owner=f.owner.browser();var request=create(" a-01 ",f.zone);var first=owner.write(base(f.store),request);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        var saved=tree(first);assertThat(saved.path("code").asString()).isEqualTo("A-01");
        assertThat(saved.path("temperatureClass").asString()).isEqualTo("AMBIENT");assertThat(saved.path("usable").asBoolean()).isTrue();
        assertThat(saved.toString()).doesNotContain("quantity","capacity","onHand","availableQuantity");
        assertThat(tree(owner.write(base(f.store),request))).isEqualTo(saved);
        problem(owner.write(base(f.store),create("A-01",f.zone)),409,"CONFIGURATION_CONFLICT");
        assertThat(owner.write(base(f.otherStore),create("A-01",f.otherZone)).statusCode()).isEqualTo(201);
        owner.write(base(f.store),create("C-01",f.zone));owner.write(base(f.store),create("B-01",f.zone));
        var page=tree(owner.get(base(f.store)+"?limit=1"));assertThat(page.path("items").get(0)).isEqualTo(saved);assertThat(page.path("hasMore").asBoolean()).isTrue();
        assertThat(tree(owner.get(base(f.store)+"?limit=1&offset=1")).path("items").get(0).path("code").asString()).isEqualTo("B-01");
        assertThat(tree(owner.get(base(f.store)+"?q=%25")).path("items")).isEmpty();
        problem(owner.get(base(f.store)+"?limit=101"),400,"INVALID_REQUEST");
        assertThat(count("storage_location")).isEqualTo(4);assertThat(count("storage_location_command")).isEqualTo(4);
    }

    @Test
    void renameAndArchivePreserveStableCodeAndZoneWithVersionAwareReplay() throws Exception {
        var owner=f.owner.browser();var id=register("A-01");var rename=rename(0,"Picking shelf");
        var changed=owner.write(path(id)+"/rename",rename);assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(tree(changed).path("version").asLong()).isEqualTo(1);assertThat(tree(owner.write(path(id)+"/rename",rename))).isEqualTo(tree(changed));
        problem(owner.write(path(id)+"/rename",rename(0,"Stale name")),409,"CONFIGURATION_CONFLICT");
        var forged=new HashMap<>(rename);forged.put("zoneId",f.otherZone);problem(owner.write(path(id)+"/rename",forged),400,"INVALID_REQUEST");
        var archive=archive(1);var archived=owner.write(path(id)+"/archive",archive);assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
        assertThat(tree(archived).path("id").asString()).isEqualTo(id.toString());assertThat(tree(archived).path("zoneId").asString()).isEqualTo(f.zone.toString());
        assertThat(tree(archived).path("usable").asBoolean()).isFalse();assertThat(tree(archived).path("archivedAt").isNull()).isFalse();
        assertThat(tree(owner.write(path(id)+"/archive",archive))).isEqualTo(tree(archived));
        assertThat(tree(owner.write(path(id)+"/rename",rename))).isEqualTo(tree(archived));
        problem(owner.write(path(id)+"/rename",rename(2,"Revived")),409,"CONFIGURATION_INACTIVE");
        problem(owner.write(base(f.store),create("a-01",f.zone)),409,"CONFIGURATION_CONFLICT");
        assertThat(tree(owner.get(base(f.store))).path("items")).isEmpty();
        assertThat(tree(owner.get(base(f.store)+"?includeArchived=true")).path("items")).hasSize(1);
        assertThat(count("storage_location_command")).isEqualTo(3);assertThat(audits("STORAGE_LOCATION_RENAMED")).isEqualTo(1);
    }

    @Test
    void grantsAndCompositeZoneIdentityApplyAtHttpAndSqlBoundaries() throws Exception {
        var manager=f.manager.browser();var request=create("MANAGED",f.zone);var result=manager.write(base(f.store),request);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);var id=UUID.fromString(tree(result).path("id").asString());
        assertThat(f.operator.browser().get(path(id)).statusCode()).isEqualTo(200);
        problem(f.operator.browser().write(base(f.store),create("DENIED",f.zone)),403,"ACCESS_DENIED");
        problem(manager.write(base(f.otherStore),create("DENIED",f.otherZone)),404,"RESOURCE_NOT_FOUND");
        problem(f.owner.browser().write(base(f.store),create("FOREIGN-STORE",f.otherZone)),404,"RESOURCE_NOT_FOUND");
        problem(f.owner.browser().write(base(f.store),create("FOREIGN-ORG",f.foreignZone)),404,"RESOURCE_NOT_FOUND");
        problem(f.foreign.browser().get(path(id)),404,"RESOURCE_NOT_FOUND");
        for (var zone:List.of(f.otherZone,f.foreignZone)) assertThatThrownBy(() -> insert(jdbc,UUID.randomUUID(),"FOREIGN",zone))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.sql("UPDATE stockrescue.store_grant SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE org_id=:org AND membership_id=:member")
                .param("org",f.org).param("member",f.membership(f.manager)).update();
        problem(manager.write(base(f.store),request),404,"RESOURCE_NOT_FOUND");problem(manager.get(path(id)),404,"RESOURCE_NOT_FOUND");
    }

    @Test
    void activeLocationsBlockArchivalAndAreIncludedInTheReviewedImpact() throws Exception {
        var owner=f.owner.browser();var oldPreview=preview(owner);var id=register("A-01");var impact=preview(owner);
        assertThat(impact.path("activeLocationCount").asInt()).isEqualTo(1);
        assertThat(impact.path("impactFingerprint")).isNotEqualTo(oldPreview.path("impactFingerprint"));
        problem(owner.write(zoneArchive(),zoneArchive(oldPreview)),409,"ZONE_IMPACT_CHANGED");
        problem(owner.write(zoneArchive(),zoneArchive(impact)),409,"ZONE_HAS_ACTIVE_LOCATIONS");
        assertThatThrownBy(() -> jdbc.sql("UPDATE stockrescue.storage_zone SET active=FALSE,version=version+1,updated_at=clock_timestamp() WHERE id=:id").param("id",f.zone).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(owner.write(path(id)+"/archive",archive(0)).statusCode()).isEqualTo(200);
        assertThat(owner.write(zoneArchive(),zoneArchive(preview(owner))).statusCode()).isEqualTo(200);
        assertThat(tree(owner.get(path(id))).path("usable").asBoolean()).isFalse();
        problem(owner.write(base(f.store),create("NEW",f.zone)),409,"CONFIGURATION_INACTIVE");
    }

    @Test
    void storeArchivalRetainsHistoryButMakesLocationConfigurationUnusable() throws Exception {
        var owner=f.owner.browser();var request=create("A-01",f.zone);var result=owner.write(base(f.store),request);var id=UUID.fromString(tree(result).path("id").asString());
        assertThat(owner.write("/api/v1/organizations/"+f.org+"/stores/"+f.store+"/archive",Map.of("version",0,"reason","Store closure")).statusCode()).isEqualTo(200);
        var detail=tree(owner.get(path(id)));assertThat(detail.path("active").asBoolean()).isTrue();assertThat(detail.path("usable").asBoolean()).isFalse();
        assertThat(tree(owner.get(base(f.store))).path("canManage").asBoolean()).isFalse();
        assertThat(tree(owner.write(base(f.store),request))).isEqualTo(detail);
        problem(owner.write(base(f.store),create("NEW",f.zone)),409,"CONFIGURATION_INACTIVE");
        problem(owner.write(path(id)+"/rename",rename(0,"Changed after closure")),409,"CONFIGURATION_INACTIVE");
        problem(f.manager.browser().get(path(id)),404,"RESOURCE_NOT_FOUND");
        assertThatThrownBy(() -> insert(jdbc,UUID.randomUUID(),"SQL-NEW",f.zone)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("storage_location_command")).isEqualTo(1);
    }

    @Test
    void eligibilityResolvesTheScopedLocationZoneAndDeniesArchivedLocations() throws Exception {
        var owner=f.owner.browser();var id=register("A-01");
        var product=owner.write("/api/v1/organizations/"+f.org+"/products",Map.of("requestId",UUID.randomUUID(),"sku","BREAD","name","Packaged bread","baseUnit","EA","category","BAKERY","temperatureClass","AMBIENT"));
        assertThat(product.statusCode()).isEqualTo(201);
        var lot=owner.write("/api/v1/organizations/"+f.org+"/lots",Map.of("requestId",UUID.randomUUID(),"productId",tree(product).path("id").asString(),"lotCode","LOT",
                "identityState","CANDIDATE","manufacturerVerified",false,"manufacturerProvenance",Map.of("source","Label","reference","photo"),
                "dateMarking",Map.of("labeledValue","2030-04-30","type","USE_BY","precision","DAY","provenance",Map.of("source","Label","reference","photo"))));
        assertThat(lot.statusCode()).isEqualTo(201);
        var eligibility="/api/v1/organizations/"+f.org+"/lots/"+tree(lot).path("id").asString()+"/eligibility?storeId="+f.store+"&operation=SALE&locationId="+id;
        var before=tree(owner.get(eligibility));assertThat(before.path("locationId").asString()).isEqualTo(id.toString());assertThat(before.path("zoneId").asString()).isEqualTo(f.zone.toString());
        assertThat(before.path("decision").asString()).isEqualTo("REQUIRES_REVIEW");
        assertThat(before.path("evidence").path("storage").asString()).isEqualTo("DECLARED_COMPATIBLE");
        problem(owner.get(eligibility+"&zoneId="+f.zone),400,"INVALID_REQUEST");
        assertThat(owner.write(path(id)+"/archive",archive(0)).statusCode()).isEqualTo(200);
        var after=tree(owner.get(eligibility));assertThat(after.path("decision").asString()).isEqualTo("DENIED");
        assertThat(after.path("reasonCodes").toString()).contains("LOCATION_ARCHIVED");assertThat(after.path("locationVersion").asLong()).isEqualTo(1);
        assertThat(after.path("evidence").path("storage").asString()).isEqualTo("INACTIVE");
    }

    @Test
    void duplicateRequestsHaveOneOutcomeAndAlteredRetriesConflict() throws Exception {
        var one=f.owner.browser();var two=new Browser(f.owner);var body=create("DUPLICATE",f.zone);
        var results=concurrent(() -> one.write(base(f.store),body),() -> two.write(base(f.store),body));
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201,200);
        assertThat(tree(results.get(0))).isEqualTo(tree(results.get(1)));
        body.put("name","Changed retry");problem(one.write(base(f.store),body),409,"CONFIGURATION_CONFLICT");
        var id=UUID.fromString(tree(results.get(0)).path("id").asString());
        var versions=concurrent(() -> one.write(path(id)+"/rename",rename(0,"First edit")),() -> two.write(path(id)+"/rename",rename(0,"Second edit")));
        assertThat(versions.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200,409);
        assertThat(count("storage_location")).isEqualTo(1);assertThat(count("storage_location_command")).isEqualTo(2);
    }

    @Test
    void auditFailureRollsBackIdentityVersionsAndDurableResults() throws Exception {
        var owner=f.owner.browser();var body=create("ROLLBACK",f.zone);
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).securityChange(any(),eq(f.org),eq("storage-location"),any(),anyString(),anyMap(),anyString());
        problem(owner.write(base(f.store),body),500,"INTERNAL_ERROR");assertThat(count("storage_location")).isZero();assertThat(count("storage_location_command")).isZero();
        doCallRealMethod().when(audit).securityChange(any(),eq(f.org),eq("storage-location"),any(),anyString(),anyMap(),anyString());
        var created=owner.write(base(f.store),body);var id=UUID.fromString(tree(created).path("id").asString());
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).securityChange(any(),eq(f.org),eq("storage-location"),eq(id),anyString(),anyMap(),anyString());
        problem(owner.write(path(id)+"/rename",rename(0,"Rolled back name")),500,"INTERNAL_ERROR");
        problem(owner.write(path(id)+"/archive",archive(0)),500,"INTERNAL_ERROR");
        assertThat(tree(owner.get(path(id)))).isEqualTo(tree(created));assertThat(count("storage_location_command")).isEqualTo(1);
    }

    @Test
    void sqlKeepsLocationIdentityImmutableAndRejectsRevivalDeletionAndTruncation() throws Exception {
        var id=register("IDENTITY");
        for (var assignment:List.of("code='OTHER'","zone_id='"+f.otherZone+"'::uuid","store_id='"+f.otherStore+"'::uuid"))
            assertThatThrownBy(() -> jdbc.sql("UPDATE stockrescue.storage_location SET "+assignment+",version=version+1 WHERE id=:id").param("id",id).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(f.owner.browser().write(path(id)+"/archive",archive(0)).statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> jdbc.sql("UPDATE stockrescue.storage_location SET active=TRUE,archived_at=NULL,version=version+1 WHERE id=:id").param("id",id).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM stockrescue.storage_location WHERE id=:id").param("id",id).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE stockrescue.storage_location,stockrescue.storage_location_command").update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void aLocationCreationCommitsBeforeWaitingZoneArchival() throws Exception { ordered(true); }
    @Test void zoneArchivalRejectsTheWaitingLocationCreation() throws Exception { ordered(false); }
    private void ordered(boolean createFirst) throws Exception {
        var creator=f.manager.browser();var archiver=f.owner.browser();var preview=preview(archiver);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var action=createFirst?"STORAGE_LOCATION_CREATED":"ZONE_ARCHIVED";
        doAnswer(call -> { entered.countDown();if (!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Command barrier timed out");return call.callRealMethod(); })
                .when(audit).securityChange(any(),eq(f.org),anyString(),any(),eq(action),anyMap(),anyString());
        try (var workers=Executors.newFixedThreadPool(2)) {
            Callable<HttpResponse<String>> create=() -> creator.write(base(f.store),create("ORDERED",f.zone));
            Callable<HttpResponse<String>> archive=() -> archiver.write(zoneArchive(),zoneArchive(preview));
            var first=workers.submit(createFirst?create:archive);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var second=workers.submit(createFirst?archive:create);waitForLock("store");release.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(createFirst?201:200);
            var rejected=second.get(15,TimeUnit.SECONDS);problem(rejected,409,createFirst?"ZONE_IMPACT_CHANGED":"CONFIGURATION_INACTIVE");
        } finally { release.countDown(); }
        assertThat(count("storage_location")).isEqualTo(createFirst?1:0);assertThat(count("storage_location_command")).isEqualTo(createFirst?1:0);
    }

    @Test void repeatableReadZoneArchivalCannotMissAConcurrentNewLocation() throws Exception { sqlRace(true); }
    @Test void repeatableReadLocationCreationCannotReferenceAConcurrentlyArchivedZone() throws Exception { sqlRace(false); }
    private void sqlRace(boolean createFirst) throws Exception {
        try (var workers=Executors.newSingleThreadExecutor();var creator=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"stockrescue_app","test-app-password");
             var archiver=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"stockrescue_app","test-app-password")) {
            for (var connection:List.of(creator,archiver)) {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
                try (var snapshot=connection.createStatement()) { snapshot.execute("SET LOCAL lock_timeout='12s'");snapshot.executeQuery("SELECT count(*) FROM stockrescue.storage_zone").close(); }
            }
            Callable<String> create=() -> sqlOutcome(creator,true);
            Callable<String> archive=() -> sqlOutcome(archiver,false);
            assertThat((createFirst?create:archive).call()).isEqualTo("UPDATED");
            var second=workers.submit(createFirst?archive:create);waitForLock(createFirst?"storage_zone":"storage_location");
            (createFirst?creator:archiver).commit();
            assertThat(second.get(15,TimeUnit.SECONDS)).isIn("23001","23503","40001");
            (createFirst?archiver:creator).rollback();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.storage_location l JOIN stockrescue.storage_zone z ON z.org_id=l.org_id AND z.store_id=l.store_id AND z.id=l.zone_id WHERE l.org_id=:org AND l.active AND NOT z.active")
                .param("org",f.org).query(Long.class).single()).isZero();
    }
    private String sqlOutcome(Connection connection,boolean create) throws Exception {
        var sql=create?"INSERT INTO stockrescue.storage_location(id,org_id,store_id,zone_id,code,name) VALUES (?,?,?,?,?,'SQL location')"
                :"UPDATE stockrescue.storage_zone SET active=FALSE,version=version+1,updated_at=clock_timestamp() WHERE id=?";
        try (var command=connection.prepareStatement(sql)) {
            if (create) { command.setObject(1,UUID.randomUUID());command.setObject(2,f.org);command.setObject(3,f.store);command.setObject(4,f.zone);command.setString(5,"SQL-RACE"); }
            else command.setObject(1,f.zone);
            command.executeUpdate();return "UPDATED";
        } catch (SQLException rejected) { return rejected.getSQLState(); }
    }

    @Test
    void inputCannotClaimQuantityCapacityOrACompetingTemperatureClass() throws Exception {
        var owner=f.owner.browser();
        for (var field:List.of("quantity","capacity","temperatureClass")) { var body=create("FORGED",f.zone);body.put(field,10);problem(owner.write(base(f.store),body),400,"INVALID_REQUEST"); }
        for (var code:List.of(" ","bad code","café","bad\tcode","A".repeat(33))) problem(owner.write(base(f.store),create(code,f.zone)),400,"INVALID_REQUEST");
        problem(new Browser().get(base(f.store)),401,"AUTH_REQUIRED");problem(owner.send("POST",base(f.store),create("CSRF",f.zone),null),403,"CSRF_INVALID");
        assertThat(count("storage_location")).isZero();
    }

    private void insert(JdbcClient client,UUID id,String code,UUID zone) { client.sql("INSERT INTO stockrescue.storage_location(id,org_id,store_id,zone_id,code,name) VALUES (:id,:org,:store,:zone,:code,'SQL location')").param("id",id).param("org",f.org).param("store",f.store).param("zone",zone).param("code",code).update(); }
    private Map<String,Object> create(String code,UUID zone) { return new HashMap<>(Map.of("requestId",UUID.randomUUID(),"code",code,"name","Picking shelf","zoneId",zone)); }
    private Map<String,Object> rename(long version,String name) { return new HashMap<>(Map.of("requestId",UUID.randomUUID(),"expectedVersion",version,"name",name,"reason","Reviewed location name")); }
    private Map<String,Object> archive(long version) { return Map.of("requestId",UUID.randomUUID(),"expectedVersion",version,"reason","Location configuration retired"); }
    private UUID register(String code) throws Exception { var result=f.owner.browser().write(base(f.store),create(code,f.zone));assertThat(result.statusCode()).as(result.body()).isEqualTo(201);return UUID.fromString(tree(result).path("id").asString()); }
    private String base(UUID store) { return "/api/v1/organizations/"+f.org+"/stores/"+store+"/storage-locations"; }
    private String path(UUID id) { return base(f.store)+"/"+id; }
    private String zoneArchive() { return "/api/v1/organizations/"+f.org+"/stores/"+f.store+"/storage-zones/"+f.zone+"/archive"; }
    private JsonNode preview(Browser browser) throws Exception { var result=browser.get(zoneArchive().replace("/archive","/archive-impact"));assertThat(result.statusCode()).as(result.body()).isEqualTo(200);return tree(result); }
    private Map<String,Object> zoneArchive(JsonNode preview) { return Map.of("version",0,"reason","Reviewed zone configuration","impactFingerprint",preview.path("impactFingerprint").asString()); }
    private JsonNode tree(HttpResponse<String> result) { return json.readTree(result.body()); }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM stockrescue."+table+" WHERE org_id=:org").param("org",f.org).query(Long.class).single(); }
    private long audits(String action) { return jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action=:action").param("org",f.org).param("action",action).query(Long.class).single(); }
    private void problem(HttpResponse<String> result,int status,String code) { assertThat(result.statusCode()).as(result.body()).isEqualTo(status);assertThat(tree(result).path("code").asString()).isEqualTo(code); }
    private void waitForLock(String table) { await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE :query").param("query","%stockrescue."+table+"%").query(Long.class).single()).isPositive()); }
    private List<HttpResponse<String>> concurrent(Callable<HttpResponse<String>> one,Callable<HttpResponse<String>> two) throws Exception { var start=new CountDownLatch(1);try (var workers=Executors.newFixedThreadPool(2)) { var a=workers.submit(() -> { start.await();return one.call(); });var b=workers.submit(() -> { start.await();return two.call(); });start.countDown();return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)); } }
    private class Fixture {
        final User owner=user(),manager=user(),operator=user(),foreign=user();
        final UUID org=org(owner),otherOrg=org(foreign),store=store(org,"NORTH"),otherStore=store(org,"SOUTH"),foreignStore=store(otherOrg,"FOREIGN"),zone=zone(org,store),otherZone=zone(org,otherStore),foreignZone=zone(otherOrg,foreignStore);
        Fixture() { member(owner,org,"ORG_ADMIN",null);member(manager,org,"MEMBER","STORE_MANAGER");member(operator,org,"MEMBER","INVENTORY_OPERATOR");member(foreign,otherOrg,"ORG_ADMIN",null); }
        User user() { var u=new User(UUID.randomUUID());jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)").param("id",u.id).param("email",u.email).update();jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)").param("id",u.id).param("hash",hash).update();return u; }
        UUID org(User owner) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Location cooperative',:owner)").param("id",id).param("owner",owner.id).update();return id; }
        UUID store(UUID org,String code) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:id,:org,:code,:code,'Europe/Warsaw')").param("id",id).param("org",org).param("code",code).update();return id; }
        UUID zone(UUID org,UUID store) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (:id,:org,:store,'Ambient','AMBIENT')").param("id",id).param("org",org).param("store",store).update();return id; }
        void member(User actor,UUID org,String role,String grant) { var id=UUID.randomUUID();jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:actor,:role)").param("id",id).param("org",org).param("actor",actor.id).param("role",role).update();if (grant!=null) jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,:role)").param("id",UUID.randomUUID()).param("org",org).param("member",id).param("store",store).param("role",grant).update(); }
        UUID membership(User actor) { return jdbc.sql("SELECT id FROM stockrescue.membership WHERE org_id=:org AND user_id=:actor").param("org",org).param("actor",actor.id).query(UUID.class).single(); }
    }
    private class User { final UUID id;final String email;Browser browser;User(UUID id) { this.id=id;this.email=id+"@example.test"; }Browser browser() throws Exception { if (browser==null) browser=new Browser(this);return browser; } }
    private class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Browser() { }
        Browser(User actor) throws Exception { assertThat(write("/api/v1/auth/sign-in",Map.of("email",actor.email,"password",PASSWORD)).statusCode()).isEqualTo(200); }
        String token() throws Exception { return tree(get("/api/v1/auth/csrf")).path("token").asString(); }
        HttpResponse<String> get(String path) throws Exception { return send("GET",path,null,null); }
        HttpResponse<String> write(String path,Object body) throws Exception { return send("POST",path,body,token()); }
        HttpResponse<String> send(String method,String path,Object body,String token) throws Exception { var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");if (token!=null) request.header("X-CSRF-TOKEN",token);return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
