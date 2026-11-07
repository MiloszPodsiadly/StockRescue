package com.stockrescue.catalog.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import com.stockrescue.catalog.application.FoodSafetyProductClassification;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationRevocation;
import com.stockrescue.catalog.domain.ProductCategory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FoodSafetyProductClassificationRepository {
    private final JdbcClient jdbc;
    public FoodSafetyProductClassificationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    public Optional<FoodSafetyProductClassification> latest(UUID organizationId,UUID productId) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.food_safety_product_classification
                WHERE org_id=:org AND product_id=:product ORDER BY recorded_at DESC,id DESC LIMIT 1
                """).param("org",organizationId).param("product",productId)
                .query((row,index) -> attestation(row)).optional();
    }

    public Optional<FoodSafetyProductClassification> byId(UUID organizationId,UUID productId,UUID attestationId) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.food_safety_product_classification
                WHERE org_id=:org AND product_id=:product AND id=:id
                """).param("org",organizationId).param("product",productId).param("id",attestationId)
                .query((row,index) -> attestation(row)).optional();
    }

    public Optional<FoodSafetyProductClassification> receipt(UUID organizationId,UUID actorUserId,UUID requestId) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.food_safety_product_classification
                WHERE org_id=:org AND actor_user_id=:actor AND request_id=:request
                """).param("org",organizationId).param("actor",actorUserId).param("request",requestId)
                .query((row,index) -> attestation(row)).optional();
    }

    public FoodSafetyProductClassification append(FoodSafetyProductClassification value) {
        return jdbc.sql("""
                INSERT INTO stockrescue.food_safety_product_classification(id,org_id,product_id,sku,product_category,product_version,
                    attestation_version,issuer_id,key_id,issued_at,valid_from,valid_until,evidence_reference,signature,actor_user_id,
                    recorded_by_membership_id,request_id,payload_digest)
                VALUES (:id,:org,:product,:sku,:category,:version,:attestationVersion,:issuer,:key,:issuedAt,:validFrom,:validUntil,
                    :reference,:signature,:actor,:membership,:request,:digest) RETURNING *
                """).param("id",value.id()).param("org",value.organizationId()).param("product",value.productId()).param("sku",value.sku())
                .param("category",value.category().name()).param("version",value.productVersion()).param("attestationVersion",value.attestationVersion())
                .param("issuer",value.issuerId()).param("key",value.keyId()).param("issuedAt",timestamp(value.issuedAt()))
                .param("validFrom",timestamp(value.validFrom())).param("validUntil",timestamp(value.validUntil())).param("reference",value.evidenceReference())
                .param("signature",value.signature()).param("actor",value.actorUserId()).param("membership",value.recordedByMembershipId())
                .param("request",value.requestId()).param("digest",value.payloadDigest()).query((row,index) -> attestation(row)).single();
    }

    public Optional<FoodSafetyProductClassificationRevocation> revocation(UUID organizationId,UUID attestationId) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.food_safety_product_classification_revocation WHERE org_id=:org AND attestation_id=:attestation
                """).param("org",organizationId).param("attestation",attestationId)
                .query((row,index) -> revocation(row)).optional();
    }

    public Optional<FoodSafetyProductClassificationRevocation> revocationReceipt(UUID organizationId,UUID actorUserId,UUID requestId) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.food_safety_product_classification_revocation
                WHERE org_id=:org AND actor_user_id=:actor AND request_id=:request
                """).param("org",organizationId).param("actor",actorUserId).param("request",requestId)
                .query((row,index) -> revocation(row)).optional();
    }

    public FoodSafetyProductClassificationRevocation revoke(FoodSafetyProductClassificationRevocation value) {
        return jdbc.sql("""
                INSERT INTO stockrescue.food_safety_product_classification_revocation(id,org_id,attestation_id,actor_user_id,
                    recorded_by_membership_id,request_id,reason,payload_digest)
                VALUES (:id,:org,:attestation,:actor,:membership,:request,:reason,:digest) RETURNING *
                """).param("id",value.id()).param("org",value.organizationId()).param("attestation",value.attestationId())
                .param("actor",value.actorUserId()).param("membership",value.recordedByMembershipId()).param("request",value.requestId())
                .param("reason",value.reason()).param("digest",value.payloadDigest()).query((row,index) -> revocation(row)).single();
    }

    private FoodSafetyProductClassification attestation(ResultSet row) throws SQLException {
        return new FoodSafetyProductClassification(row.getObject("id",UUID.class),row.getObject("org_id",UUID.class),
                row.getObject("product_id",UUID.class),row.getString("sku"),ProductCategory.valueOf(row.getString("product_category")),
                row.getLong("product_version"),row.getInt("attestation_version"),row.getString("issuer_id"),row.getString("key_id"),
                row.getTimestamp("issued_at").toInstant(),row.getTimestamp("valid_from").toInstant(),row.getTimestamp("valid_until").toInstant(),
                row.getString("evidence_reference"),row.getBytes("signature"),row.getObject("actor_user_id",UUID.class),
                row.getObject("recorded_by_membership_id",UUID.class),row.getObject("request_id",UUID.class),row.getBytes("payload_digest"),
                row.getTimestamp("recorded_at").toInstant());
    }

    private FoodSafetyProductClassificationRevocation revocation(ResultSet row) throws SQLException {
        return new FoodSafetyProductClassificationRevocation(row.getObject("id",UUID.class),row.getObject("org_id",UUID.class),
                row.getObject("attestation_id",UUID.class),row.getObject("actor_user_id",UUID.class),
                row.getObject("recorded_by_membership_id",UUID.class),row.getObject("request_id",UUID.class),row.getString("reason"),
                row.getBytes("payload_digest"),row.getTimestamp("recorded_at").toInstant());
    }

    private OffsetDateTime timestamp(Instant value) { return OffsetDateTime.ofInstant(value,ZoneOffset.UTC); }
}
