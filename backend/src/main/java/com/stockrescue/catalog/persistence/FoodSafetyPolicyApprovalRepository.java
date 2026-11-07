package com.stockrescue.catalog.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import com.stockrescue.catalog.application.FoodSafetyEvaluation.Operation;
import com.stockrescue.catalog.application.FoodSafetyPolicyApproval;
import com.stockrescue.catalog.application.FoodSafetyPolicyApprovalRevocation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FoodSafetyPolicyApprovalRepository {
    private final JdbcClient jdbc;
    public FoodSafetyPolicyApprovalRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    public Optional<FoodSafetyPolicyApproval> forPolicy(UUID organizationId,UUID policyVersionId) {
        return jdbc.sql("SELECT * FROM stockrescue.food_safety_policy_approval WHERE org_id=:org AND policy_version_id=:policy")
                .param("org",organizationId).param("policy",policyVersionId)
                .query((row,index) -> approval(row)).optional();
    }

    public Optional<FoodSafetyPolicyApproval> byId(UUID organizationId,UUID approvalId) {
        return jdbc.sql("SELECT * FROM stockrescue.food_safety_policy_approval WHERE org_id=:org AND id=:id")
                .param("org",organizationId).param("id",approvalId)
                .query((row,index) -> approval(row)).optional();
    }

    public Optional<FoodSafetyPolicyApproval> receipt(UUID organizationId,UUID actorUserId,UUID requestId) {
        return jdbc.sql("SELECT * FROM stockrescue.food_safety_policy_approval WHERE org_id=:org AND actor_user_id=:actor AND request_id=:request")
                .param("org",organizationId).param("actor",actorUserId).param("request",requestId)
                .query((row,index) -> approval(row)).optional();
    }

    public FoodSafetyPolicyApproval append(FoodSafetyPolicyApproval value) {
        return jdbc.sql("""
                INSERT INTO stockrescue.food_safety_policy_approval(id,org_id,policy_version_id,profile_id,product_category,temperature_class,
                    approved_sale,approved_transfer,policy_digest,issuer_id,key_id,issued_at,valid_from,valid_until,evidence_reference,signature,
                    actor_user_id,recorded_by_membership_id,request_id,payload_digest)
                VALUES (:id,:org,:policy,:profile,:category,:temperature,:sale,:transfer,:policyDigest,:issuer,:key,:issuedAt,:validFrom,
                    :validUntil,:reference,:signature,:actor,:membership,:request,:payloadDigest) RETURNING *
                """).param("id",value.id()).param("org",value.organizationId()).param("policy",value.policyVersionId())
                .param("profile",value.profileId()).param("category",value.productCategory().name()).param("temperature",value.temperatureClass().name())
                .param("sale",value.allowedOperations().contains(Operation.SALE)).param("transfer",value.allowedOperations().contains(Operation.TRANSFER))
                .param("policyDigest",value.policyDigest()).param("issuer",value.issuerId()).param("key",value.keyId())
                .param("issuedAt",timestamp(value.issuedAt())).param("validFrom",timestamp(value.validFrom())).param("validUntil",timestamp(value.validUntil()))
                .param("reference",value.evidenceReference()).param("signature",value.signature()).param("actor",value.actorUserId())
                .param("membership",value.recordedByMembershipId()).param("request",value.requestId()).param("payloadDigest",value.payloadDigest())
                .query((row,index) -> approval(row)).single();
    }

    public Optional<FoodSafetyPolicyApprovalRevocation> revocation(UUID organizationId,UUID approvalId) {
        return jdbc.sql("SELECT * FROM stockrescue.food_safety_policy_approval_revocation WHERE org_id=:org AND approval_id=:approval")
                .param("org",organizationId).param("approval",approvalId)
                .query((row,index) -> revocation(row)).optional();
    }

    public Optional<FoodSafetyPolicyApprovalRevocation> revocationReceipt(UUID organizationId,UUID actorUserId,UUID requestId) {
        return jdbc.sql("SELECT * FROM stockrescue.food_safety_policy_approval_revocation WHERE org_id=:org AND actor_user_id=:actor AND request_id=:request")
                .param("org",organizationId).param("actor",actorUserId).param("request",requestId)
                .query((row,index) -> revocation(row)).optional();
    }

    public FoodSafetyPolicyApprovalRevocation revoke(FoodSafetyPolicyApprovalRevocation value) {
        return jdbc.sql("""
                INSERT INTO stockrescue.food_safety_policy_approval_revocation(id,org_id,approval_id,actor_user_id,
                    recorded_by_membership_id,request_id,reason,payload_digest)
                VALUES (:id,:org,:approval,:actor,:membership,:request,:reason,:digest) RETURNING *
                """).param("id",value.id()).param("org",value.organizationId()).param("approval",value.approvalId())
                .param("actor",value.actorUserId()).param("membership",value.recordedByMembershipId()).param("request",value.requestId())
                .param("reason",value.reason()).param("digest",value.payloadDigest()).query((row,index) -> revocation(row)).single();
    }

    private FoodSafetyPolicyApproval approval(ResultSet row) throws SQLException {
        var operations=EnumSet.noneOf(Operation.class);
        if (row.getBoolean("approved_sale")) operations.add(Operation.SALE);
        if (row.getBoolean("approved_transfer")) operations.add(Operation.TRANSFER);
        return new FoodSafetyPolicyApproval(row.getObject("id",UUID.class),row.getObject("org_id",UUID.class),
                row.getObject("policy_version_id",UUID.class),row.getObject("profile_id",UUID.class),
                com.stockrescue.catalog.domain.ProductCategory.valueOf(row.getString("product_category")),
                com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass.valueOf(row.getString("temperature_class")),
                operations,row.getBytes("policy_digest"),row.getString("issuer_id"),row.getString("key_id"),
                row.getTimestamp("issued_at").toInstant(),row.getTimestamp("valid_from").toInstant(),row.getTimestamp("valid_until").toInstant(),
                row.getString("evidence_reference"),row.getBytes("signature"),row.getObject("actor_user_id",UUID.class),
                row.getObject("recorded_by_membership_id",UUID.class),row.getObject("request_id",UUID.class),row.getBytes("payload_digest"),
                row.getTimestamp("recorded_at").toInstant());
    }

    private FoodSafetyPolicyApprovalRevocation revocation(ResultSet row) throws SQLException {
        return new FoodSafetyPolicyApprovalRevocation(row.getObject("id",UUID.class),row.getObject("org_id",UUID.class),
                row.getObject("approval_id",UUID.class),row.getObject("actor_user_id",UUID.class),
                row.getObject("recorded_by_membership_id",UUID.class),row.getObject("request_id",UUID.class),row.getString("reason"),
                row.getBytes("payload_digest"),row.getTimestamp("recorded_at").toInstant());
    }

    private OffsetDateTime timestamp(Instant value) { return OffsetDateTime.ofInstant(value,ZoneOffset.UTC); }
}
