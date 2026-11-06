package com.stockrescue.stores.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.stockrescue.stores.application.StorageLocation;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;
import com.stockrescue.stores.domain.LocationData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StorageLocationRepository {
    public enum Operation { CREATE, RENAME, ARCHIVE }
    public record Receipt(UUID storeId,UUID locationId,byte[] digest) { }
    private static final String SELECT="""
            SELECT l.*,s.active AS store_active,z.active AS zone_active,z.temperature_class
            FROM stockrescue.storage_location l JOIN stockrescue.store s ON s.org_id=l.org_id AND s.id=l.store_id
            JOIN stockrescue.storage_zone z ON z.org_id=l.org_id AND z.store_id=l.store_id AND z.id=l.zone_id
            """;
    private final JdbcClient jdbc;
    public StorageLocationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public boolean reserve(UUID actor,UUID org,UUID store,Operation operation,UUID request,UUID location,long version,byte[] digest) {
        return jdbc.sql("""
                INSERT INTO stockrescue.storage_location_command(org_id,store_id,actor_user_id,operation,request_id,location_id,result_version,payload_digest)
                VALUES (:org,:store,:actor,:operation,:request,:location,:version,:digest)
                ON CONFLICT (org_id,actor_user_id,operation,request_id) DO NOTHING
                """).param("org",org).param("store",store).param("actor",actor).param("operation",operation.name()).param("request",request)
                .param("location",location).param("version",version).param("digest",digest).update()==1;
    }
    public Receipt receipt(UUID actor,UUID org,Operation operation,UUID request) {
        return jdbc.sql("SELECT store_id,location_id,payload_digest FROM stockrescue.storage_location_command WHERE org_id=:org AND actor_user_id=:actor AND operation=:operation AND request_id=:request")
                .param("org",org).param("actor",actor).param("operation",operation.name()).param("request",request)
                .query((row,index) -> new Receipt(row.getObject("store_id",UUID.class),row.getObject("location_id",UUID.class),row.getBytes("payload_digest"))).single();
    }
    public boolean create(UUID org,UUID store,UUID id,LocationData data) {
        return jdbc.sql("""
                INSERT INTO stockrescue.storage_location(id,org_id,store_id,zone_id,code,name) VALUES (:id,:org,:store,:zone,:code,:name)
                ON CONFLICT (org_id,store_id,code_normalized) DO NOTHING
                """).param("id",id).param("org",org).param("store",store).param("zone",data.zoneId()).param("code",data.code()).param("name",data.name()).update()==1;
    }
    public Optional<StorageLocation> detail(UUID org,UUID store,UUID id,boolean lock) {
        return jdbc.sql(SELECT+" WHERE l.org_id=:org AND l.store_id=:store AND l.id=:id"+(lock?" FOR UPDATE OF l":""))
                .param("org",org).param("store",store).param("id",id).query((row,index) -> location(row)).optional();
    }
    public List<StorageLocation> list(UUID org,UUID store,boolean includeArchived,String q,int offset,int limit) {
        return jdbc.sql(SELECT+"""
                 WHERE l.org_id=:org AND l.store_id=:store AND (:archived OR l.active)
                    AND (:q='' OR position(upper(:q COLLATE "C") in l.code_normalized)>0 OR position(lower(:q) in lower(l.name))>0)
                 ORDER BY l.code_normalized,l.id LIMIT :limit OFFSET :offset
                """).param("org",org).param("store",store).param("archived",includeArchived).param("q",q).param("offset",offset).param("limit",limit)
                .query((row,index) -> location(row)).list();
    }
    public boolean rename(UUID org,UUID store,UUID id,String name,long version) {
        return jdbc.sql("UPDATE stockrescue.storage_location SET name=:name,version=version+1 WHERE org_id=:org AND store_id=:store AND id=:id AND version=:version AND active")
                .param("org",org).param("store",store).param("id",id).param("name",name).param("version",version).update()==1;
    }
    public boolean archive(UUID org,UUID store,UUID id,long version) {
        return jdbc.sql("UPDATE stockrescue.storage_location SET active=FALSE,version=version+1 WHERE org_id=:org AND store_id=:store AND id=:id AND version=:version AND active")
                .param("org",org).param("store",store).param("id",id).param("version",version).update()==1;
    }
    private StorageLocation location(ResultSet row) throws SQLException {
        var archived=row.getTimestamp("archived_at");
        return new StorageLocation(row.getObject("id",UUID.class),row.getObject("store_id",UUID.class),new LocationData(row.getString("code_normalized"),row.getString("name"),row.getObject("zone_id",UUID.class)),
                TemperatureClass.valueOf(row.getString("temperature_class")),row.getBoolean("active"),row.getBoolean("store_active"),row.getBoolean("zone_active"),row.getLong("version"),
                row.getTimestamp("created_at").toInstant(),row.getTimestamp("updated_at").toInstant(),archived==null?null:archived.toInstant());
    }
}
