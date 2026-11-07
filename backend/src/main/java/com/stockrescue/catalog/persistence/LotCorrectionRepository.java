package com.stockrescue.catalog.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.stockrescue.catalog.application.LotCorrection;
import com.stockrescue.catalog.application.LotData;
import com.stockrescue.catalog.application.LotMetadataCorrection;
import com.stockrescue.catalog.application.LotSafetyEvidence;
import com.stockrescue.catalog.domain.LotIdentityState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LotCorrectionRepository {
    public record Receipt(LotCorrection correction,byte[] digest) { }
    private static final String SNAPSHOTS="""
            CROSS JOIN LATERAL jsonb_populate_record(NULL::stockrescue.manufacturer_lot,c.before_evidence) b
            CROSS JOIN LATERAL jsonb_populate_record(NULL::stockrescue.manufacturer_lot,c.after_evidence) a
            """;
    private static final String SELECT="SELECT c.*,"+LotRepository.projection("b","before_")+","+LotRepository.projection("a","after_");
    private final JdbcClient jdbc;
    public LotCorrectionRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    public Optional<Receipt> receipt(UUID actor,UUID org,UUID request) {
        return jdbc.sql(SELECT+" FROM stockrescue.lot_correction c "+SNAPSHOTS+" WHERE c.org_id=:org AND c.actor_user_id=:actor AND c.request_id=:request")
                .param("org",org).param("actor",actor).param("request",request)
                .query((row,index) -> new Receipt(correction(row),row.getBytes("payload_digest"))).optional();
    }
    public LotCorrection correct(UUID actor,UUID membership,UUID org,UUID lot,UUID request,byte[] digest,LotMetadataCorrection command,LotData data) {
        var manufacturer=data.manufacturerProvenance();
        var marking=data.dateMarking(); var provenance=marking.provenance().orElse(null);
        return jdbc.sql("""
                WITH previous AS MATERIALIZED (
                    SELECT * FROM stockrescue.manufacturer_lot WHERE org_id=:org AND id=:lot AND version=:version FOR UPDATE
                ), revised AS (
                    UPDATE stockrescue.manufacturer_lot l SET identity_state=:state,manufacturer_identity_key=:manufacturer,
                        manufacturer_source=:source,manufacturer_reference=:reference,
                        confirmed_by_membership_id=CASE WHEN :confirmed THEN coalesce(l.confirmed_by_membership_id,:membership) ELSE NULL END,
                        confirmed_at=CASE WHEN :confirmed THEN coalesce(l.confirmed_at,clock_timestamp()) ELSE NULL END,
                        labeled_value=:label,date_marking_type=:type,date_precision=:precision,marking_source=:markingSource,marking_reference=:markingReference,
                        normalized_cutoff=NULL,normalization_policy_version_id=NULL,normalization_policy_version=NULL,normalization_version=NULL,
                        version=l.version+1
                    FROM previous p WHERE l.org_id=p.org_id AND l.id=p.id RETURNING l.*
                ), recorded AS (
                    INSERT INTO stockrescue.lot_correction(id,org_id,lot_id,actor_user_id,actor_membership_id,request_id,payload_digest,
                        expected_version,result_version,reason,before_evidence,after_evidence)
                    SELECT :id,:org,:lot,:actor,:membership,:request,:digest,p.version,r.version,:reason,to_jsonb(p),to_jsonb(r)
                    FROM previous p CROSS JOIN revised r RETURNING *
                )
                """+SELECT+" FROM recorded c "+SNAPSHOTS)
                .param("id",UUID.randomUUID()).param("org",org).param("lot",lot).param("actor",actor).param("membership",membership)
                .param("request",request).param("digest",digest).param("version",command.expectedVersion()).param("reason",command.reason())
                .param("state",data.identityState().name()).param("manufacturer",data.manufacturerIdentityKey(),java.sql.Types.VARCHAR)
                .param("source",manufacturer.source()).param("reference",manufacturer.reference(),java.sql.Types.VARCHAR)
                .param("confirmed",data.identityState()==LotIdentityState.CONFIRMED)
                .param("label",marking.labeledValue(),java.sql.Types.VARCHAR).param("type",marking.type().name()).param("precision",marking.precision().name())
                .param("markingSource",provenance==null?null:provenance.source(),java.sql.Types.VARCHAR)
                .param("markingReference",provenance==null?null:provenance.reference(),java.sql.Types.VARCHAR)
                .query((row,index) -> correction(row)).single();
    }
    public List<LotCorrection> history(UUID org,UUID lot,int offset,int limit) {
        return jdbc.sql(SELECT+" FROM stockrescue.lot_correction c "+SNAPSHOTS+" WHERE c.org_id=:org AND c.lot_id=:lot ORDER BY c.result_version DESC LIMIT :limit OFFSET :offset")
                .param("org",org).param("lot",lot).param("offset",offset).param("limit",limit)
                .query((row,index) -> correction(row)).list();
    }
    public LotCorrection recordSafetyEvidence(UUID actor,UUID membership,UUID org,UUID lot,UUID request,LotSafetyEvidence command) {
        return jdbc.sql("""
                WITH previous AS MATERIALIZED (
                    SELECT * FROM stockrescue.manufacturer_lot WHERE org_id=:org AND id=:lot AND version=:version FOR UPDATE
                ), revised AS (
                    UPDATE stockrescue.manufacturer_lot l SET recall_state=:recall,damage_state=:damage,status_source=:source,status_reference=:reference,version=l.version+1
                    FROM previous p WHERE l.org_id=p.org_id AND l.id=p.id RETURNING l.*
                ), recorded AS (
                    INSERT INTO stockrescue.lot_correction(id,org_id,lot_id,actor_user_id,actor_membership_id,request_id,payload_digest,
                        expected_version,result_version,reason,correction_kind,before_evidence,after_evidence)
                    SELECT :id,:org,:lot,:actor,:membership,:request,:digest,p.version,r.version,:reason,'SAFETY_STATUS',to_jsonb(p),to_jsonb(r)
                    FROM previous p CROSS JOIN revised r RETURNING *
                )
                """+SELECT+" FROM recorded c "+SNAPSHOTS)
                .param("id",UUID.randomUUID()).param("org",org).param("lot",lot).param("actor",actor).param("membership",membership)
                .param("request",request).param("digest",command.fingerprint(lot)).param("version",command.expectedVersion()).param("reason",command.reason())
                .param("recall",command.recallState().name()).param("damage",command.damageState().name()).param("source",command.provenance().source())
                .param("reference",command.provenance().reference()).query((row,index) -> correction(row)).single();
    }
    private LotCorrection correction(ResultSet row) throws SQLException {
        return new LotCorrection(row.getObject("id",UUID.class),row.getObject("lot_id",UUID.class),row.getObject("actor_user_id",UUID.class),
                row.getObject("actor_membership_id",UUID.class),row.getLong("expected_version"),row.getLong("result_version"),row.getString("reason"),
                row.getTimestamp("recorded_at").toInstant(),LotCorrection.Kind.valueOf(row.getString("correction_kind")),LotRepository.lot(row,"before_"),LotRepository.lot(row,"after_"));
    }
}
