package com.stockrescue.catalog.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.stockrescue.catalog.application.LotData;
import com.stockrescue.catalog.application.ManufacturerLot;
import com.stockrescue.catalog.domain.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LotRepository {
    public record Receipt(UUID lotId,byte[] digest,UUID actorUserId,UUID storeId) { }
    public record ArchiveReceipt(UUID lotId,long resultVersion,byte[] digest) { }
    private final JdbcClient jdbc;
    public LotRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    public boolean reserve(UUID actor,UUID org,UUID request,UUID store,UUID lot,byte[] digest) {
        return jdbc.sql("""
                INSERT INTO stockrescue.lot_registration(org_id,actor_user_id,request_id,store_id,lot_id,payload_digest)
                VALUES (:org,:actor,:request,:store,:lot,:digest) ON CONFLICT (org_id,request_id) DO NOTHING
                """).param("org",org).param("actor",actor).param("request",request).param("store",store,java.sql.Types.OTHER)
                .param("lot",lot).param("digest",digest).update()==1;
    }
    public Receipt receipt(UUID org,UUID request) {
        return jdbc.sql("SELECT lot_id,payload_digest,actor_user_id,store_id FROM stockrescue.lot_registration WHERE org_id=:org AND request_id=:request")
                .param("org",org).param("request",request)
                .query((row,index) -> new Receipt(row.getObject("lot_id",UUID.class),row.getBytes("payload_digest"),
                        row.getObject("actor_user_id",UUID.class),row.getObject("store_id",UUID.class))).single();
    }
    public boolean reserveArchive(UUID actor,UUID org,UUID request,UUID lot,long resultVersion,byte[] digest) {
        return jdbc.sql("""
                INSERT INTO stockrescue.lot_archive_command(org_id,actor_user_id,request_id,lot_id,result_version,payload_digest)
                VALUES (:org,:actor,:request,:lot,:version,:digest) ON CONFLICT (org_id,actor_user_id,request_id) DO NOTHING
                """).param("org",org).param("actor",actor).param("request",request).param("lot",lot)
                .param("version",resultVersion).param("digest",digest).update()==1;
    }
    public Optional<ArchiveReceipt> archiveReceipt(UUID actor,UUID org,UUID request) {
        return jdbc.sql("""
                SELECT lot_id,result_version,payload_digest FROM stockrescue.lot_archive_command
                WHERE org_id=:org AND actor_user_id=:actor AND request_id=:request
                """).param("org",org).param("actor",actor).param("request",request)
                .query((row,index) -> new ArchiveReceipt(row.getObject("lot_id",UUID.class),row.getLong("result_version"),row.getBytes("payload_digest"))).optional();
    }
    public Optional<ManufacturerLot> archive(UUID org,UUID id,long expectedVersion) {
        return jdbc.sql("""
                UPDATE stockrescue.manufacturer_lot SET active=FALSE,version=version+1
                WHERE org_id=:org AND id=:id AND version=:version AND active RETURNING *
                """).param("org",org).param("id",id).param("version",expectedVersion)
                .query((row,index) -> lot(row)).optional();
    }
    public Optional<ManufacturerLot> create(UUID org,UUID id,UUID membership,LotData data) {
        var marking=data.dateMarking();
        var manufacturer=data.manufacturerProvenance();
        var provenance=marking.provenance().orElse(null);
        return jdbc.sql("""
                INSERT INTO stockrescue.manufacturer_lot(id,org_id,product_id,lot_code,identity_state,manufacturer_identity_key,
                    manufacturer_source,manufacturer_reference,confirmed_by_membership_id,confirmed_at,labeled_value,
                    date_marking_type,date_precision,marking_source,marking_reference)
                VALUES (:id,:org,:product,:code,:state,:manufacturer,:source,:reference,
                    CASE WHEN :confirmed THEN :membership ELSE NULL END,CASE WHEN :confirmed THEN clock_timestamp() ELSE NULL END,
                    :label,:type,:precision,:markingSource,:markingReference)
                ON CONFLICT (org_id,product_id,manufacturer_identity_key,lot_code) WHERE identity_state='CONFIRMED' DO NOTHING RETURNING *
                """).param("id",id).param("org",org).param("product",data.productId()).param("code",data.lotCode())
                .param("state",data.identityState().name()).param("manufacturer",data.manufacturerIdentityKey(),java.sql.Types.VARCHAR)
                .param("source",manufacturer.source()).param("reference",manufacturer.reference(),java.sql.Types.VARCHAR)
                .param("confirmed",data.identityState()==LotIdentityState.CONFIRMED).param("membership",membership)
                .param("label",marking.labeledValue(),java.sql.Types.VARCHAR).param("type",marking.type().name()).param("precision",marking.precision().name())
                .param("markingSource",provenance==null?null:provenance.source(),java.sql.Types.VARCHAR)
                .param("markingReference",provenance==null?null:provenance.reference(),java.sql.Types.VARCHAR)
                .query((row,index) -> lot(row)).optional();
    }
    public Optional<ManufacturerLot> detail(UUID org,UUID id) { return detail(org,id,false); }
    public Optional<ManufacturerLot> lockForSourceRegistration(UUID org,UUID id) { return detail(org,id,true); }
    public Optional<ManufacturerLot> lockForCorrection(UUID org,UUID id) {
        return jdbc.sql("SELECT * FROM stockrescue.manufacturer_lot WHERE org_id=:org AND id=:id FOR UPDATE")
                .param("org",org).param("id",id).query((row,index) -> lot(row)).optional();
    }
    private Optional<ManufacturerLot> detail(UUID org,UUID id,boolean lock) {
        return jdbc.sql("SELECT * FROM stockrescue.manufacturer_lot WHERE org_id=:org AND id=:id"+(lock?" FOR SHARE":""))
                .param("org",org).param("id",id).query((row,index) -> lot(row)).optional();
    }
    public List<ManufacturerLot> list(UUID org,UUID product,boolean includeArchived,int offset,int limit) {
        return jdbc.sql("""
                SELECT * FROM stockrescue.manufacturer_lot WHERE org_id=:org AND (CAST(:product AS uuid) IS NULL OR product_id=:product)
                    AND (:archived OR active) ORDER BY lot_code,id LIMIT :limit OFFSET :offset
                """).param("org",org).param("product",product,java.sql.Types.OTHER).param("archived",includeArchived)
                .param("offset",offset).param("limit",limit).query((row,index) -> lot(row)).list();
    }
    private ManufacturerLot lot(ResultSet row) throws SQLException { return lot(row,""); }
    static String projection(String alias,String prefix) {
        return Stream.of("id","product_id","lot_code","identity_state","manufacturer_identity_key","manufacturer_source","manufacturer_reference",
                "confirmed_by_membership_id","confirmed_at","labeled_value","date_marking_type","date_precision","marking_source","marking_reference",
                "normalized_cutoff","normalization_policy_version_id","normalization_policy_version","normalization_version","recall_state","damage_state",
                "status_source","status_reference","active","version","created_at","updated_at","archived_at")
                .map(column -> alias+"."+column+" AS "+prefix+column).collect(Collectors.joining(","));
    }
    static ManufacturerLot lot(ResultSet row,String prefix) throws SQLException {
        var cutoff=row.getDate(prefix+"normalized_cutoff");
        var date=new DateMarking(row.getString(prefix+"labeled_value"),DateMarkingType.valueOf(row.getString(prefix+"date_marking_type")),
                DatePrecision.valueOf(row.getString(prefix+"date_precision")),Optional.ofNullable(provenance(row,prefix+"marking_source",prefix+"marking_reference")),
                cutoff==null?Optional.empty():Optional.of(new PolicyDerivedCutoff(cutoff.toLocalDate(),row.getObject(prefix+"normalization_policy_version_id",UUID.class),
                        row.getInt(prefix+"normalization_policy_version"),row.getString(prefix+"normalization_version"))));
        var state=LotIdentityState.valueOf(row.getString(prefix+"identity_state"));
        var data=new LotData(row.getObject(prefix+"product_id",UUID.class),row.getString(prefix+"lot_code"),state,row.getString(prefix+"manufacturer_identity_key"),
                provenance(row,prefix+"manufacturer_source",prefix+"manufacturer_reference"),state==LotIdentityState.CONFIRMED,date);
        return new ManufacturerLot(row.getObject(prefix+"id",UUID.class),data,row.getObject(prefix+"confirmed_by_membership_id",UUID.class),instant(row,prefix+"confirmed_at"),
                ManufacturerLot.RecallState.valueOf(row.getString(prefix+"recall_state")),ManufacturerLot.DamageState.valueOf(row.getString(prefix+"damage_state")),
                provenance(row,prefix+"status_source",prefix+"status_reference"),row.getBoolean(prefix+"active"),row.getLong(prefix+"version"),
                instant(row,prefix+"created_at"),instant(row,prefix+"updated_at"),instant(row,prefix+"archived_at"));
    }
    private static EvidenceProvenance provenance(ResultSet row,String source,String reference) throws SQLException {
        return row.getString(source)==null?null:new EvidenceProvenance(row.getString(source),row.getString(reference));
    }
    private static Instant instant(ResultSet row,String field) throws SQLException { var value=row.getTimestamp(field); return value==null?null:value.toInstant(); }
}
