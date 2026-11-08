package com.stockrescue.catalog.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmation;
import com.stockrescue.catalog.application.FoodSafetyProductClassificationConfirmation.SourceType;
import com.stockrescue.catalog.domain.ProductCategory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FoodSafetyProductClassificationConfirmationRepository {
    private final JdbcClient jdbc;

    public FoodSafetyProductClassificationConfirmationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FoodSafetyProductClassificationConfirmation> latest(UUID organizationId, UUID productId) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.food_safety_product_classification_confirmation
                WHERE org_id=:org AND product_id=:product
                ORDER BY product_version DESC, recorded_at DESC, id DESC LIMIT 1
                """).param("org", organizationId).param("product", productId)
                .query((row, index) -> confirmation(row)).optional();
    }

    public Optional<FoodSafetyProductClassificationConfirmation> byRequest(
            UUID organizationId, UUID actorUserId, UUID requestId) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.food_safety_product_classification_confirmation
                WHERE org_id=:org AND actor_user_id=:actor AND request_id=:request
                """).param("org", organizationId).param("actor", actorUserId).param("request", requestId)
                .query((row, index) -> confirmation(row)).optional();
    }

    public FoodSafetyProductClassificationConfirmation append(FoodSafetyProductClassificationConfirmation value) {
        return jdbc.sql("""
                INSERT INTO stockrescue.food_safety_product_classification_confirmation(
                    id,org_id,product_id,sku,expected_product_version,product_version,original_category,confirmed_category,
                    original_temperature_class,confirmed_temperature_class,product_profile,source_type,evidence_reference,
                    actor_user_id,recorded_by_membership_id,request_id,reason,payload_digest,recorded_at)
                VALUES (:id,:org,:product,:sku,:expectedVersion,:version,:originalCategory,:category,:originalTemperature,:temperature,:profile,
                    :sourceType,:reference,:actor,:membership,:request,:reason,:digest,:recordedAt)
                RETURNING *
                """).param("id", value.id()).param("org", value.organizationId()).param("product", value.productId())
                .param("sku", value.sku()).param("expectedVersion", value.expectedProductVersion())
                .param("version", value.productVersion()).param("originalCategory", value.originalCategory().name())
                .param("category", value.confirmedCategory().name()).param("originalTemperature",value.originalTemperatureClass().name())
                .param("temperature",value.confirmedTemperatureClass().name()).param("profile",value.productProfile().name())
                .param("sourceType", value.sourceType() == null ? null : value.sourceType().name(), java.sql.Types.VARCHAR)
                .param("reference", value.evidenceReference(), java.sql.Types.VARCHAR).param("actor", value.actorUserId())
                .param("membership", value.recordedByMembershipId()).param("request", value.requestId())
                .param("reason", value.reason()).param("digest", value.payloadDigest())
                .param("recordedAt", timestamp(value.recordedAt()))
                .query((row, index) -> confirmation(row)).single();
    }

    private FoodSafetyProductClassificationConfirmation confirmation(ResultSet row) throws SQLException {
        var sourceType = row.getString("source_type");
        return new FoodSafetyProductClassificationConfirmation(row.getObject("id", UUID.class),
                row.getObject("org_id", UUID.class), row.getObject("product_id", UUID.class), row.getString("sku"),
                row.getLong("expected_product_version"), row.getLong("product_version"),
                ProductCategory.valueOf(row.getString("original_category")),
                ProductCategory.valueOf(row.getString("confirmed_category")),
                com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass.valueOf(row.getString("original_temperature_class")),
                com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass.valueOf(row.getString("confirmed_temperature_class")),
                FoodSafetyProductClassificationConfirmation.ProductProfile.valueOf(row.getString("product_profile")),
                sourceType == null ? null : SourceType.valueOf(sourceType), row.getString("evidence_reference"),
                row.getObject("actor_user_id", UUID.class), row.getObject("recorded_by_membership_id", UUID.class),
                row.getObject("request_id", UUID.class), row.getString("reason"), row.getBytes("payload_digest"),
                row.getTimestamp("recorded_at").toInstant());
    }

    private OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
