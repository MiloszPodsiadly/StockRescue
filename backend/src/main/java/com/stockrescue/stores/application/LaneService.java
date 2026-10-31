package com.stockrescue.stores.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.stores.application.OperationalConfiguration.*;
import com.stockrescue.stores.domain.ConfigurationFailure;
import com.stockrescue.stores.domain.CostParameters;
import com.stockrescue.stores.persistence.LaneRepository;
import com.stockrescue.stores.persistence.ZoneRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LaneService {
    private final AccessPolicy access; private final LaneRepository lanes; private final ZoneRepository zones; private final AuditWriter audit;
    public LaneService(AccessPolicy access,LaneRepository lanes,ZoneRepository zones,AuditWriter audit) { this.access=access;this.lanes=lanes;this.zones=zones;this.audit=audit; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<String> currencies(UUID actor,UUID org) { access.requireAdmin(actor,org,false);return CostParameters.supportedCurrencies(); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public LanePage list(UUID actor,UUID org,int offset,int limit) {
        access.requireAdmin(actor,org,false);ZoneService.page(offset,limit);
        var asOf=lanes.databaseNow();var rows=lanes.list(org,offset,limit+1);var visible=rows.stream().limit(limit).toList();
        var costs=lanes.currentCosts(org,visible.stream().map(Lane::id).toList(),asOf);
        return new LanePage(visible.stream().map(lane->view(lane,costs.stream().filter(cost->cost.laneId().equals(lane.id())).map(LaneRepository.CostRow::revision).findFirst().orElse(null),asOf)).toList(),rows.size()>limit,asOf);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public LaneView detail(UUID actor,UUID org,UUID id) { access.requireAdmin(actor,org,false);return view(org,existing(org,id),lanes.databaseNow()); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RouteLookup lookup(UUID actor,UUID org,UUID origin,UUID destination) {
        access.requireAdmin(actor,org,false);pair(origin,destination);
        for(var store:List.of(origin,destination).stream().sorted().toList()) access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_STORE,false);
        var asOf=lanes.databaseNow();return new RouteLookup(lanes.route(org,origin,destination).map(lane->view(org,lane,asOf)).orElse(null),asOf);
    }
    @Transactional
    public LaneView create(UUID actor,UUID org,UUID origin,UUID destination,UUID originZone,UUID destinationZone,String reason) {
        access.requireAdmin(actor,org,true);pair(origin,destination);ZoneService.reason(reason);
        lockStores(actor,org,origin,destination,false);compatible(org,origin,destination,originZone,destinationZone);
        var id=lanes.create(org,origin,destination,originZone,destinationZone).orElseThrow(ZoneService::conflict);
        var lane=existing(org,id);
        audit.securityChange(actor,org,"transport-lane",id,"LANE_CREATED",Map.of("outcome","SUCCEEDED","after",lane),reason);
        return view(org,lane,lanes.databaseNow());
    }
    @Transactional
    public LaneView update(UUID actor,UUID org,UUID id,UUID originZone,UUID destinationZone,boolean active,long version,String reason) {
        access.requireAdmin(actor,org,true);ZoneService.reason(reason);
        var before=existing(org,id);lockStores(actor,org,before.originStoreId(),before.destinationStoreId(),!active);lanes.lock(org,id);
        before=existing(org,id);if(before.version()!=version) throw ZoneService.conflict();
        var asOf=lanes.databaseNow();
        if(active&&(!Objects.equals(originZone,before.originZoneId())||!Objects.equals(destinationZone,before.destinationZoneId()))
                &&lanes.hasFutureRevisions(org,id,asOf)) throw new ConfigurationFailure(ConfigurationFailure.Kind.SCHEDULED_REVISIONS_PENDING);
        if(active) compatible(org,before.originStoreId(),before.destinationStoreId(),originZone,destinationZone);
        else if(!Objects.equals(originZone,before.originZoneId())||!Objects.equals(destinationZone,before.destinationZoneId())) throw ZoneService.conflict();
        lanes.update(org,id,originZone,destinationZone,active);var after=existing(org,id);
        audit.securityChange(actor,org,"transport-lane",id,"LANE_UPDATED",Map.of("outcome","SUCCEEDED","before",before,"after",after),reason);
        return view(org,after,asOf);
    }
    @Transactional
    public LaneView revise(UUID actor,UUID org,UUID id,long version,long expectedRevision,String amount,String currency,int minutes,Instant effectiveAt,String reason) {
        var administrator=access.requireAdmin(actor,org,true);ZoneService.reason(reason);
        var before=existing(org,id);lockStores(actor,org,before.originStoreId(),before.destinationStoreId(),false);lanes.lock(org,id);before=existing(org,id);
        if(!before.active()) throw inactive();
        if(before.version()!=version||before.latestRevision()!=expectedRevision) throw ZoneService.conflict();
        compatible(org,before.originStoreId(),before.destinationStoreId(),before.originZoneId(),before.destinationZoneId());
        var parameters=CostParameters.parse(amount,currency,minutes);var now=lanes.databaseNow();
        if(effectiveAt!=null&&(effectiveAt.getNano()%1000!=0||effectiveAt.isBefore(now))) ZoneService.invalid();
        var effectiveForOrder=effectiveAt==null?now:effectiveAt;
        if(before.latestEffectiveAt()!=null&&!effectiveForOrder.isAfter(before.latestEffectiveAt())) throw ZoneService.conflict();
        var approvedAt=lanes.revise(org,before,administrator.membershipId(),parameters,effectiveAt);
        var after=existing(org,id);var revision=lanes.history(org,id,0,1).getFirst();
        audit.securityChange(actor,org,"transport-lane",id,"LANE_COST_REVISED",Map.of("outcome","SUCCEEDED","previousRevision",expectedRevision,"after",revision),reason);
        return view(org,after,approvedAt);
    }
    @Transactional
    public CancellationResult cancelFutureRevisions(UUID actor,UUID org,UUID id,long version,long expectedRevision,String reason) {
        var administrator=access.requireAdmin(actor,org,true);ZoneService.reason(reason);
        var before=existing(org,id);lockStores(actor,org,before.originStoreId(),before.destinationStoreId(),true);lanes.lock(org,id);before=existing(org,id);
        if(before.version()!=version||before.latestRevision()!=expectedRevision) throw ZoneService.conflict();
        var batch=lanes.cancelFutureRevisions(org,id,administrator.membershipId(),reason,MDC.get("correlationId"));
        var cancelled=batch.revisions();
        if(cancelled.isEmpty()) throw new ConfigurationFailure(ConfigurationFailure.Kind.NO_FUTURE_REVISIONS);
        var after=existing(org,id);
        var cancelledEvidence=cancelled.stream().map(item->Map.of("revisionId",item.revisionId(),"revision",item.revision())).toList();
        audit.securityChange(actor,org,"transport-lane",id,"LANE_SCHEDULED_REVISIONS_CANCELLED",
                Map.of("outcome","SUCCEEDED","beforeVersion",before.version(),"afterVersion",after.version(),"cancelledRevisions",cancelledEvidence),reason);
        return new CancellationResult(view(org,after,batch.asOf()),cancelled);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RevisionPage history(UUID actor,UUID org,UUID id,int offset,int limit) {
        access.requireAdmin(actor,org,false);existing(org,id);ZoneService.page(offset,limit);
        var rows=lanes.history(org,id,offset,limit+1);return new RevisionPage(rows.stream().limit(limit).toList(),rows.size()>limit);
    }
    private void lockStores(UUID actor,UUID org,UUID origin,UUID destination,boolean includeArchived) {
        for(var store:List.of(origin,destination).stream().sorted().toList()) {
            if(includeArchived) access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_STORE,true);
            else access.requireStore(actor,org,store,StoreAction.READ_STORE,true);
        }
    }
    private void compatible(UUID org,UUID origin,UUID destination,UUID originZone,UUID destinationZone) {
        if(originZone==null||destinationZone==null) ZoneService.invalid();
        var a=zones.detail(org,origin,originZone,true).orElseThrow(OrganizationUnavailableException::new);
        var b=zones.detail(org,destination,destinationZone,true).orElseThrow(OrganizationUnavailableException::new);
        if(!a.active()||!b.active()) throw inactive();
        if(a.temperatureClass()!=b.temperatureClass()) throw new ConfigurationFailure(ConfigurationFailure.Kind.INCOMPATIBLE_ZONES);
    }
    private Lane existing(UUID org,UUID id) { return lanes.detail(org,id).orElseThrow(OrganizationUnavailableException::new); }
    private LaneView view(UUID org,Lane lane,Instant asOf) { return view(lane,lanes.currentCosts(org,List.of(lane.id()),asOf).stream().map(LaneRepository.CostRow::revision).findFirst().orElse(null),asOf); }
    private LaneView view(Lane lane,Revision cost,Instant asOf) {
        var missing=new ArrayList<Missing>();
        if(!lane.active()) missing.add(Missing.INACTIVE_LANE);
        if(!lane.originStoreActive()||!lane.destinationStoreActive()) missing.add(Missing.INACTIVE_STORE);
        if(lane.originZoneId()==null||lane.destinationZoneId()==null) missing.add(Missing.MISSING_ZONES);
        else {
            if(!lane.originZoneActive()||!lane.destinationZoneActive()) missing.add(Missing.INACTIVE_ZONE);
            if(lane.originTemperature()!=lane.destinationTemperature()) missing.add(Missing.INCOMPATIBLE_ZONES);
        }
        if(cost==null) missing.add(Missing.NO_EFFECTIVE_COST);
        else if(cost.originZoneId()==null||!cost.originZoneId().equals(lane.originZoneId())||!cost.destinationZoneId().equals(lane.destinationZoneId())) missing.add(Missing.COST_ZONE_MISMATCH);
        var status=missing.stream().anyMatch(value->value==Missing.INACTIVE_LANE||value==Missing.INACTIVE_STORE||value==Missing.INACTIVE_ZONE)?Status.INACTIVE:missing.isEmpty()?Status.CONFIGURED:Status.UNCONFIGURED;
        return new LaneView(lane,status,List.copyOf(missing),missing.isEmpty()?cost:null,asOf);
    }
    private void pair(UUID origin,UUID destination) { if(origin==null||destination==null||origin.equals(destination)) ZoneService.invalid(); }
    private ConfigurationFailure inactive() { return new ConfigurationFailure(ConfigurationFailure.Kind.INACTIVE); }
    public record RouteLookup(LaneView lane,Instant asOf) { }
}
