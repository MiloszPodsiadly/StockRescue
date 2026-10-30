package com.stockrescue.stores.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.stockrescue.stores.application.OperationalConfiguration.Zone;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ZoneRepository {
    private final JdbcClient jdbc;
    public ZoneRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public List<Zone> list(UUID org, UUID store, boolean includeInactive, int offset, int limit) {
        return jdbc.sql("SELECT * FROM stockrescue.storage_zone WHERE org_id=:org AND store_id=:store AND (:inactive OR active) ORDER BY name_normalized,id LIMIT :limit OFFSET :offset")
                .param("org", org).param("store", store).param("inactive", includeInactive).param("offset", offset).param("limit", limit)
                .query((row,index) -> zone(row)).list();
    }
    public Optional<Zone> detail(UUID org, UUID store, UUID zone, boolean lock) {
        return jdbc.sql("SELECT * FROM stockrescue.storage_zone WHERE org_id=:org AND store_id=:store AND id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("org", org).param("store", store).param("id", zone).query((row,index) -> zone(row)).optional();
    }
    public Optional<Zone> create(UUID org, UUID store, String name, TemperatureClass temperature) {
        return jdbc.sql("""
                INSERT INTO stockrescue.storage_zone(id,org_id,store_id,name,temperature_class) VALUES (:id,:org,:store,:name,:temperature)
                ON CONFLICT DO NOTHING RETURNING *
                """).param("id",UUID.randomUUID()).param("org",org).param("store",store).param("name",name).param("temperature",temperature.name())
                .query((row,index) -> zone(row)).optional();
    }
    public Optional<Zone> rename(UUID org, UUID store, UUID id, String name, long version) {
        if (jdbc.sql("SELECT id FROM stockrescue.storage_zone WHERE org_id=:org AND store_id=:store AND name_normalized=lower(:name COLLATE \"C\") AND id<>:id")
                .param("org",org).param("store",store).param("name",name).param("id",id).query(UUID.class).optional().isPresent()) return Optional.empty();
        return jdbc.sql("UPDATE stockrescue.storage_zone SET name=:name,version=version+1,updated_at=GREATEST(clock_timestamp(),updated_at) WHERE org_id=:org AND store_id=:store AND id=:id AND version=:version AND active RETURNING *")
                .param("org",org).param("store",store).param("id",id).param("name",name).param("version",version).query((row,index) -> zone(row)).optional();
    }
    public Optional<Zone> archive(UUID org, UUID store, UUID id, long version) {
        return jdbc.sql("UPDATE stockrescue.storage_zone SET active=FALSE,version=version+1,updated_at=GREATEST(clock_timestamp(),updated_at) WHERE org_id=:org AND store_id=:store AND id=:id AND version=:version AND active RETURNING *")
                .param("org",org).param("store",store).param("id",id).param("version",version).query((row,index) -> zone(row)).optional();
    }
    private Zone zone(ResultSet row) throws SQLException {
        return new Zone(row.getObject("id",UUID.class),row.getString("name"),TemperatureClass.valueOf(row.getString("temperature_class")),row.getBoolean("active"),row.getLong("version"),
                row.getTimestamp("created_at").toInstant(),row.getTimestamp("updated_at").toInstant());
    }
}
