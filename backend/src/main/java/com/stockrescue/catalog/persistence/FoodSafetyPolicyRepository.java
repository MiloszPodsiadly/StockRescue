package com.stockrescue.catalog.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.stockrescue.catalog.application.FoodSafetyPolicyReview;
import com.stockrescue.catalog.application.FoodSafetyPolicyVersion;
import com.stockrescue.catalog.application.FoodSafetyPolicyVersion.*;
import com.stockrescue.catalog.domain.EvidenceProvenance;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FoodSafetyPolicyRepository {
    public record Receipt(FoodSafetyPolicyVersion version,byte[] digest) { }
    private final JdbcClient jdbc;
    public FoodSafetyPolicyRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public Optional<FoodSafetyPolicyVersion> current(UUID org,ProductCategory category,TemperatureClass temperature) {
        return jdbc.sql("SELECT * FROM stockrescue.food_safety_policy_version WHERE org_id=:org AND product_category=:category AND temperature_class=:temperature ORDER BY version DESC LIMIT 1")
                .param("org",org).param("category",category.name()).param("temperature",temperature.name()).query((row,index) -> version(row)).optional();
    }
    public Optional<FoodSafetyPolicyVersion> byId(UUID org,UUID policyVersionId) {
        return jdbc.sql("SELECT * FROM stockrescue.food_safety_policy_version WHERE org_id=:org AND id=:id")
                .param("org",org).param("id",policyVersionId).query((row,index) -> version(row)).optional();
    }
    public Optional<Receipt> receipt(UUID org,UUID actor,UUID request) {
        return jdbc.sql("SELECT * FROM stockrescue.food_safety_policy_version WHERE org_id=:org AND actor_user_id=:actor AND request_id=:request")
                .param("org",org).param("actor",actor).param("request",request)
                .query((row,index) -> new Receipt(version(row),row.getBytes("payload_digest"))).optional();
    }
    public FoodSafetyPolicyVersion append(UUID org,UUID actor,UUID membership,UUID request,UUID profile,ProductCategory category,TemperatureClass temperature,FoodSafetyPolicyReview review) {
        return jdbc.sql("""
                INSERT INTO stockrescue.food_safety_policy_version(id,org_id,profile_id,product_category,temperature_class,version,partial_date_rule,
                    review_source,review_reference,reason,actor_user_id,recorded_by_membership_id,request_id,payload_digest)
                VALUES (:id,:org,:profile,:category,:temperature,:version,:rule,:source,:reference,:reason,:actor,:membership,:request,:digest) RETURNING *
                """).param("id",UUID.randomUUID()).param("org",org).param("profile",profile).param("category",category.name()).param("temperature",temperature.name())
                .param("version",review.expectedVersion()+1).param("rule",review.partialDateRule().name()).param("source",review.reviewProvenance().source())
                .param("reference",review.reviewProvenance().reference()).param("reason",review.reason()).param("actor",actor).param("membership",membership)
                .param("request",request).param("digest",review.fingerprint(category,temperature)).query((row,index) -> version(row)).single();
    }
    public List<FoodSafetyPolicyVersion> history(UUID org,ProductCategory category,TemperatureClass temperature,int offset,int limit) {
        return jdbc.sql("SELECT * FROM stockrescue.food_safety_policy_version WHERE org_id=:org AND product_category=:category AND temperature_class=:temperature ORDER BY version DESC LIMIT :limit OFFSET :offset")
                .param("org",org).param("category",category.name()).param("temperature",temperature.name()).param("offset",offset).param("limit",limit)
                .query((row,index) -> version(row)).list();
    }
    private FoodSafetyPolicyVersion version(ResultSet row) throws SQLException {
        return new FoodSafetyPolicyVersion(row.getObject("id",UUID.class),row.getObject("profile_id",UUID.class),ProductCategory.valueOf(row.getString("product_category")),TemperatureClass.valueOf(row.getString("temperature_class")),
                row.getLong("version"),PartialDateRule.valueOf(row.getString("partial_date_rule")),ApprovalStatus.valueOf(row.getString("approval_status")),
                new EvidenceProvenance(row.getString("review_source"),row.getString("review_reference")),row.getObject("recorded_by_membership_id",UUID.class),
                row.getTimestamp("recorded_at").toInstant(),row.getString("reason"));
    }
}
