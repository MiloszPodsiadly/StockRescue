package com.stockrescue.catalog.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.stockrescue.catalog.application.ConsignmentEvidence;
import com.stockrescue.catalog.application.SourceEvidenceData;
import com.stockrescue.catalog.domain.EvidenceProvenance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SourceEvidenceRepository {
    public record Receipt(UUID evidenceId,byte[] digest) { }
    private static final String VISIBLE="""
            AND (:all OR EXISTS (
                SELECT 1 FROM stockrescue.store_grant g JOIN stockrescue.store s ON s.org_id=g.org_id AND s.id=g.store_id AND s.active
                WHERE g.org_id=e.org_id AND g.store_id=e.store_id AND g.membership_id=:member AND g.state='ACTIVE'))
            """;
    private final JdbcClient jdbc;
    public SourceEvidenceRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public boolean reserve(UUID actor,UUID org,UUID request,UUID evidence,byte[] digest) {
        return jdbc.sql("""
                INSERT INTO stockrescue.consignment_registration(org_id,actor_user_id,request_id,evidence_id,payload_digest)
                VALUES (:org,:actor,:request,:evidence,:digest) ON CONFLICT (org_id,actor_user_id,request_id) DO NOTHING
                """).param("org",org).param("actor",actor).param("request",request).param("evidence",evidence).param("digest",digest).update()==1;
    }
    public Receipt receipt(UUID actor,UUID org,UUID request) {
        return jdbc.sql("SELECT evidence_id,payload_digest FROM stockrescue.consignment_registration WHERE org_id=:org AND actor_user_id=:actor AND request_id=:request")
                .param("org",org).param("actor",actor).param("request",request)
                .query((row,index) -> new Receipt(row.getObject("evidence_id",UUID.class),row.getBytes("payload_digest"))).single();
    }
    public ConsignmentEvidence create(UUID org,UUID lot,UUID id,SourceEvidenceData data) {
        return jdbc.sql("""
                INSERT INTO stockrescue.consignment_evidence(id,org_id,lot_id,store_id,supplier_name,supplier_reference,provenance_source,provenance_reference)
                VALUES (:id,:org,:lot,:store,:supplier,:supplierReference,:source,:reference) RETURNING *
                """).param("id",id).param("org",org).param("lot",lot).param("store",data.storeId(),java.sql.Types.OTHER)
                .param("supplier",data.supplierName(),java.sql.Types.VARCHAR).param("supplierReference",data.supplierReference(),java.sql.Types.VARCHAR)
                .param("source",data.provenance().source()).param("reference",data.provenance().reference(),java.sql.Types.VARCHAR)
                .query((row,index) -> evidence(row)).single();
    }
    public Optional<ConsignmentEvidence> detail(UUID org,UUID lot,UUID id,UUID membership,boolean all) {
        return jdbc.sql("SELECT e.* FROM stockrescue.consignment_evidence e WHERE e.org_id=:org AND e.lot_id=:lot AND e.id=:id "+VISIBLE)
                .param("org",org).param("lot",lot).param("id",id).param("member",membership).param("all",all)
                .query((row,index) -> evidence(row)).optional();
    }
    public List<ConsignmentEvidence> trace(UUID org,UUID lot,UUID membership,boolean all,int offset,int limit) {
        return jdbc.sql("SELECT e.* FROM stockrescue.consignment_evidence e WHERE e.org_id=:org AND e.lot_id=:lot "+VISIBLE+" ORDER BY e.created_at,e.id LIMIT :limit OFFSET :offset")
                .param("org",org).param("lot",lot).param("member",membership).param("all",all).param("offset",offset).param("limit",limit)
                .query((row,index) -> evidence(row)).list();
    }
    private ConsignmentEvidence evidence(ResultSet row) throws SQLException {
        return new ConsignmentEvidence(row.getObject("id",UUID.class),row.getObject("lot_id",UUID.class),
                new SourceEvidenceData(row.getObject("store_id",UUID.class),row.getString("supplier_name"),row.getString("supplier_reference"),
                        new EvidenceProvenance(row.getString("provenance_source"),row.getString("provenance_reference"))),
                row.getBoolean("active"),row.getLong("version"),instant(row,"created_at"),instant(row,"updated_at"),instant(row,"archived_at"));
    }
    private Instant instant(ResultSet row,String field) throws SQLException { var value=row.getTimestamp(field); return value==null?null:value.toInstant(); }
}
