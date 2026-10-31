package com.stockrescue.stores.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.stores.application.OperationalConfiguration.*;
import com.stockrescue.stores.domain.ConfigurationFailure;
import com.stockrescue.stores.persistence.StoreRepository;
import com.stockrescue.stores.persistence.LaneRepository;
import com.stockrescue.stores.persistence.ZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ZoneService {
    private final AccessPolicy access;
    private final ZoneRepository zones;
    private final LaneRepository lanes;
    private final StoreRepository stores;
    private final AuditWriter audit;
    public ZoneService(AccessPolicy access, ZoneRepository zones, LaneRepository lanes, StoreRepository stores, AuditWriter audit) { this.access=access;this.zones=zones;this.lanes=lanes;this.stores=stores;this.audit=audit; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ZonePage list(UUID actor,UUID org,UUID store,boolean includeInactive,int offset,int limit) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_STORE,false);
        page(offset,limit);
        var permissions=access.permissions(actor,org);
        var parent=stores.detail(org,store).orElseThrow(OrganizationUnavailableException::new);
        var rows=zones.list(org,store,includeInactive,offset,limit+1);
        return new ZonePage(rows.stream().limit(limit).toList(),rows.size()>limit,access.storeActions(permissions,store,parent.active()).contains(StoreAction.MANAGE_STORAGE_ZONES));
    }
    @Transactional
    public Zone create(UUID actor,UUID org,UUID store,String name,TemperatureClass temperature) {
        access.requireStore(actor,org,store,StoreAction.MANAGE_STORAGE_ZONES,true);
        name=name(name);
        if(temperature==null) invalid();
        var zone=zones.create(org,store,name,temperature).orElseThrow(ZoneService::conflict);
        audit.securityChange(actor,org,"storage-zone",zone.id(),"ZONE_CREATED",Map.of("outcome","SUCCEEDED","after",zone),"Storage compatibility configured");
        return zone;
    }
    @Transactional
    public Zone rename(UUID actor,UUID org,UUID store,UUID id,String name,long version,String reason) {
        access.requireStore(actor,org,store,StoreAction.MANAGE_STORAGE_ZONES,true);
        var before=existing(org,store,id);
        editable(before,version,reason);
        var after=zones.rename(org,store,id,name(name),version).orElseThrow(ZoneService::conflict);
        audit.securityChange(actor,org,"storage-zone",id,"ZONE_RENAMED",Map.of("outcome","SUCCEEDED","before",before,"after",after),reason);
        return after;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ZoneArchiveImpact archiveImpact(UUID actor,UUID org,UUID store,UUID id) {
        access.requireStore(actor,org,store,StoreAction.MANAGE_STORAGE_ZONES,false);
        var zone=zones.detail(org,store,id,false).orElseThrow(OrganizationUnavailableException::new);
        if(!zone.active()) throw new ConfigurationFailure(ConfigurationFailure.Kind.INACTIVE);
        return impact(org,store,zone,lanes.databaseNow());
    }
    @Transactional
    public Zone archive(UUID actor,UUID org,UUID store,UUID id,long version,String reason,String expectedImpactFingerprint) {
        access.requireStore(actor,org,store,StoreAction.MANAGE_STORAGE_ZONES,true);
        zones.detail(org,store,id,false).orElseThrow(OrganizationUnavailableException::new);
        lanes.lockForZoneImpact(org,store,id);
        var before=zones.detail(org,store,id,true).orElseThrow(OrganizationUnavailableException::new);
        if(!before.active()) throw new ConfigurationFailure(ConfigurationFailure.Kind.INACTIVE);
        reason(reason);
        if(before.version()!=version) throw new ConfigurationFailure(ConfigurationFailure.Kind.ZONE_IMPACT_CHANGED);
        var actual=impact(org,store,before,lanes.databaseNow());
        if(!actual.impactFingerprint().equals(expectedImpactFingerprint)) throw new ConfigurationFailure(ConfigurationFailure.Kind.ZONE_IMPACT_CHANGED);
        var after=zones.archive(org,store,id,version).orElseThrow(ZoneService::conflict);
        audit.securityChange(actor,org,"storage-zone",id,"ZONE_ARCHIVED",Map.of("outcome","SUCCEEDED","before",before,"after",after),reason);
        return after;
    }
    private ZoneArchiveImpact impact(UUID org,UUID store,Zone zone,Instant asOf) {
        var affected=zones.impactLanes(org,store,zone.id());
        var future=lanes.futureRevisionsForZone(org,store,zone.id(),asOf);
        var active=(int)affected.stream().filter(ZoneRepository.ImpactLane::active).count();
        var inactive=affected.size()-active;
        var fingerprint=fingerprint(org,store,zone.id(),zone.version(),affected,future);
        return new ZoneArchiveImpact(active,inactive,future.size(),zone.version(),fingerprint,asOf);
    }
    private String fingerprint(UUID org,UUID store,UUID zone,long zoneVersion,
            java.util.List<ZoneRepository.ImpactLane> affected,java.util.List<LaneRepository.ZoneImpactRevision> future) {
        var snapshot=new StringBuilder().append("zone:").append(org).append(':').append(store).append(':').append(zone).append(':').append(zoneVersion);
        affected.forEach(lane->snapshot.append("\nlane:").append(lane.id()).append(':').append(lane.version()).append(':').append(lane.active()));
        future.forEach(revision->snapshot.append("\nrevision:").append(revision.id()).append(':').append(revision.laneId()).append(':')
                .append(revision.revision()).append(':').append(revision.effectiveAt()));
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(snapshot.toString().getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private Zone existing(UUID org,UUID store,UUID id) { return zones.detail(org,store,id,true).orElseThrow(OrganizationUnavailableException::new); }
    private void editable(Zone zone,long version,String reason) {
        if(!zone.active()) throw new ConfigurationFailure(ConfigurationFailure.Kind.INACTIVE);
        if(zone.version()!=version) throw conflict();
        reason(reason);
    }
    static String name(String name) { if(name==null||name.isBlank()||name.strip().length()>100||name.codePoints().anyMatch(Character::isISOControl)) invalid();return name.strip(); }
    static void reason(String reason) { if(reason==null||reason.isBlank()||reason.length()>500||reason.indexOf('\0')>=0) invalid(); }
    static void page(int offset,int limit) { if(offset<0||offset>10000||limit<1||limit>100) invalid(); }
    static ConfigurationFailure conflict() { return new ConfigurationFailure(ConfigurationFailure.Kind.CONFLICT); }
    static void invalid() { throw new ConfigurationFailure(ConfigurationFailure.Kind.INVALID); }
}
