package com.stockrescue.catalog.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.stockrescue.catalog.application.Product;
import com.stockrescue.catalog.application.ProductData;
import com.stockrescue.catalog.domain.ProductCategory;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {
    public enum Operation { CREATE, ARCHIVE, CONFIRM_CLASSIFICATION }
    public record Receipt(UUID productId, long resultVersion, byte[] digest) { }
    public record GtinMatch(UUID productId,String sku,String name,String gtin,boolean active) { }
    private final JdbcClient jdbc;
    public ProductRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean reserve(UUID actor, UUID org, Operation operation, UUID request, UUID product, long version, byte[] digest) {
        // A competing insert waits for commit/rollback. Read the winning result in a new statement afterwards.
        return jdbc.sql("""
                INSERT INTO stockrescue.product_command(org_id,actor_user_id,operation,request_id,product_id,result_version,payload_digest)
                VALUES (:org,:actor,:operation,:request,:product,:version,:digest)
                ON CONFLICT (org_id,actor_user_id,operation,request_id) DO NOTHING
                """).param("org",org).param("actor",actor).param("operation",operation.name()).param("request",request)
                .param("product",product).param("version",version).param("digest",digest).update() == 1;
    }

    public Receipt receipt(UUID actor, UUID org, Operation operation, UUID request) {
        return jdbc.sql("""
                SELECT product_id,result_version,payload_digest FROM stockrescue.product_command
                WHERE org_id=:org AND actor_user_id=:actor AND operation=:operation AND request_id=:request
                """).param("org",org).param("actor",actor).param("operation",operation.name()).param("request",request)
                .query((row,index) -> new Receipt(row.getObject("product_id",UUID.class),row.getLong("result_version"),row.getBytes("payload_digest"))).single();
    }

    public Optional<Receipt> receiptIfPresent(UUID actor,UUID org,Operation operation,UUID request) {
        return jdbc.sql("""
                SELECT product_id,result_version,payload_digest FROM stockrescue.product_command
                WHERE org_id=:org AND actor_user_id=:actor AND operation=:operation AND request_id=:request
                """).param("org",org).param("actor",actor).param("operation",operation.name()).param("request",request)
                .query((row,index) -> new Receipt(row.getObject("product_id",UUID.class),row.getLong("result_version"),row.getBytes("payload_digest"))).optional();
    }

    public List<GtinMatch> gtinMatches(UUID org,String gtin,int offset,int limit) {
        return jdbc.sql("""
                SELECT id,sku_normalized,name,gtin,active FROM stockrescue.product
                WHERE org_id=:org AND gtin=:gtin
                ORDER BY id LIMIT :limit OFFSET :offset
                """).param("org",org).param("gtin",gtin).param("limit",limit).param("offset",offset)
                .query((row,index) -> new GtinMatch(row.getObject("id",UUID.class),row.getString("sku_normalized"),
                        row.getString("name"),row.getString("gtin"),row.getBoolean("active"))).list();
    }

    public boolean hasGtinMatch(UUID org,String gtin) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM stockrescue.product WHERE org_id=:org AND gtin=:gtin)")
                .param("org",org).param("gtin",gtin).query(Boolean.class).single();
    }

    /** Acquire after organization authority, and before product or command writes. */
    public long lockGtinReviewScope(UUID org,String gtin) {
        jdbc.sql("""
                INSERT INTO stockrescue.product_gtin_review_scope(org_id,gtin,version) VALUES (:org,:gtin,0)
                ON CONFLICT (org_id,gtin) DO NOTHING
                """).param("org",org).param("gtin",gtin).update();
        return jdbc.sql("SELECT version FROM stockrescue.product_gtin_review_scope WHERE org_id=:org AND gtin=:gtin FOR UPDATE")
                .param("org",org).param("gtin",gtin).query(Long.class).single();
    }

    public Optional<Long> shareGtinReviewScope(UUID org,String gtin) {
        return jdbc.sql("SELECT version FROM stockrescue.product_gtin_review_scope WHERE org_id=:org AND gtin=:gtin FOR SHARE")
                .param("org",org).param("gtin",gtin).query(Long.class).optional();
    }

    public Optional<Product> create(UUID org, UUID id, ProductData data) {
        return jdbc.sql("""
                INSERT INTO stockrescue.product(id,org_id,sku,name,gtin,base_unit,category,temperature_class)
                VALUES (:id,:org,:sku,:name,:gtin,:unit,:category,:temperature)
                ON CONFLICT (org_id,sku_normalized) DO NOTHING RETURNING *
                """).param("id",id).param("org",org).param("sku",data.sku().value()).param("name",data.name())
                .param("gtin",data.gtin() == null ? null : data.gtin().value(),java.sql.Types.VARCHAR)
                .param("unit",data.baseUnit().name()).param("category",data.category().name()).param("temperature",data.temperatureClass().name())
                .query((row,index) -> product(row)).optional();
    }

    public Optional<Product> detail(UUID org, UUID id, boolean lock) {
        return jdbc.sql("SELECT * FROM stockrescue.product WHERE org_id=:org AND id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("org",org).param("id",id).query((row,index) -> product(row)).optional();
    }

    public Optional<Product> lockForReference(UUID org,UUID id) {
        return jdbc.sql("SELECT * FROM stockrescue.product WHERE org_id=:org AND id=:id FOR SHARE")
                .param("org",org).param("id",id).query((row,index) -> product(row)).optional();
    }

    public List<Product> list(UUID org, String state, String q, int offset, int limit) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.product WHERE org_id=:org
                    AND (:state='ALL' OR (:state='ACTIVE' AND active) OR (:state='ARCHIVED' AND NOT active))
                    AND (:q='' OR position(lower(:q) in lower(name))>0
                        OR position(upper(:q COLLATE "C") in sku_normalized)>0 OR position(:q in gtin)>0)
                ORDER BY sku_normalized,id LIMIT :limit OFFSET :offset
                """).param("org",org).param("state",state).param("q",q).param("offset",offset).param("limit",limit)
                .query((row,index) -> product(row)).list();
    }

    public Optional<Product> archive(UUID org, UUID id, long version) {
        return jdbc.sql("""
                UPDATE stockrescue.product SET active=FALSE,version=version+1
                WHERE org_id=:org AND id=:id AND version=:version AND active RETURNING *
                """).param("org",org).param("id",id).param("version",version).query((row,index) -> product(row)).optional();
    }

    public Optional<Product> confirmSafetyAttributes(UUID org, UUID id, long version, ProductCategory category,
            TemperatureClass temperatureClass) {
        return jdbc.sql("""
                UPDATE stockrescue.product SET category=:category,temperature_class=:temperature,version=version+1
                WHERE org_id=:org AND id=:id AND version=:version AND active RETURNING *
                """).param("category",category.name()).param("temperature",temperatureClass.name())
                .param("org",org).param("id",id).param("version",version)
                .query((row,index) -> product(row)).optional();
    }

    private Product product(ResultSet row) throws SQLException {
        var archived = row.getTimestamp("archived_at");
        return new Product(row.getObject("id",UUID.class), ProductData.parse(row.getString("sku_normalized"),row.getString("name"),
                row.getString("gtin"),row.getString("base_unit"),ProductCategory.valueOf(row.getString("category")),
                TemperatureClass.valueOf(row.getString("temperature_class"))),
                row.getBoolean("active"),row.getLong("version"),row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),archived == null ? null : archived.toInstant());
    }
}
