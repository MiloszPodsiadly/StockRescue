package com.stockrescue.catalog.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.StockRescueApplication;
import com.stockrescue.catalog.application.FoodSafetyApprovalProperties;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationAttestation;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.catalog.persistence.ProductRepository;
import com.github.dockerjava.api.model.ExposedPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CatalogRestartTest {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    private int port;

    @Test
    void catalogIdentitySourceEvidenceAndCommandOutcomesSurviveDatabaseAndApplicationRestart() throws Exception {
        try (var database=new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"))
                .withDatabaseName("stockrescue_test").withUsername("test_admin").withPassword("test-admin-password")
                .withInitScript("db/test-role.sql")) {
            database.start();
            var actor=UUID.randomUUID(); var org=UUID.randomUUID(); var membership=UUID.randomUUID();
            var email=actor+"@example.test"; var password="product-restart-test-passphrase";
            var request=UUID.randomUUID();
            var body=Map.of("requestId",request,"sku","PERSISTED","name","Persisted product","gtin","036000291452","baseUnit","EA","category","DAIRY","temperatureClass","AMBIENT");
            UUID product;
            UUID lot;
            var store=UUID.randomUUID(); var zone=UUID.randomUUID();
            Map<String,Object> lotRequest;
            Map<String,Object> correctionRequest;
            JsonNode correctionResult;
            Map<String,Object> policyRequest;
            JsonNode policyResult;
            Map<String,Object> classificationRequest;
            JsonNode classificationResult;
            KeyPair classificationSigner;
            Map<String,Object> safetyEvidenceRequest;
            JsonNode safetyEvidenceResult;
            UUID location;
            var locationRequest=Map.of("requestId",UUID.randomUUID(),"code","A-01","name","Picking shelf","zoneId",zone);
            var sourceRequest=Map.of("requestId",UUID.randomUUID(),"supplierReference","shared-reference",
                    "provenance",Map.of("source","Supplier document","reference","document-17"));
            try (var application=application(database.getJdbcUrl())) {
                port=((WebServerApplicationContext)application).getWebServer().getPort();
                var jdbc=application.getBean(JdbcClient.class);
                jdbc.sql("INSERT INTO stockrescue.user_account(id,email_display) VALUES (:id,:email)").param("id",actor).param("email",email).update();
                jdbc.sql("INSERT INTO stockrescue.credential(user_id,password_hash) VALUES (:id,:hash)").param("id",actor).param("hash",application.getBean(PasswordEncoder.class).encode(password)).update();
                jdbc.sql("INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (:id,'Restart cooperative',:actor)").param("id",org).param("actor",actor).update();
                jdbc.sql("INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (:id,:org,:actor,'ORG_ADMIN')").param("id",membership).param("org",org).param("actor",actor).update();
                jdbc.sql("INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (:id,:org,'NORTH','North','Europe/Warsaw')").param("id",store).param("org",org).update();
                jdbc.sql("INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (:id,:org,:store,'Shelf','AMBIENT')")
                        .param("id",zone).param("org",org).param("store",store).update();
                assertThat(write("/api/v1/auth/sign-in",Map.of("email",email,"password",password)).statusCode()).isEqualTo(200);
                var located=write(locations(org,store),locationRequest);
                assertThat(located.statusCode()).as(located.body()).isEqualTo(201);location=UUID.fromString(tree(located).path("id").asString());
                var created=write(base(org),body);
                assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
                product=UUID.fromString(tree(created).path("id").asString());
                classificationSigner=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                trustClassificationIssuer(application,classificationSigner);
                var productRecord=application.getBean(ProductRepository.class).detail(org,product,false).orElseThrow();
                var issuedAt=Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(5);
                var validUntil=issuedAt.plus(Duration.ofDays(1));
                var evidenceReference="external-case:product-classification-restart";
                var canonical=FoodSafetyProductClassificationAttestation.canonicalPayload(org,productRecord,ProductCategory.DAIRY,
                        "restart-issuer","restart-key",issuedAt,issuedAt,validUntil,evidenceReference);
                var classificationSignature=Signature.getInstance("Ed25519");
                classificationSignature.initSign(classificationSigner.getPrivate());classificationSignature.update(canonical);
                classificationRequest=Map.of("requestId",UUID.randomUUID(),"expectedProductVersion",productRecord.version(),
                        "issuerId","restart-issuer","keyId","restart-key","issuedAt",issuedAt,"validFrom",issuedAt,
                        "validUntil",validUntil,"evidenceReference",evidenceReference,
                        "signatureBase64",Base64.getEncoder().encodeToString(classificationSignature.sign()));
                var classification=write(classification(org,product),classificationRequest);
                assertThat(classification.statusCode()).as(classification.body()).isEqualTo(201);
                classificationResult=tree(classification);
                assertThat(classificationResult.path("outcome").asString()).isEqualTo("VERIFIED");
                lotRequest=Map.of("requestId",UUID.randomUUID(),"productId",product,"lotCode","PERSISTED-LOT",
                        "identityState","CONFIRMED","manufacturerIdentityKey","registry:maker-a","manufacturerVerified",true,
                        "manufacturerProvenance",Map.of("source","Reviewed manufacturer registry","reference","registry-17"),
                        "dateMarking",Map.of("labeledValue"," 2027-04 ","type","BEST_BEFORE_END","precision","MONTH",
                                "provenance",Map.of("source","Manufacturer label","reference","photo-17")));
                var registered=write(lots(org),lotRequest);
                assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
                lot=UUID.fromString(tree(registered).path("id").asString());
                var first=write(sources(org,lot),sourceRequest);
                assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
                var anotherRequest=new java.util.HashMap<String,Object>(sourceRequest);
                anotherRequest.put("requestId",UUID.randomUUID());
                var second=write(sources(org,lot),anotherRequest);
                assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
                assertThat(tree(first).path("id")).isNotEqualTo(tree(second).path("id"));
                correctionRequest=Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Reviewed replacement label evidence",
                        "evidenceReviewed",true,"identityState","CONFIRMED","manufacturerIdentityKey","registry:maker-a","manufacturerVerified",true,
                        "manufacturerProvenance",Map.of("source","Reviewed manufacturer registry","reference","registry-reviewed"),
                        "dateMarking",Map.of("labeledValue"," 2027-05 ","type","BEST_BEFORE_END","precision","MONTH",
                                "provenance",Map.of("source","Manufacturer label","reference","photo-reviewed")));
                var corrected=write(lots(org)+"/"+lot+"/corrections",correctionRequest);
                assertThat(corrected.statusCode()).as(corrected.body()).isEqualTo(201);
                correctionResult=tree(corrected);
                policyRequest=Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Reviewed conservative policy source",
                        "partialDateRule","END_OF_LABELED_PERIOD","reviewProvenance",Map.of("source","Review document","reference","policy-17"));
                var policyReview=write(policies(org),policyRequest);
                assertThat(policyReview.statusCode()).as(policyReview.body()).isEqualTo(201); policyResult=tree(policyReview);
                safetyEvidenceRequest=Map.of("requestId",UUID.randomUUID(),"expectedVersion",1,"reason","Reviewed manufacturer recall notice",
                        "recallState","RECALLED","damageState","NOT_REPORTED","provenance",Map.of("source","Manufacturer notice","reference","notice-17"));
                var blocked=write(lots(org)+"/"+lot+"/safety-status-evidence",safetyEvidenceRequest);
                assertThat(blocked.statusCode()).as(blocked.body()).isEqualTo(201); safetyEvidenceResult=tree(blocked);
            }
            var docker=DockerClientFactory.instance().client();
            docker.restartContainerCmd(database.getContainerId()).withTimeout(10).exec();
            // Docker can reassign a published ephemeral port; Testcontainers caches the original inspection.
            var binding=docker.inspectContainerCmd(database.getContainerId()).exec().getNetworkSettings().getPorts()
                    .getBindings().get(ExposedPort.tcp(5432))[0];
            var restartedUrl="jdbc:postgresql://"+database.getHost()+":"+binding.getHostPortSpec()+"/stockrescue_test";
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                try (var connection=DriverManager.getConnection(restartedUrl,"stockrescue_app","test-app-password")) {
                    assertThat(connection.isValid(1)).isTrue();
                } catch (java.sql.SQLException unavailable) { throw new AssertionError("PostgreSQL reconnect failed: "+unavailable.getSQLState(),unavailable); }
            });
            try (var application=application(restartedUrl)) {
                trustClassificationIssuer(application,classificationSigner);
                port=((WebServerApplicationContext)application).getWebServer().getPort();
                assertThat(get("/api/v1/auth/me").statusCode()).isEqualTo(200);
                var detail=get(base(org)+"/"+product);
                assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
                assertThat(tree(detail).path("gtin").asString()).isEqualTo("036000291452");
                assertThat(tree(get(base(org)+"?q=PERSISTED")).path("items")).hasSize(1);
                var retry=write(base(org),body);
                assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
                assertThat(tree(retry)).isEqualTo(tree(detail));
                var classificationAfterRestart=get(classification(org,product));
                assertThat(classificationAfterRestart.statusCode()).as(classificationAfterRestart.body()).isEqualTo(200);
                assertThat(tree(classificationAfterRestart).path("outcome").asString()).isEqualTo("VERIFIED");
                assertThat(tree(classificationAfterRestart).path("attestationId")).isEqualTo(classificationResult.path("attestationId"));
                assertThat(classificationAfterRestart.body()).doesNotContain("signature","publicKey","privateKey");
                var registered=get(lots(org)+"/"+lot);
                assertThat(registered.statusCode()).as(registered.body()).isEqualTo(200);
                assertThat(tree(registered).path("dateMarking").path("labeledValue").asString()).isEqualTo(" 2027-05 ");
                assertThat(tree(registered).path("version").asLong()).isEqualTo(2);
                assertThat(tree(registered)).isEqualTo(safetyEvidenceResult.path("lot"));
                var correctionHistory=tree(get(lots(org)+"/"+lot+"/corrections"));
                assertThat(correctionHistory.path("items")).hasSize(2);
                assertThat(correctionHistory.path("items").get(0)).isEqualTo(safetyEvidenceResult.path("correction"));
                assertThat(correctionHistory.path("items").get(1)).isEqualTo(correctionResult.path("correction"));
                assertThat(correctionHistory.path("items").get(1).path("before").path("dateMarking").path("labeledValue").asString()).isEqualTo(" 2027-04 ");
                var correctionReplay=write(lots(org)+"/"+lot+"/corrections",correctionRequest);
                assertThat(correctionReplay.statusCode()).as(correctionReplay.body()).isEqualTo(200);
                assertThat(tree(correctionReplay).path("correction")).isEqualTo(correctionResult.path("correction"));
                assertThat(tree(correctionReplay).path("lot")).isEqualTo(safetyEvidenceResult.path("lot"));
                var safetyReplay=write(lots(org)+"/"+lot+"/safety-status-evidence",safetyEvidenceRequest);
                assertThat(safetyReplay.statusCode()).as(safetyReplay.body()).isEqualTo(200);
                assertThat(tree(safetyReplay)).isEqualTo(safetyEvidenceResult);
                var policyReplay=write(policies(org),policyRequest);
                assertThat(policyReplay.statusCode()).as(policyReplay.body()).isEqualTo(200);
                assertThat(tree(policyReplay)).isEqualTo(policyResult);
                assertThat(tree(get(policies(org))).path("current")).isEqualTo(policyResult);
                var locationReplay=write(locations(org,store),locationRequest);
                assertThat(locationReplay.statusCode()).as(locationReplay.body()).isEqualTo(200);
                assertThat(tree(locationReplay).path("id").asString()).isEqualTo(location.toString());
                assertThat(tree(get(locations(org,store)+"/"+location))).isEqualTo(tree(locationReplay));
                var evaluated=tree(get(lots(org)+"/"+lot+"/eligibility?operation=SALE&storeId="+store+"&locationId="+location));
                assertThat(evaluated.path("decision").asString()).isEqualTo("DENIED");
                assertThat(evaluated.path("reasonCodes").get(0).asString()).isEqualTo("LOT_RECALLED");
                assertThat(evaluated.path("classificationVerification").path("outcome").asString()).isEqualTo("VERIFIED");
                assertThat(evaluated.path("policy").path("id")).isEqualTo(policyResult.path("id"));
                assertThat(evaluated.path("locationId").asString()).isEqualTo(location.toString());
                assertThat(evaluated.path("zoneId").asString()).isEqualTo(zone.toString());
                assertThat(tree(registered).path("dateMarking").path("precision").asString()).isEqualTo("MONTH");
                assertThat(tree(registered).path("dateMarking").path("normalizedCutoff").isNull()).isTrue();
                var lotReplay=write(lots(org),lotRequest);
                assertThat(lotReplay.statusCode()).as(lotReplay.body()).isEqualTo(200);
                assertThat(tree(lotReplay)).isEqualTo(tree(registered));
                assertThat(write(sources(org,lot),sourceRequest).statusCode()).isEqualTo(200);
                assertThat(tree(get(sources(org,lot))).path("items")).hasSize(2);
                var archive=Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Reviewed discontinuation after restart");
                var archived=write(base(org)+"/"+product+"/archive",archive);
                assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
                assertThat(tree(archived).path("active").asBoolean()).isFalse();
                assertThat(tree(write(base(org)+"/"+product+"/archive",archive))).isEqualTo(tree(archived));
                var jdbc=application.getBean(JdbcClient.class);
                assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.product_command WHERE org_id=:org").param("org",org).query(Long.class).single()).isEqualTo(2);
                assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND resource_id=:id").param("org",org).param("id",product).query(Long.class).single()).isEqualTo(2);
                assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.lot_registration WHERE org_id=:org").param("org",org).query(Long.class).single()).isEqualTo(1);
                assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.consignment_registration WHERE org_id=:org").param("org",org).query(Long.class).single()).isEqualTo(2);
                assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.lot_correction WHERE org_id=:org").param("org",org).query(Long.class).single()).isEqualTo(2);
                assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.food_safety_policy_version WHERE org_id=:org").param("org",org).query(Long.class).single()).isEqualTo(1);
                assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action='LOT_SAFETY_EVIDENCE_RECORDED'").param("org",org).query(Long.class).single()).isEqualTo(1);
                assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action='LOT_METADATA_CORRECTED'").param("org",org).query(Long.class).single()).isEqualTo(1);
                assertThat(jdbc.sql("SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=:org AND action='SOURCE_EVIDENCE_REGISTERED'").param("org",org).query(Long.class).single()).isEqualTo(2);
            }
        }
    }

    private void trustClassificationIssuer(ConfigurableApplicationContext application,KeyPair signer) {
        var issuer=new FoodSafetyApprovalProperties.TrustedIssuer();issuer.setIssuerId("restart-issuer");
        issuer.setPublicKeyX509(Base64.getEncoder().encodeToString(signer.getPublic().getEncoded()));issuer.setEnabled(true);
        var properties=application.getBean(FoodSafetyApprovalProperties.class);
        properties.setEnabled(true);properties.setTrustedIssuers(Map.of("restart-key",issuer));
    }
    private ConfigurableApplicationContext application(String databaseUrl) {
        return new SpringApplicationBuilder(StockRescueApplication.class).profiles("local").run(
                "--server.port=0","--spring.datasource.url="+databaseUrl,
                "--spring.datasource.username=stockrescue_app","--spring.datasource.password=test-app-password",
                "--spring.kafka.bootstrap-servers=127.0.0.1:1",
                "--stockrescue.intelligence.base-url=http://127.0.0.1:1");
    }
    private String base(UUID org) { return "/api/v1/organizations/"+org+"/products"; }
    private String lots(UUID org) { return "/api/v1/organizations/"+org+"/lots"; }
    private String sources(UUID org,UUID lot) { return lots(org)+"/"+lot+"/source-evidence"; }
    private String policies(UUID org) { return "/api/v1/organizations/"+org+"/food-safety-profiles/DAIRY/AMBIENT/versions"; }
    private String classification(UUID org,UUID product) { return base(org)+"/"+product+"/classification-evidence"; }
    private String locations(UUID org,UUID store) { return "/api/v1/organizations/"+org+"/stores/"+store+"/storage-locations"; }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private HttpResponse<String> get(String path) throws Exception { return send("GET",path,null,null); }
    private HttpResponse<String> write(String path,Object body) throws Exception { return send("POST",path,body,tree(get("/api/v1/auth/csrf")).path("token").asString()); }
    private HttpResponse<String> send(String method,String path,Object body,String csrf) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if (csrf!=null) request.header("X-CSRF-TOKEN",csrf);
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
}
