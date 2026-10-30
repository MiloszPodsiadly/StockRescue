package com.stockrescue.stores.persistence;

import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.stockrescue.stores.application.OperationalConfiguration.*;
import com.stockrescue.stores.domain.CostParameters;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LaneRepository {
    private static final String SELECT = """
            SELECT l.*,a.name AS origin_name,a.active AS origin_active,b.name AS destination_name,b.active AS destination_active,
              za.active AS origin_zone_active,zb.active AS destination_zone_active,za.temperature_class AS origin_temperature,
              zb.temperature_class AS destination_temperature,coalesce(last.revision_no,0) AS latest_revision,last.effective_at AS latest_effective
            FROM stockrescue.transport_lane l
            JOIN stockrescue.store a ON a.org_id=l.org_id AND a.id=l.origin_store_id
            JOIN stockrescue.store b ON b.org_id=l.org_id AND b.id=l.destination_store_id
            LEFT JOIN stockrescue.storage_zone za ON za.org_id=l.org_id AND za.store_id=l.origin_store_id AND za.id=l.origin_zone_id
            LEFT JOIN stockrescue.storage_zone zb ON zb.org_id=l.org_id AND zb.store_id=l.destination_store_id AND zb.id=l.destination_zone_id
            LEFT JOIN LATERAL (SELECT revision_no,effective_at FROM stockrescue.lane_revision r
              WHERE r.org_id=l.org_id AND r.lane_id=l.id ORDER BY revision_no DESC LIMIT 1) last ON TRUE
            """;
    private final JdbcClient jdbc;
    public LaneRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public List<Lane> list(UUID org,int offset,int limit) {
        return jdbc.sql(SELECT+" WHERE l.org_id=:org ORDER BY l.origin_store_id,l.destination_store_id,l.id LIMIT :limit OFFSET :offset")
                .param("org",org).param("offset",offset).param("limit",limit).query((row,index)->lane(row)).list();
    }
    public Optional<Lane> detail(UUID org,UUID id) {
        return jdbc.sql(SELECT+" WHERE l.org_id=:org AND l.id=:id").param("org",org).param("id",id).query((row,index)->lane(row)).optional();
    }
    public Optional<Lane> route(UUID org,UUID origin,UUID destination) {
        return jdbc.sql(SELECT+" WHERE l.org_id=:org AND l.origin_store_id=:origin AND l.destination_store_id=:destination")
                .param("org",org).param("origin",origin).param("destination",destination).query((row,index)->lane(row)).optional();
    }
    public void lock(UUID org,UUID id) {
        jdbc.sql("SELECT id FROM stockrescue.transport_lane WHERE org_id=:org AND id=:id FOR UPDATE").param("org",org).param("id",id).query(UUID.class).optional()
                .orElseThrow(com.stockrescue.access.application.OrganizationUnavailableException::new);
    }
    public Optional<UUID> create(UUID org,UUID origin,UUID destination,UUID originZone,UUID destinationZone) {
        return jdbc.sql("""
                INSERT INTO stockrescue.transport_lane(id,org_id,origin_store_id,destination_store_id,origin_zone_id,destination_zone_id)
                VALUES (:id,:org,:origin,:destination,:originZone,:destinationZone) ON CONFLICT DO NOTHING RETURNING id
                """).param("id",UUID.randomUUID()).param("org",org).param("origin",origin).param("destination",destination)
                .param("originZone",originZone).param("destinationZone",destinationZone).query(UUID.class).optional();
    }
    public void update(UUID org,UUID id,UUID originZone,UUID destinationZone,boolean active) {
        jdbc.sql("UPDATE stockrescue.transport_lane SET origin_zone_id=:originZone,destination_zone_id=:destinationZone,active=:active,version=version+1,updated_at=GREATEST(clock_timestamp(),updated_at) WHERE org_id=:org AND id=:id")
                .param("org",org).param("id",id).param("originZone",originZone,java.sql.Types.OTHER).param("destinationZone",destinationZone,java.sql.Types.OTHER).param("active",active).update();
    }
    public void revise(UUID org,Lane lane,UUID member,CostParameters parameters,Instant effective) {
        jdbc.sql("""
                INSERT INTO stockrescue.lane_revision(id,org_id,lane_id,revision_no,cost_amount,currency_code,duration_minutes,effective_at,
                  recorded_by_membership_id,origin_zone_id,destination_zone_id,temperature_class)
                VALUES (:id,:org,:lane,:revision,:amount,:currency,:minutes,:effective,:member,:originZone,:destinationZone,:temperature)
                """).param("id",UUID.randomUUID()).param("org",org).param("lane",lane.id()).param("revision",lane.latestRevision()+1)
                .param("amount",parameters.amount()).param("currency",parameters.currencyCode()).param("minutes",parameters.durationMinutes())
                .param("effective",Timestamp.from(effective)).param("member",member).param("originZone",lane.originZoneId()).param("destinationZone",lane.destinationZoneId())
                .param("temperature",lane.originTemperature().name()).update();
        jdbc.sql("UPDATE stockrescue.transport_lane SET version=version+1,updated_at=GREATEST(clock_timestamp(),updated_at) WHERE org_id=:org AND id=:id")
                .param("org",org).param("id",lane.id()).update();
    }
    public List<CostRow> currentCosts(UUID org,List<UUID> lanes,Instant asOf) {
        if(lanes.isEmpty()) return List.of();
        return jdbc.sql("""
                WITH history AS (SELECT r.*,lead(effective_at) OVER (PARTITION BY lane_id ORDER BY revision_no) AS valid_until
                  FROM stockrescue.lane_revision r WHERE org_id=:org AND lane_id IN (:lanes))
                SELECT DISTINCT ON (lane_id) * FROM history WHERE effective_at<=:asOf ORDER BY lane_id,revision_no DESC
                """).param("org",org).param("lanes",lanes).param("asOf",Timestamp.from(asOf))
                .query((row,index)->new CostRow(row.getObject("lane_id",UUID.class),revision(row))).list();
    }
    public List<Revision> history(UUID org,UUID lane,int offset,int limit) {
        return jdbc.sql("""
                SELECT r.*,lead(effective_at) OVER (ORDER BY revision_no) AS valid_until FROM stockrescue.lane_revision r
                WHERE org_id=:org AND lane_id=:lane ORDER BY revision_no DESC LIMIT :limit OFFSET :offset
                """).param("org",org).param("lane",lane).param("offset",offset).param("limit",limit).query((row,index)->revision(row)).list();
    }
    private Lane lane(ResultSet row) throws SQLException {
        return new Lane(row.getObject("id",UUID.class),row.getObject("origin_store_id",UUID.class),row.getString("origin_name"),row.getBoolean("origin_active"),
                row.getObject("destination_store_id",UUID.class),row.getString("destination_name"),row.getBoolean("destination_active"),
                row.getObject("origin_zone_id",UUID.class),row.getObject("destination_zone_id",UUID.class),row.getBoolean("origin_zone_active"),row.getBoolean("destination_zone_active"),
                temperature(row.getString("origin_temperature")),temperature(row.getString("destination_temperature")),row.getBoolean("active"),row.getLong("version"),row.getLong("latest_revision"),
                instant(row,"latest_effective"),instant(row,"created_at"),instant(row,"updated_at"));
    }
    private Revision revision(ResultSet row) throws SQLException {
        return new Revision(row.getObject("id",UUID.class),row.getLong("revision_no"),row.getBigDecimal("cost_amount").setScale(4,RoundingMode.UNNECESSARY).toPlainString(),
                row.getString("currency_code"),row.getInt("duration_minutes"),instant(row,"effective_at"),instant(row,"valid_until"),instant(row,"recorded_at"),
                row.getObject("recorded_by_membership_id",UUID.class),row.getObject("origin_zone_id",UUID.class),row.getObject("destination_zone_id",UUID.class),temperature(row.getString("temperature_class")));
    }
    private TemperatureClass temperature(String value) { return value==null?null:TemperatureClass.valueOf(value); }
    private Instant instant(ResultSet row,String field) throws SQLException { var value=row.getTimestamp(field);return value==null?null:value.toInstant(); }
    public record CostRow(UUID laneId,Revision revision) { }
}
