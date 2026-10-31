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
import com.stockrescue.stores.domain.ConfigurationFailure;
import com.stockrescue.stores.domain.CostParameters;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
            LEFT JOIN LATERAL (
              SELECT max(r.revision_no) AS revision_no,
                     max(r.effective_at) FILTER (WHERE c.id IS NULL) AS effective_at
              FROM stockrescue.lane_revision r
              LEFT JOIN stockrescue.lane_revision_cancellation c
                ON c.org_id=r.org_id AND c.lane_id=r.lane_id AND c.revision_id=r.id
              WHERE r.org_id=l.org_id AND r.lane_id=l.id
            ) last ON TRUE
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
    public boolean hasFutureRevisions(UUID org,UUID lane) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM stockrescue.lane_revision r
                  WHERE r.org_id=:org AND r.lane_id=:lane AND r.effective_at>clock_timestamp()
                    AND NOT EXISTS (SELECT 1 FROM stockrescue.lane_revision_cancellation c
                      WHERE c.org_id=r.org_id AND c.lane_id=r.lane_id AND c.revision_id=r.id))
                """).param("org",org).param("lane",lane).query(Boolean.class).single();
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
    @Transactional(propagation=Propagation.MANDATORY)
    public List<Cancellation> cancelFutureRevisions(UUID org,UUID lane,UUID member,String reason,String correlationId) {
        lock(org,lane);
        var pending=jdbc.sql("""
                SELECT r.id,r.revision_no FROM stockrescue.lane_revision r
                WHERE r.org_id=:org AND r.lane_id=:lane AND r.effective_at>clock_timestamp()
                  AND NOT EXISTS (SELECT 1 FROM stockrescue.lane_revision_cancellation c
                    WHERE c.org_id=r.org_id AND c.lane_id=r.lane_id AND c.revision_id=r.id)
                ORDER BY r.revision_no FOR UPDATE OF r
                """).param("org",org).param("lane",lane)
                .query((row,index)->new PendingRevision(row.getObject("id",UUID.class),row.getLong("revision_no"))).list();
        var cancelled=new java.util.ArrayList<Cancellation>(pending.size());
        try {
            for(var revision:pending) {
                cancelled.add(jdbc.sql("""
                        INSERT INTO stockrescue.lane_revision_cancellation(
                          id,org_id,lane_id,revision_id,cancelled_by_membership_id,reason,correlation_id)
                        VALUES (:id,:org,:lane,:revision,:member,:reason,:correlation)
                        RETURNING id,revision_id,cancelled_by_membership_id,reason,cancelled_at,correlation_id
                        """).param("id",UUID.randomUUID()).param("org",org).param("lane",lane).param("revision",revision.id())
                        .param("member",member).param("reason",reason).param("correlation",correlationId,java.sql.Types.VARCHAR)
                        .query((row,index)->new Cancellation(row.getObject("id",UUID.class),row.getObject("revision_id",UUID.class),
                                revision.number(),row.getObject("cancelled_by_membership_id",UUID.class),row.getString("reason"),
                                row.getTimestamp("cancelled_at").toInstant(),row.getString("correlation_id"))).single());
            }
        } catch (DataIntegrityViolationException failure) {
            if (isNoLongerFuture(failure)) {
                throw new ConfigurationFailure(ConfigurationFailure.Kind.NO_FUTURE_REVISIONS);
            }
            throw failure;
        }
        if(!cancelled.isEmpty()) {
            jdbc.sql("UPDATE stockrescue.transport_lane SET version=version+1,updated_at=GREATEST(clock_timestamp(),updated_at) WHERE org_id=:org AND id=:lane")
                    .param("org",org).param("lane",lane).update();
        }
        return List.copyOf(cancelled);
    }
    private boolean isNoLongerFuture(DataIntegrityViolationException failure) {
        for(var cause=failure.getCause();cause!=null;cause=cause.getCause()) {
            if(cause instanceof java.sql.SQLException sql && "23514".equals(sql.getSQLState())
                    && sql.getMessage().contains("Only a scheduled lane revision can be cancelled before it becomes effective")) {
                return true;
            }
        }
        return false;
    }
    public List<CostRow> currentCosts(UUID org,List<UUID> lanes,Instant asOf) {
        if(lanes.isEmpty()) return List.of();
        return jdbc.sql("""
                WITH history AS (SELECT r.*,lead(effective_at) OVER (PARTITION BY lane_id ORDER BY revision_no) AS valid_until
                  FROM stockrescue.lane_revision r WHERE org_id=:org AND lane_id IN (:lanes)
                    AND NOT EXISTS (SELECT 1 FROM stockrescue.lane_revision_cancellation c
                      WHERE c.org_id=r.org_id AND c.lane_id=r.lane_id AND c.revision_id=r.id))
                SELECT DISTINCT ON (lane_id) * FROM history WHERE effective_at<=:asOf
                  ORDER BY lane_id,effective_at DESC,revision_no DESC
                """).param("org",org).param("lanes",lanes).param("asOf",Timestamp.from(asOf))
                .query((row,index)->new CostRow(row.getObject("lane_id",UUID.class),revision(row,null))).list();
    }
    public List<Revision> history(UUID org,UUID lane,int offset,int limit) {
        return jdbc.sql("""
                WITH active_history AS (
                  SELECT r.id,lead(r.effective_at) OVER (ORDER BY r.effective_at, r.revision_no) AS valid_until
                  FROM stockrescue.lane_revision r
                  WHERE r.org_id=:org AND r.lane_id=:lane
                    AND NOT EXISTS (SELECT 1 FROM stockrescue.lane_revision_cancellation c
                      WHERE c.org_id=r.org_id AND c.lane_id=r.lane_id AND c.revision_id=r.id)
                )
                SELECT r.*,CASE WHEN c.id IS NULL THEN a.valid_until ELSE NULL END AS valid_until,
                  c.id AS cancellation_id,c.cancelled_by_membership_id AS cancellation_actor_membership_id,
                  c.reason AS cancellation_reason,c.cancelled_at AS cancellation_at,c.correlation_id AS cancellation_correlation_id
                FROM stockrescue.lane_revision r
                LEFT JOIN active_history a ON a.id=r.id
                LEFT JOIN stockrescue.lane_revision_cancellation c
                  ON c.org_id=r.org_id AND c.lane_id=r.lane_id AND c.revision_id=r.id
                WHERE r.org_id=:org AND r.lane_id=:lane ORDER BY r.revision_no DESC LIMIT :limit OFFSET :offset
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
        return revision(row,cancellation(row));
    }
    private Revision revision(ResultSet row,Cancellation cancellation) throws SQLException {
        return new Revision(row.getObject("id",UUID.class),row.getLong("revision_no"),row.getBigDecimal("cost_amount").setScale(4,RoundingMode.UNNECESSARY).toPlainString(),
                row.getString("currency_code"),row.getInt("duration_minutes"),instant(row,"effective_at"),instant(row,"valid_until"),instant(row,"recorded_at"),
                row.getObject("recorded_by_membership_id",UUID.class),row.getObject("origin_zone_id",UUID.class),row.getObject("destination_zone_id",UUID.class),temperature(row.getString("temperature_class")),cancellation);
    }
    private Cancellation cancellation(ResultSet row) throws SQLException {
        var id=row.getObject("cancellation_id",UUID.class);
        return id==null?null:new Cancellation(id,row.getObject("id",UUID.class),row.getLong("revision_no"),
                row.getObject("cancellation_actor_membership_id",UUID.class),row.getString("cancellation_reason"),
                instant(row,"cancellation_at"),row.getString("cancellation_correlation_id"));
    }
    private TemperatureClass temperature(String value) { return value==null?null:TemperatureClass.valueOf(value); }
    private Instant instant(ResultSet row,String field) throws SQLException { var value=row.getTimestamp(field);return value==null?null:value.toInstant(); }
    private record PendingRevision(UUID id,long number) { }
    public record CostRow(UUID laneId,Revision revision) { }
}
