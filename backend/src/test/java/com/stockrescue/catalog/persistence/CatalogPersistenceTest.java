package com.stockrescue.catalog.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stockrescue.catalog.domain.DateMarking;
import com.stockrescue.catalog.domain.DateMarkingType;
import com.stockrescue.catalog.domain.DatePrecision;
import com.stockrescue.catalog.domain.EvidenceProvenance;
import com.stockrescue.catalog.domain.Gtin;
import com.stockrescue.support.InfrastructureTestBase;
import com.stockrescue.support.MigrationAssertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogPersistenceTest extends InfrastructureTestBase {
    private Connection connection;
    private Fixture f;

    @BeforeAll
    static void migrate() {
        var flyway = migrations(POSTGRES.getJdbcUrl());
        flyway.migrate();
        flyway.validate();
        MigrationAssertions.assertCurrent(flyway);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @BeforeEach
    void startTransaction() throws Exception {
        connection = open(POSTGRES.getJdbcUrl());
        connection.setAutoCommit(false);
        f = fixture(connection);
    }

    @AfterEach
    void rollback() throws Exception {
        if (connection != null) {
            try { connection.rollback(); }
            finally { connection.close(); }
        }
    }

    @Test
    void productsKeepCanonicalSkuAndLosslessGtinWithinTheirOrganization() throws Exception {
        assertThat(text("SELECT sku_normalized FROM stockrescue.product WHERE id=?", f.productA)).isEqualTo("MILK-1");
        reject("23505", productSql(), UUID.randomUUID(), f.orgA, " MILK-1 ", "036000291452");
        reject("23514", productSql(), UUID.randomUUID(), f.orgA, "bad code", null);
        reject("23514", productSql(), UUID.randomUUID(), f.orgA, "café", null);
        reject("23514", productSql(), UUID.randomUUID(), f.orgA, "A\t", null);
        reject("23514", productSql(), UUID.randomUUID(), f.orgA, "BAD-CHECK", "036000291453");
        reject("23514", "UPDATE stockrescue.product SET base_unit='KG',version=version+1 WHERE id=?", f.productA);
        reject("23514", "UPDATE stockrescue.product SET temperature_class='COOL',version=version+1 WHERE id=?", f.productA);
        reject("23514", "UPDATE stockrescue.product SET name=' ',version=version+1 WHERE id=?", f.productA);
        assertThat(text("SELECT gtin FROM stockrescue.product WHERE id=?", f.productA)).isEqualTo("036000291452");
        assertThat(count("SELECT count(*) FROM stockrescue.product WHERE sku_normalized='MILK-1' AND org_id IN (?,?)", f.orgA, f.orgB)).isEqualTo(2);
        for (var value : List.of("96385074", "036000291452", "4006381333931", "04006381333931")) {
            var id = UUID.randomUUID();
            sql(productSql(), id, f.orgA, id.toString(), value);
            assertThat(new Gtin(text("SELECT gtin FROM stockrescue.product WHERE id=?", id))).isEqualTo(new Gtin(value));
        }
        for (var value : List.of("1234567", "123456789", "400638133393X", " 036000291452")) {
            reject("23514", productSql(), UUID.randomUUID(), f.orgA, UUID.randomUUID().toString(), value);
        }
    }

    @Test
    void confirmedLotsHaveUniqueIdentityWhileUnverifiedCandidatesStayIndependent() throws Exception {
        var firstCandidate = lot(f.orgA, f.productA, "SHARED");
        var secondCandidate = lot(f.orgA, f.productA, "SHARED");
        var repeatedClaim = lot(f.orgA, f.productA, "SHARED");
        sql("UPDATE stockrescue.manufacturer_lot SET manufacturer_identity_key='CLAIM-A',manufacturer_source='supplier claim',version=version+1 WHERE id=?", firstCandidate);
        sql("UPDATE stockrescue.manufacturer_lot SET manufacturer_identity_key='CLAIM-B',manufacturer_source='another claim',version=version+1 WHERE id=?", secondCandidate);
        sql("UPDATE stockrescue.manufacturer_lot SET manufacturer_identity_key='CLAIM-A',manufacturer_source='supplier claim',version=version+1 WHERE id=?", repeatedClaim);
        assertThat(count("SELECT count(*) FROM stockrescue.manufacturer_lot WHERE org_id=? AND lot_code='SHARED' AND identity_state='CANDIDATE'", f.orgA)).isEqualTo(3);
        var confirmed = confirmedLot("VERIFIED-LOT", "registry:maker-a");
        reject("23505", confirmedSql(), UUID.randomUUID(), f.orgA, f.productA, "VERIFIED-LOT", "registry:maker-a", f.memberA);
        sql(confirmedSql(), UUID.randomUUID(), f.orgA, f.productA, "VERIFIED-LOT", "registry:maker-b", f.memberA);
        sql(confirmedSql(), UUID.randomUUID(), f.orgB, f.productB, "VERIFIED-LOT", "registry:maker-a", f.memberB);
        var anotherProduct = UUID.randomUUID();
        sql(productSql(), anotherProduct, f.orgA, "OTHER-PRODUCT", null);
        sql(confirmedSql(), UUID.randomUUID(), f.orgA, anotherProduct, "VERIFIED-LOT", "registry:maker-a", f.memberA);
        reject("23514", "UPDATE stockrescue.manufacturer_lot SET manufacturer_identity_key='different',version=version+1 WHERE id=?", confirmed);
        reject("23514", "UPDATE stockrescue.manufacturer_lot SET identity_state='CANDIDATE',version=version+1 WHERE id=?", confirmed);
        reject("23514", "UPDATE stockrescue.manufacturer_lot SET identity_state='CONFIRMED',version=version+1 WHERE id=?", firstCandidate);
        reject("23503", confirmedSql(), UUID.randomUUID(), f.orgA, f.productA, "FOREIGN-WITNESS", "registry:maker-a", f.memberB);
        assertThat(text("SELECT manufacturer_identity_key FROM stockrescue.manufacturer_lot WHERE id=?", firstCandidate)).isEqualTo("CLAIM-A");
        assertThat(text("SELECT manufacturer_identity_key FROM stockrescue.manufacturer_lot WHERE id=?", secondCandidate)).isEqualTo("CLAIM-B");
        var confirm = "UPDATE stockrescue.manufacturer_lot SET identity_state='CONFIRMED',manufacturer_reference='reviewed-reference',confirmed_by_membership_id=?,confirmed_at=clock_timestamp(),version=version+1 WHERE id=?";
        sql(confirm, f.memberA, firstCandidate);
        reject("23505", confirm, f.memberA, repeatedClaim);
        assertThat(text("SELECT identity_state FROM stockrescue.manufacturer_lot WHERE id=?", repeatedClaim)).isEqualTo("CANDIDATE");
    }

    @Test
    void everyLotAndSourceAssociationRejectsForeignOrganizationIdentities() throws Exception {
        var lotA = lot(f.orgA, f.productA, "A");
        var lotB = lot(f.orgB, f.productB, "B");
        reject("23503", lotSql(), UUID.randomUUID(), f.orgA, f.productB, "CROSS-PRODUCT");
        reject("23503", sourceSql(), UUID.randomUUID(), f.orgA, lotB, f.storeA);
        reject("23503", sourceSql(), UUID.randomUUID(), f.orgA, lotA, f.storeB);
        var source = source(lotA, f.storeA);
        reject("23514", "UPDATE stockrescue.consignment_evidence SET lot_id=?,version=version+1 WHERE id=?", lotB, source);
        reject("23514", "UPDATE stockrescue.manufacturer_lot SET product_id=?,version=version+1 WHERE id=?", f.productB, lotA);
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conname IN ('lot_product_fk','lot_confirmation_actor_fk','consignment_lot_fk','consignment_store_fk') AND confdeltype='r'")).isEqualTo(4);
    }

    @Test
    void separateConsignmentsCanShareOneConfirmedLotAndSupplierReference() throws Exception {
        var lot = confirmedLot("ONE-PRODUCER-LOT", "registry:maker-a");
        var first = source(lot, f.storeA);
        var second = source(lot, f.storeA);
        var unscoped = source(lot, null);
        assertThat(first).isNotEqualTo(second);
        assertThat(count("SELECT count(*) FROM stockrescue.consignment_evidence WHERE org_id=? AND lot_id=? AND supplier_reference='supplier-lot-17'", f.orgA, lot)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM stockrescue.consignment_evidence WHERE org_id=? AND lot_id=? AND store_id IS NULL AND id=?", f.orgA, lot, unscoped)).isEqualTo(1);
        reject("23514", "UPDATE stockrescue.consignment_evidence SET store_id=NULL,version=version+1 WHERE id=?", first);
        reject("23514", "UPDATE stockrescue.consignment_evidence SET provenance_source=' ',version=version+1 WHERE id=?", second);
    }

    @Test
    void dateLabelsAndPrecisionRoundTripWithoutInventingCutoffsOrSafetyClearance() throws Exception {
        var provenance = new EvidenceProvenance("  supplier label  ", "document-17");
        for (var marking : List.of(
                new DateMarking("30/04/2027", DateMarkingType.USE_BY, DatePrecision.DAY, Optional.of(provenance), Optional.empty()),
                new DateMarking(" 04/2027 ", DateMarkingType.BEST_BEFORE_END, DatePrecision.MONTH, Optional.of(provenance), Optional.empty()),
                new DateMarking("2027", DateMarkingType.BEST_BEFORE_END, DatePrecision.YEAR, Optional.of(provenance), Optional.empty()),
                new DateMarking("31/02/2027", DateMarkingType.USE_BY, DatePrecision.DAY, Optional.of(provenance), Optional.empty()),
                new DateMarking(null, DateMarkingType.UNKNOWN, DatePrecision.UNKNOWN, Optional.empty(), Optional.empty()))) {
            var lot = lot(f.orgA, f.productA, UUID.randomUUID().toString());
            sql("""
                    UPDATE stockrescue.manufacturer_lot SET labeled_value=?,date_marking_type=?,date_precision=?,
                        marking_source=?,marking_reference=?,version=version+1 WHERE id=?
                    """, marking.labeledValue(), marking.type().name(), marking.precision().name(),
                    marking.provenance().map(EvidenceProvenance::source).orElse(null),
                    marking.provenance().map(EvidenceProvenance::reference).orElse(null), lot);
            try (var statement = connection.prepareStatement("SELECT * FROM stockrescue.manufacturer_lot WHERE org_id=? AND id=?")) {
                statement.setObject(1, f.orgA);
                statement.setObject(2, lot);
                try (var row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(new DateMarking(row.getString("labeled_value"), DateMarkingType.valueOf(row.getString("date_marking_type")),
                            DatePrecision.valueOf(row.getString("date_precision")), row.getString("marking_source") == null ? Optional.empty()
                            : Optional.of(new EvidenceProvenance(row.getString("marking_source"), row.getString("marking_reference"))), Optional.empty()))
                            .isEqualTo(marking);
                    assertThat(row.getDate("normalized_cutoff")).isNull();
                    assertThat(row.getString("recall_state")).isEqualTo("UNKNOWN");
                    assertThat(row.getString("damage_state")).isEqualTo("UNKNOWN");
                }
            }
        }
        var lot = lot(f.orgA, f.productA, "INVALID-DATE-METADATA");
        reject("23514", "UPDATE stockrescue.manufacturer_lot SET date_precision='WEEK',version=version+1 WHERE id=?", lot);
        reject("23514", "UPDATE stockrescue.manufacturer_lot SET date_marking_type='EXPIRY',version=version+1 WHERE id=?", lot);
        reject("23514", "UPDATE stockrescue.manufacturer_lot SET recall_state='NOT_REPORTED',version=version+1 WHERE id=?", lot);
        reject("23514", "UPDATE stockrescue.manufacturer_lot SET marking_source=?,version=version+1 WHERE id=?", "label\u0085source", lot);
        reject("23514", """
                UPDATE stockrescue.manufacturer_lot SET labeled_value='30/04/2027',date_marking_type='USE_BY',date_precision='DAY',
                    marking_source='label',normalized_cutoff='2027-04-30',normalization_policy_version_id=?,
                    normalization_policy_version=1,normalization_version='rule-1',version=version+1 WHERE id=?
                """, UUID.randomUUID(), lot);
    }

    @Test
    void archivalRetainsIdentityAndRequiresMonotonicVersions() throws Exception {
        var lot = lot(f.orgA, f.productA, "ARCHIVE");
        var source = source(lot, f.storeA);
        reject("23514", "UPDATE stockrescue.product SET version=-1 WHERE id=?", f.productA);
        reject("23514", "UPDATE stockrescue.manufacturer_lot SET version=2 WHERE id=?", lot);
        reject("23514", "UPDATE stockrescue.manufacturer_lot SET active=FALSE,version=version+1 WHERE id=?", lot);
        reject("23514", "UPDATE stockrescue.consignment_evidence SET version=1,created_at=created_at-interval '1 second' WHERE id=?", source);
        reject("23514", "UPDATE stockrescue.product SET sku='NEW-KEY',version=1 WHERE id=?", f.productA);
        for (var row : List.of(new TableRow("product", f.productA), new TableRow("manufacturer_lot", lot), new TableRow("consignment_evidence", source))) {
            if (row.table.equals("manufacturer_lot")) {
                var request = UUID.randomUUID();
                sql("""
                        INSERT INTO stockrescue.lot_archive_command(org_id,actor_user_id,request_id,lot_id,result_version,payload_digest)
                        VALUES (?,?,?,?,1,?)
                        """, f.orgA, f.userA, request, lot, new byte[32]);
                sql("""
                        INSERT INTO stockrescue.audit_entry(id,actor_user_id,org_id,resource_type,resource_id,action,reason,correlation_id)
                        VALUES (?,?,?,'manufacturer-lot',?,'LOT_ARCHIVED','Persistence archive fixture','catalog-lot-archive-test')
                        """, UUID.randomUUID(), f.userA, f.orgA, lot);
            }
            sql("UPDATE stockrescue." + row.table + " SET active=FALSE,version=version+1 WHERE id=?", row.id);
            assertThat(count("SELECT count(*) FROM stockrescue." + row.table + " WHERE id=? AND NOT active AND version=1 AND archived_at IS NOT NULL AND updated_at>=created_at", row.id)).isEqualTo(1);
            reject("23514", "UPDATE stockrescue." + row.table + " SET active=TRUE,version=version+1 WHERE id=?", row.id);
            reject("23514", "UPDATE stockrescue." + row.table + " SET version=version+1 WHERE id=?", row.id);
            reject("55000", "DELETE FROM stockrescue." + row.table + " WHERE id=?", row.id);
        }
        reject("23505", productSql(), UUID.randomUUID(), f.orgA, "MILK-1", null);
        // Run deferred archival checks before testing the dedicated TRUNCATE protection.
        sql("SET CONSTRAINTS ALL IMMEDIATE");
        reject("55000", "TRUNCATE stockrescue.lot_archive_command");
        assertThat(count("SELECT count(*) FROM stockrescue.consignment_evidence WHERE org_id=? AND lot_id=?", f.orgA, lot)).isEqualTo(1);
    }

    @Test
    void aConstraintFailureRollsBackProductLotSourceAndAuditTogether() throws Exception {
        var savepoint = connection.setSavepoint();
        var product = UUID.randomUUID();
        var lot = UUID.randomUUID();
        var source = UUID.randomUUID();
        var audit = UUID.randomUUID();
        sql(productSql(), product, f.orgA, "ROLLBACK", null);
        sql(lotSql(), lot, f.orgA, product, "ROLLBACK-LOT");
        sql(sourceSql(), source, f.orgA, lot, f.storeA);
        sql("INSERT INTO stockrescue.audit_entry(id,actor_user_id,org_id,resource_type,resource_id,action,correlation_id) VALUES (?,?,?,'product',?,'PRODUCT_CREATED','catalog-rollback')", audit, f.userA, f.orgA, product);
        assertThatThrownBy(() -> sql(sourceSql(), UUID.randomUUID(), f.orgA, lot, f.storeB)).isInstanceOf(SQLException.class);
        connection.rollback(savepoint);
        for (var row : List.of(new TableRow("product", product), new TableRow("manufacturer_lot", lot), new TableRow("consignment_evidence", source), new TableRow("audit_entry", audit))) {
            assertThat(count("SELECT count(*) FROM stockrescue." + row.table + " WHERE id=?", row.id)).isZero();
        }
    }

    @Test
    void concurrentSkuCollisionsHaveOneDurableWinner() throws Exception {
        connection.commit();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var outcomes = new ArrayList<java.util.concurrent.Future<String>>();
            for (int attempt = 0; attempt < 2; attempt++) outcomes.add(workers.submit(() -> {
                try (var other = open(POSTGRES.getJdbcUrl())) {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    execute(other, productSql(), UUID.randomUUID(), f.orgA, "RACE-KEY", null);
                    return "CREATED";
                } catch (SQLException collision) { return collision.getSQLState(); }
            }));
            start.countDown();
            assertThat(List.of(outcomes.get(0).get(15, TimeUnit.SECONDS), outcomes.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED", "23505");
        }
        assertThat(count("SELECT count(*) FROM stockrescue.product WHERE org_id=? AND sku_normalized='RACE-KEY'", f.orgA)).isEqualTo(1);
    }

    @Test
    void scopedLookupIndexesSupportProductLotAndSourceQueries() throws Exception {
        var lot = lot(f.orgA, f.productA, "LOOKUP");
        source(lot, f.storeA);
        sql("SET LOCAL enable_seqscan=off");
        assertThat(plan("SELECT id FROM stockrescue.product WHERE org_id=? AND sku_normalized='MILK-1'", f.orgA)).contains("product_org_sku_unique");
        assertThat(text("SELECT sku_normalized FROM stockrescue.product WHERE org_id=? AND sku_normalized LIKE 'MILK%'", f.orgA)).isEqualTo("MILK-1");
        assertThat(plan("SELECT id FROM stockrescue.manufacturer_lot WHERE org_id=? AND product_id=? AND lot_code='LOOKUP'", f.orgA, f.productA)).contains("lot_product_code_lookup_idx");
        assertThat(plan("SELECT id FROM stockrescue.consignment_evidence WHERE org_id=? AND lot_id=? ORDER BY created_at,id", f.orgA, lot)).contains("consignment_lot_trace_idx");
        assertThat(plan("SELECT id FROM stockrescue.consignment_evidence WHERE org_id=? AND store_id=? AND lot_id=? ORDER BY created_at,id", f.orgA, f.storeA, lot)).contains("consignment_store_trace_idx");
    }

    @Test
    void cleanDatabaseAppliesAllMigrationsAndRevalidatesCatalogStorage() throws Exception {
        try (var clean = isolatedDatabase()) {
            clean.start();
            var flyway = migrations(clean.getJdbcUrl());
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(MigrationAssertions.CURRENT_VERSION);
            flyway.validate();
            MigrationAssertions.assertCurrent(flyway);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var current = open(clean.getJdbcUrl()); var statement = current.createStatement();
                    var row = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='stockrescue' AND table_name IN ('product','manufacturer_lot','consignment_evidence')")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isEqualTo(3);
            }
        }
    }

    @Test
    void existingStoreSchemaUpgradesWithoutRewritingHistoryOrLosingData() throws Exception {
        try (var previous = isolatedDatabase()) {
            previous.start();
            var baseline = Flyway.configure().dataSource(previous.getJdbcUrl(), "stockrescue_app", "test-app-password")
                    .defaultSchema("public").target("8").cleanDisabled(true).load();
            assertThat(baseline.migrate().migrationsExecuted).isEqualTo(8);
            String history;
            UUID persistedOrg;
            UUID persistedStore;
            try (var old = open(previous.getJdbcUrl())) {
                // Product tables do not exist at this baseline, so preserve a real store instead.
                var user = UUID.randomUUID();
                var org = UUID.randomUUID();
                var store = UUID.randomUUID();
                execute(old, "INSERT INTO stockrescue.user_account(id,email_display) VALUES (?,?)", user, user + "@example.test");
                execute(old, "INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (?,'Before catalog',?)", org, user);
                execute(old, "INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (?,?,'PERSISTED','Existing Store','Europe/Warsaw')", store, org);
                persistedOrg = org;
                persistedStore = store;
                history = history(old);
            }
            var upgraded = migrations(previous.getJdbcUrl());
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(MigrationAssertions.migrationsAfter(8));
            upgraded.validate();
            MigrationAssertions.assertCurrent(upgraded);
            try (var current = open(previous.getJdbcUrl())) {
                assertThat(history(current)).isEqualTo(history);
                try (var query = current.prepareStatement("SELECT name FROM stockrescue.store WHERE org_id=? AND id=?")) {
                    query.setObject(1, persistedOrg);
                    query.setObject(2, persistedStore);
                    try (var data = query.executeQuery()) { assertThat(data.next()).isTrue(); assertThat(data.getString(1)).isEqualTo("Existing Store"); }
                }
                execute(current, productSql(), UUID.randomUUID(), persistedOrg, "AFTER-UPGRADE", "036000291452");
            }
            assertThat(migrations(previous.getJdbcUrl()).migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void v010ProductsAndPolicyReviewsUpgradeWithoutLossOrAccidentalClassification() throws Exception {
        try (var previous = isolatedDatabase()) {
            previous.start();
            var baseline = Flyway.configure().dataSource(previous.getJdbcUrl(), "stockrescue_app", "test-app-password")
                    .defaultSchema("public").target("10").cleanDisabled(true).load();
            assertThat(baseline.migrate().migrationsExecuted).isEqualTo(10);

            var user = UUID.randomUUID();
            var org = UUID.randomUUID();
            var membership = UUID.randomUUID();
            var product = UUID.randomUUID();
            var lot = UUID.randomUUID();
            var audit = UUID.randomUUID();
            var policy = UUID.randomUUID();
            var profile = UUID.randomUUID();
            var request = UUID.randomUUID();
            var gtin = "036000291452";
            var digest = new byte[32];
            java.util.Arrays.fill(digest, (byte) 7);
            try (var old = open(previous.getJdbcUrl())) {
                execute(old, "INSERT INTO stockrescue.user_account(id,email_display) VALUES (?,?)", user, user + "@example.test");
                execute(old, "INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (?,'Legacy catalog',?)", org, user);
                execute(old, "INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (?,?,?,'ORG_ADMIN')", membership, org, user);
                execute(old, productSql(), product, org, "LEGACY-MILK", gtin);
                execute(old, "INSERT INTO stockrescue.manufacturer_lot(id,org_id,product_id,lot_code) VALUES (?,?,?,'LEGACY-LOT')",
                        lot, org, product);
                execute(old, """
                        INSERT INTO stockrescue.audit_entry(id,actor_user_id,org_id,resource_type,resource_id,action,reason,correlation_id)
                        VALUES (?, ?, ?, 'product', ?, 'PRODUCT_CREATED', 'Legacy catalog evidence', 'migration-upgrade-probe')
                        """, audit, user, org, product);
                execute(old, """
                        INSERT INTO stockrescue.food_safety_policy_version(id,org_id,profile_id,temperature_class,version,
                            partial_date_rule,review_source,review_reference,reason,actor_user_id,recorded_by_membership_id,
                            request_id,payload_digest)
                        VALUES (?, ?, ?, 'CHILLED', 1, 'NO_NORMALIZATION', 'legacy review', 'legacy-policy-1',
                            'Preserve reviewed policy history', ?, ?, ?, ?)
                        """, policy, org, profile, user, membership, request, digest);
            }

            var upgraded = migrations(previous.getJdbcUrl());
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(MigrationAssertions.migrationsAfter(10));
            upgraded.validate();
            MigrationAssertions.assertCurrent(upgraded);
            try (var current = open(previous.getJdbcUrl())) {
                assertThat(text(current, "SELECT category FROM stockrescue.product WHERE org_id=? AND id=?", org, product))
                        .isEqualTo("UNCLASSIFIED");
                assertThat(text(current, "SELECT version::text FROM stockrescue.product WHERE org_id=? AND id=?", org, product))
                        .isEqualTo("0");
                assertThat(text(current, "SELECT product_id::text FROM stockrescue.manufacturer_lot WHERE org_id=? AND id=?", org, lot))
                        .isEqualTo(product.toString());
                assertThat(count(current, "SELECT count(*) FROM stockrescue.audit_entry WHERE org_id=? AND id=? AND resource_id=?", org, audit, product))
                        .isEqualTo(1);
                assertThat(text(current, "SELECT product_category FROM stockrescue.food_safety_policy_version WHERE org_id=? AND id=?", org, policy))
                        .isEqualTo("UNCLASSIFIED");
                assertThat(text(current, "SELECT gtin FROM stockrescue.product WHERE org_id=? AND id=?", org, product)).isEqualTo(gtin);
                assertThat(text(current, "SELECT review_reference FROM stockrescue.food_safety_policy_version WHERE org_id=? AND id=?", org, policy))
                        .isEqualTo("legacy-policy-1");

                var dairyProfile = UUID.randomUUID();
                var bakeryProfile = UUID.randomUUID();
                insertPolicy(current, UUID.randomUUID(), org, dairyProfile, "DAIRY", user, membership, UUID.randomUUID());
                insertPolicy(current, UUID.randomUUID(), org, bakeryProfile, "BAKERY", user, membership, UUID.randomUUID());
                assertThat(count(current, "SELECT count(*) FROM stockrescue.food_safety_policy_version WHERE org_id=? AND temperature_class='CHILLED' AND version=1", org))
                        .isEqualTo(3);
                assertThatThrownBy(() -> insertPolicy(current, UUID.randomUUID(), org, dairyProfile, "BAKERY", user, membership, UUID.randomUUID()))
                        .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
                assertThatThrownBy(() -> execute(current,
                        "UPDATE stockrescue.product SET category='UNSUPPORTED' WHERE org_id=? AND id=?", org, product))
                        .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
            }
            assertThat(migrations(previous.getJdbcUrl()).migrate().migrationsExecuted).isZero();
        }
    }

    private static Flyway migrations(String url) {
        return Flyway.configure().dataSource(url, "stockrescue_app", "test-app-password").defaultSchema("public").cleanDisabled(true).load();
    }

    private static void insertPolicy(Connection target, UUID id, UUID org, UUID profile, String category,
            UUID actor, UUID membership, UUID request) throws Exception {
        execute(target, """
                INSERT INTO stockrescue.food_safety_policy_version(id,org_id,profile_id,product_category,temperature_class,
                    version,partial_date_rule,review_source,review_reference,reason,actor_user_id,recorded_by_membership_id,
                    request_id,payload_digest)
                VALUES (?, ?, ?, ?, 'CHILLED', 1, 'NO_NORMALIZATION', 'review source', 'policy-reference',
                    'Scoped review', ?, ?, ?, ?)
                """, id, org, profile, category, actor, membership, request, new byte[32]);
    }

    private static String text(Connection target, String query, Object... parameters) throws SQLException {
        try (var statement = target.prepareStatement(query)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Expected one row from migration verification query");
                return row.getString(1);
            }
        }
    }

    private static long count(Connection target, String query, Object... parameters) throws SQLException {
        return Long.parseLong(text(target, query, parameters));
    }

    private PostgreSQLContainer isolatedDatabase() {
        return new PostgreSQLContainer(DockerImageName.parse(POSTGRES.getDockerImageName()))
                .withDatabaseName("stockrescue_test").withUsername("test_admin").withPassword("test-admin-password")
                .withInitScript("db/test-role.sql");
    }

    private static Connection open(String url) throws SQLException {
        var connection = DriverManager.getConnection(url, "stockrescue_app", "test-app-password");
        connection.setSchema("stockrescue");
        return connection;
    }

    private Fixture fixture(Connection target) throws Exception {
        var f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        execute(target, "INSERT INTO stockrescue.user_account(id,email_display) VALUES (?,?),(?,?)", f.userA, f.userA + "@example.test", f.userB, f.userB + "@example.test");
        execute(target, "INSERT INTO stockrescue.organization(id,name,created_by_user_id) VALUES (?,'Retailer A',?),(?,'Retailer B',?)", f.orgA, f.userA, f.orgB, f.userB);
        execute(target, "INSERT INTO stockrescue.membership(id,org_id,user_id,org_role) VALUES (?,?,?,'ORG_ADMIN'),(?,?,?,'ORG_ADMIN')", f.memberA, f.orgA, f.userA, f.memberB, f.orgB, f.userB);
        execute(target, "INSERT INTO stockrescue.store(id,org_id,code,name,zone_id) VALUES (?,?,'NORTH','North','UTC'),(?,?,'NORTH','North','UTC')", f.storeA, f.orgA, f.storeB, f.orgB);
        execute(target, productSql(), f.productA, f.orgA, " milk-1 ", "036000291452");
        execute(target, productSql(), f.productB, f.orgB, "MILK-1", null);
        return f;
    }

    private String productSql() { return "INSERT INTO stockrescue.product(id,org_id,sku,name,gtin,base_unit,temperature_class) VALUES (?,?,?,'Milk',?,'EA','CHILLED')"; }
    private String lotSql() { return "INSERT INTO stockrescue.manufacturer_lot(id,org_id,product_id,lot_code) VALUES (?,?,?,?)"; }
    private String confirmedSql() { return """
            INSERT INTO stockrescue.manufacturer_lot(id,org_id,product_id,lot_code,identity_state,manufacturer_identity_key,
                manufacturer_source,manufacturer_reference,confirmed_by_membership_id,confirmed_at)
            VALUES (?,?,?,?,'CONFIRMED',?,'reviewed manufacturer registry','registry-document-17',?,clock_timestamp())
            """; }
    private String sourceSql() { return """
            INSERT INTO stockrescue.consignment_evidence(id,org_id,lot_id,store_id,supplier_name,supplier_reference,provenance_source,provenance_reference)
            VALUES (?,?,?,?,'Supplier','supplier-lot-17','supplier document','source-document-17')
            """; }
    private UUID lot(UUID org, UUID product, String code) throws Exception { var id = UUID.randomUUID(); sql(lotSql(), id, org, product, code); return id; }
    private UUID confirmedLot(String code, String manufacturer) throws Exception { var id = UUID.randomUUID(); sql(confirmedSql(), id, f.orgA, f.productA, code, manufacturer, f.memberA); return id; }
    private UUID source(UUID lot, UUID store) throws Exception { var id = UUID.randomUUID(); sql(sourceSql(), id, f.orgA, lot, store); return id; }
    private void sql(String query, Object... parameters) throws Exception { execute(connection, query, parameters); }
    private static void execute(Connection target, String query, Object... parameters) throws Exception {
        try (var statement = target.prepareStatement(query)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            statement.setQueryTimeout(15);
            statement.execute();
        }
    }
    private void reject(String state, String query, Object... parameters) throws Exception {
        var savepoint = connection.setSavepoint();
        try {
            assertThatThrownBy(() -> sql(query, parameters)).isInstanceOfSatisfying(SQLException.class,
                    error -> assertThat(error.getSQLState()).isEqualTo(state));
        } finally { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
    }
    private String text(String query, Object... parameters) throws Exception {
        try (var statement = connection.prepareStatement(query)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); return row.getString(1); }
        }
    }
    private long count(String query, Object... parameters) throws Exception { return Long.parseLong(text(query, parameters)); }
    private String plan(String query, Object... parameters) throws Exception {
        try (var statement = connection.prepareStatement("EXPLAIN (COSTS OFF) " + query)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            try (var rows = statement.executeQuery()) {
                var lines = new ArrayList<String>();
                while (rows.next()) lines.add(rows.getString(1));
                return String.join("\n", lines);
            }
        }
    }
    private static String history(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT json_agg(h ORDER BY installed_rank)::text FROM public.flyway_schema_history h WHERE version::integer<=8")) {
            assertThat(row.next()).isTrue(); return row.getString(1);
        }
    }
    private record Fixture(UUID userA, UUID userB, UUID orgA, UUID orgB, UUID memberA, UUID memberB, UUID storeA, UUID storeB, UUID productA, UUID productB) { }
    private record TableRow(String table, UUID id) { }
}
