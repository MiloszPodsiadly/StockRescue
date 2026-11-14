package com.stockrescue.inventory.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmation.ProductProfile;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmationSubmission;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmation.SourceType;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmationService;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import com.stockrescue.stores.application.StoreService;
import com.stockrescue.support.InfrastructureTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryAccessHttpTest extends InfrastructureTestBase {
    private static final String PASSWORD="inventory-access-test-passphrase";
    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder encoder;
    @Autowired private FoodSafetyProductClassificationConfirmationService classification;
    @Autowired private StoreService stores;
    private String passwordHash;
    private Fixture f;

    @BeforeAll void credentials() { passwordHash=encoder.encode(PASSWORD); }
    @BeforeEach void fixture() { MDC.put("correlationId",UUID.randomUUID().toString()); f=new Fixture(); }
    @AfterEach void clear() { MDC.remove("correlationId"); }

    @Test
    void directInventoryRequestsHonorCurrentRoleOwnershipTenantAndStoreState() throws Exception {
        var owner=f.owner.browser();
        var manager=f.manager.browser();
        var operator=f.operator.browser();
        var peer=f.peer.browser();
        var analyst=f.analyst.browser();
        var foreignOwner=f.foreignOwner.browser();
        var root=inventory(f.org,f.store);
        var receipt=owner.write("POST",root+"/receipts",Map.ofEntries(
                Map.entry("requestId",UUID.randomUUID()),Map.entry("expectedVersion",0),
                Map.entry("locationId",f.location),Map.entry("productId",f.product),Map.entry("lotId",f.lot),
                Map.entry("quantity",6),Map.entry("supplierKey","supplier:registered-http"),
                Map.entry("documentType","DELIVERY"),Map.entry("documentReference","delivery-http-1"),
                Map.entry("documentLineReference","line-1"),
                Map.entry("physicallyReceivedAt",Instant.now().minusSeconds(3600).toString()),
                Map.entry("reason","Received counted physical stock"),Map.entry("evidenceReference","signed-delivery-note")));
        assertThat(receipt.statusCode()).as(receipt.body()).isEqualTo(200);
        UUID position=UUID.fromString(json.readTree(receipt.body()).path("positionId").asString());

        UUID operatorHold=hold(operator,root,position,1,1);
        problem(peer.write("POST",releasePath(root,position,operatorHold),command(UUID.randomUUID(),2,1)),403);
        UUID managerRelease=UUID.randomUUID();
        var releasedByManager=manager.write("POST",releasePath(root,position,operatorHold),command(managerRelease,2,1));
        assertThat(releasedByManager.statusCode()).as(releasedByManager.body()).isEqualTo(200);
        var managerReplay=manager.write("POST",releasePath(root,position,operatorHold),command(managerRelease,2,1));
        assertThat(managerReplay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(managerReplay.body()).path("movementId").asString())
                .isEqualTo(json.readTree(releasedByManager.body()).path("movementId").asString());

        UUID managerHold=hold(manager,root,position,3,1);
        problem(operator.write("POST",releasePath(root,position,managerHold),command(UUID.randomUUID(),4,1)),403);
        var managerOwnRelease=manager.write("POST",releasePath(root,position,managerHold),command(UUID.randomUUID(),4,1));
        assertThat(managerOwnRelease.statusCode()).as(managerOwnRelease.body()).isEqualTo(200);

        UUID administratorHold=hold(owner,root,position,5,1);
        UUID managerReleaseOfAdministratorHoldRequest=UUID.randomUUID();
        var managerReleaseOfAdministratorHold=manager.write("POST",releasePath(root,position,administratorHold),
                command(managerReleaseOfAdministratorHoldRequest,6,1));
        assertThat(managerReleaseOfAdministratorHold.statusCode()).as(managerReleaseOfAdministratorHold.body()).isEqualTo(200);

        jdbc.sql("UPDATE stockrescue.store_grant SET role='READ_ONLY_ANALYST',version=version+1 WHERE membership_id=:membership")
                .param("membership",f.managerMembership).update();
        problem(manager.write("POST",releasePath(root,position,administratorHold),
                command(managerReleaseOfAdministratorHoldRequest,6,1)),403);

        problem(analyst.write("POST",holdPath(root,position),command(UUID.randomUUID(),7,1)),403);
        problem(foreignOwner.write("POST",holdPath(root,position),command(UUID.randomUUID(),7,1)),404);
        problem(operator.write("POST",holdPath(inventory(f.org,f.otherStore),position),command(UUID.randomUUID(),7,1)),404);

        jdbc.sql("UPDATE stockrescue.membership SET state='REVOKED',revoked_at=clock_timestamp(),version=version+1 WHERE id=:id")
                .param("id",f.peerMembership).update();
        problem(peer.write("POST",holdPath(root,position),command(UUID.randomUUID(),7,1)),404);

        stores.archive(f.owner.id,f.org,f.archivedStore,0,"Store closed after inventory review");
        problem(owner.write("POST",holdPath(inventory(f.org,f.archivedStore),position),command(UUID.randomUUID(),7,1)),404);

        var snapshot=owner.get(root+"/positions/"+position);
        assertThat(snapshot.statusCode()).isEqualTo(200);
        var stock=json.readTree(snapshot.body());
        assertThat(stock.path("onHand").asLong()).isEqualTo(6);
        assertThat(stock.path("available").asLong()).isEqualTo(6);
        assertThat(stock.path("otherBlocked").asLong()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.stock_movement_line WHERE position_id=:position")
                .param("position",position).query(Long.class).single()).isEqualTo(7);
    }

    private UUID hold(Browser browser,String root,UUID position,long version,long quantity) throws Exception {
        var response=browser.write("POST",holdPath(root,position),command(UUID.randomUUID(),version,quantity));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return UUID.fromString(json.readTree(response.body()).path("allocationId").asString());
    }

    private static String inventory(UUID organization,UUID store) {
        return "/api/v1/organizations/"+organization+"/stores/"+store+"/inventory";
    }
    private static String holdPath(String root,UUID position) { return root+"/positions/"+position+"/holds"; }
    private static String releasePath(String root,UUID position,UUID allocation) {
        return root+"/positions/"+position+"/holds/"+allocation+"/release";
    }
    private static Map<String,Object> command(UUID request,long expectedVersion,long quantity) {
        return Map.of("requestId",request,"expectedVersion",expectedVersion,"quantity",quantity,
                "reason","Reviewed the physical stock count","evidenceReference","inventory-count:checked");
    }
    private static void problem(HttpResponse<String> response,int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
    }

    private class Fixture {
        final User owner=user(),manager=user(),operator=user(),peer=user(),analyst=user(),foreignOwner=user();
        final UUID org=organization(owner),foreignOrg=organization(foreignOwner);
        final UUID store=UUID.randomUUID(),otherStore=UUID.randomUUID(),archivedStore=UUID.randomUUID();
        final UUID managerMembership,peerMembership;
        final UUID zone=UUID.randomUUID(),location=UUID.randomUUID(),product=UUID.randomUUID(),lot=UUID.randomUUID();

        Fixture() {
            jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:store,:org,'NORTH','North','UTC'),(:other,:org,'SOUTH','South','UTC'),(:archived,:org,'CLOSED','Closed','UTC')")
                    .param("store",store).param("other",otherStore).param("archived",archivedStore).param("org",org).update();
            membership(owner,org,"ORG_ADMIN",null);
            membership(foreignOwner,foreignOrg,"ORG_ADMIN",null);
            managerMembership=membership(manager,org,"MEMBER","STORE_MANAGER");
            membership(operator,org,"MEMBER","INVENTORY_OPERATOR");
            peerMembership=membership(peer,org,"MEMBER","INVENTORY_OPERATOR");
            membership(analyst,org,"MEMBER","READ_ONLY_ANALYST");
            jdbc.sql("INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (:id,:org,:store,'Dry','AMBIENT')")
                    .param("id",zone).param("org",org).param("store",store).update();
            jdbc.sql("INSERT INTO stockrescue.storage_location(id,org_id,store_id,zone_id,code,name) VALUES (:id,:org,:store,:zone,'A-1','Dry shelf')")
                    .param("id",location).param("org",org).param("store",store).param("zone",zone).update();
            jdbc.sql("INSERT INTO stockrescue.product(id,org_id,sku,name,base_unit,temperature_class,category) VALUES (:id,:org,'BREAD','Packaged bread','EA','AMBIENT','BAKERY')")
                    .param("id",product).param("org",org).update();
            classification.confirm(owner.id,org,product,new FoodSafetyProductClassificationConfirmationSubmission(
                    UUID.randomUUID(),0,ProductCategory.BAKERY,TemperatureClass.AMBIENT,
                    ProductProfile.PACKAGED_SHELF_STABLE_DRY_BAKERY,SourceType.ORIGINAL_PACKAGE,
                    "package:label","Reviewed original package"));
            jdbc.sql("""
                    INSERT INTO stockrescue.manufacturer_lot(id,org_id,product_id,lot_code,identity_state,manufacturer_identity_key,manufacturer_source,
                        manufacturer_reference,confirmed_by_membership_id,confirmed_at,labeled_value,date_marking_type,date_precision,marking_source,marking_reference,
                        recall_state,damage_state,status_source,status_reference)
                    VALUES (:id,:org,:product,'BREAD-LOT','CONFIRMED','maker:registered-http','Manufacturer register','register-record',
                        :member,clock_timestamp(),'2027-04-30','USE_BY','DAY','Original package','label-photo',
                        'NOT_REPORTED','NOT_REPORTED','Manufacturer notice','notice-record')
                    """).param("id",lot).param("org",org).param("product",product).param("member",membershipId(owner,org)).update();
        }

        UUID organization(User actor) {
            UUID id=UUID.randomUUID();
            jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Inventory security test',:actor)")
                    .param("id",id).param("actor",actor.id).update();
            return id;
        }
        UUID membership(User actor,String role,String storeRole) { return membership(actor,org,role,storeRole); }
        UUID membership(User actor,UUID organization,String role,String storeRole) {
            UUID id=UUID.randomUUID();
            jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:user,:role)")
                    .param("id",id).param("org",organization).param("user",actor.id).param("role",role).update();
            if(storeRole!=null) jdbc.sql("INSERT INTO stockrescue.store_grant(id,org_id,membership_id,store_id,role) VALUES (:id,:org,:member,:store,:role)")
                    .param("id",UUID.randomUUID()).param("org",organization).param("member",id).param("store",store).param("role",storeRole).update();
            return id;
        }
        UUID membershipId(User actor,UUID organization) {
            return jdbc.sql("SELECT id FROM stockrescue.membership WHERE org_id=:org AND user_id=:user")
                    .param("org",organization).param("user",actor.id).query(UUID.class).single();
        }
        User user() {
            User user=new User(UUID.randomUUID());
            jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)")
                    .param("id",user.id).param("email",user.email).update();
            jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)")
                    .param("id",user.id).param("hash",passwordHash).update();
            return user;
        }
    }

    private class User {
        final UUID id;
        final String email;
        User(UUID id) { this.id=id; this.email=id+"@example.test"; }
        Browser browser() throws Exception { return new Browser(this); }
    }

    private class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Browser(User user) throws Exception {
            var response=write("POST","/api/v1/auth/sign-in",Map.of("email",user.email,"password",PASSWORD));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
        HttpResponse<String> get(String path) throws Exception { return send("GET",path,null,null); }
        HttpResponse<String> write(String method,String path,Object body) throws Exception {
            String token=json.readTree(get("/api/v1/auth/csrf").body()).path("token").asString();
            return send(method,path,body,token);
        }
        HttpResponse<String> send(String method,String path,Object body,String token) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(25))
                    .header("Content-Type","application/json");
            if(token!=null) request.header("X-CSRF-TOKEN",token);
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
