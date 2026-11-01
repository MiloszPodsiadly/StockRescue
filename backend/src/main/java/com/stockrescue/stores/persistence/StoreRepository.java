package com.stockrescue.stores.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.stockrescue.stores.application.Store;
import com.stockrescue.stores.domain.StoreData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StoreRepository {
    private final JdbcClient jdbc;
    public StoreRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<CreationRequest> creation(UUID actor, UUID org, UUID request) {
        return jdbc.sql("SELECT store_id,payload_digest FROM stockrescue.store_creation_request WHERE org_id=:org AND actor_user_id=:actor AND request_id=:request")
                .param("org", org).param("actor", actor).param("request", request)
                .query((row, index) -> new CreationRequest(row.getObject("store_id", UUID.class), row.getBytes("payload_digest"))).optional();
    }
    public void remember(UUID actor, UUID org, UUID request, UUID store, byte[] digest) {
        jdbc.sql("INSERT INTO stockrescue.store_creation_request(org_id,actor_user_id,request_id,store_id,payload_digest) VALUES (:org,:actor,:request,:store,:digest)")
                .param("org", org).param("actor", actor).param("request", request).param("store", store).param("digest", digest).update();
    }
    public Optional<Store> create(UUID org, StoreData data) {
        return fields(jdbc.sql("""
                INSERT INTO stockrescue.store(id,org_id,code,name,address_line1,city,postal_code,country_code,zone_id)
                VALUES (:id,:org,:code,:name,:address,:city,:postal,:country,:zone)
                ON CONFLICT (org_id,code_normalized) DO NOTHING RETURNING *
                """).param("id", UUID.randomUUID()).param("org", org), data).query((row, index) -> store(row)).optional();
    }
    public Optional<Store> detail(UUID org, UUID id) {
        return jdbc.sql("SELECT * FROM stockrescue.store WHERE org_id=:org AND id=:id").param("org", org).param("id", id)
                .query((row, index) -> store(row)).optional();
    }
    public List<Store> list(UUID org, UUID membership, boolean all, String state, String search, int offset, int limit) {
        return jdbc.sql("""
                SELECT s.* FROM stockrescue.store s WHERE s.org_id=:org
                  AND (:all OR (s.active AND EXISTS (SELECT 1 FROM stockrescue.store_grant g
                    WHERE g.org_id=s.org_id AND g.store_id=s.id AND g.membership_id=:member AND g.state='ACTIVE')))
                  AND (:state='ALL' OR (:state='ACTIVE' AND s.active) OR (:state='ARCHIVED' AND NOT s.active))
                  AND (:search='' OR position(lower(:search) in lower(s.name))>0 OR position(upper(:search) in s.code_normalized)>0)
                ORDER BY s.code_normalized,s.id LIMIT :limit OFFSET :offset
                """).param("org", org).param("member", membership).param("all", all).param("state", state).param("search", search)
                .param("offset", offset).param("limit", limit).query((row, index) -> store(row)).list();
    }
    public Optional<Store> update(UUID org, UUID id, long version, StoreData data) {
        return fields(jdbc.sql("""
                UPDATE stockrescue.store SET name=:name,address_line1=:address,city=:city,postal_code=:postal,country_code=:country,zone_id=:zone,
                  version=version+1,updated_at=GREATEST(clock_timestamp(),updated_at)
                WHERE org_id=:org AND id=:id AND version=:version AND active RETURNING *
                """).param("org", org).param("id", id).param("version", version), data).query((row, index) -> store(row)).optional();
    }
    public Optional<Store> archive(UUID org, UUID id, long version) {
        return jdbc.sql("""
                UPDATE stockrescue.store SET active=FALSE,version=version+1,updated_at=GREATEST(clock_timestamp(),updated_at)
                WHERE org_id=:org AND id=:id AND version=:version AND active RETURNING *
                """).param("org", org).param("id", id).param("version", version).query((row, index) -> store(row)).optional();
    }
    private JdbcClient.StatementSpec fields(JdbcClient.StatementSpec statement, StoreData data) {
        return statement.param("code", data.code()).param("name", data.name()).param("address", data.addressLine1(), java.sql.Types.VARCHAR)
                .param("city", data.city(), java.sql.Types.VARCHAR).param("postal", data.postalCode(), java.sql.Types.VARCHAR)
                .param("country", data.countryCode(), java.sql.Types.VARCHAR).param("zone", data.zoneId());
    }
    private Store store(ResultSet row) throws SQLException {
        return new Store(row.getObject("id", UUID.class), new StoreData(row.getString("code"), row.getString("name"), row.getString("address_line1"),
                row.getString("city"), row.getString("postal_code"), row.getString("country_code"), row.getString("zone_id")),
                row.getBoolean("active"), row.getLong("version"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
    public record CreationRequest(UUID storeId, byte[] digest) { }
}
