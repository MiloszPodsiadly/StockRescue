package com.stockrescue.stores.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.stores.domain.ConfigurationFailure;
import com.stockrescue.stores.domain.LocationData;
import com.stockrescue.stores.persistence.StorageLocationRepository;
import com.stockrescue.stores.persistence.StorageLocationRepository.Operation;
import com.stockrescue.stores.persistence.ZoneRepository;
import com.stockrescue.stores.persistence.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorageLocationService {
    public record Result(StorageLocation location,boolean created) { }
    public record Page(List<StorageLocation> items,boolean hasMore,boolean canManage) { }
    private final AccessPolicy access;private final StorageLocationRepository locations;private final ZoneRepository zones;private final StoreRepository stores;private final AuditWriter audit;
    public StorageLocationService(AccessPolicy access,StorageLocationRepository locations,ZoneRepository zones,StoreRepository stores,AuditWriter audit) { this.access=access;this.locations=locations;this.zones=zones;this.stores=stores;this.audit=audit; }
    @Transactional
    public Result create(UUID actor,UUID org,UUID store,UUID request,LocationData data) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.MANAGE_STORAGE_LOCATIONS,true);
        if (request==null || data==null) ZoneService.invalid();
        var digest=digest(store.toString(),data.code(),data.name(),data.zoneId().toString());var id=UUID.randomUUID();
        if (!locations.reserve(actor,org,store,Operation.CREATE,request,id,0,digest)) return replay(actor,org,store,Operation.CREATE,request,digest);
        if (!stores.detail(org,store).orElseThrow(OrganizationUnavailableException::new).active()) throw new ConfigurationFailure(ConfigurationFailure.Kind.INACTIVE);
        var zone=zones.detail(org,store,data.zoneId(),false).orElseThrow(OrganizationUnavailableException::new);
        if (!zone.active()) throw new ConfigurationFailure(ConfigurationFailure.Kind.INACTIVE);
        if (!locations.create(org,store,id,data)) throw ZoneService.conflict();
        var created=existing(org,store,id,false);
        if (!created.usable()) throw new ConfigurationFailure(ConfigurationFailure.Kind.INACTIVE);
        audit.securityChange(actor,org,"storage-location",id,"STORAGE_LOCATION_CREATED",Map.of("outcome","SUCCEEDED","after",created),"Storage location configured");
        return new Result(created,true);
    }
    @Transactional
    public Result rename(UUID actor,UUID org,UUID store,UUID id,UUID request,long version,String name,String reason) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.MANAGE_STORAGE_LOCATIONS,true);
        validate(request,version,reason);reason=reason.strip();
        var before=existing(org,store,id,true);name=new LocationData(before.data().code(),name,before.data().zoneId()).name();
        var digest=digest(store.toString(),id.toString(),Long.toString(version),name,reason);
        if (!locations.reserve(actor,org,store,Operation.RENAME,request,id,version+1,digest)) return replay(actor,org,store,Operation.RENAME,request,digest);
        editable(before,version);
        if (!locations.rename(org,store,id,name,version)) throw ZoneService.conflict();
        var after=existing(org,store,id,false);
        audit.securityChange(actor,org,"storage-location",id,"STORAGE_LOCATION_RENAMED",Map.of("outcome","SUCCEEDED","before",before,"after",after),reason);
        return new Result(after,true);
    }
    @Transactional
    public Result archive(UUID actor,UUID org,UUID store,UUID id,UUID request,long version,String reason) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.MANAGE_STORAGE_LOCATIONS,true);
        validate(request,version,reason);reason=reason.strip();
        var digest=digest(store.toString(),id.toString(),Long.toString(version),reason);
        if (!locations.reserve(actor,org,store,Operation.ARCHIVE,request,id,version+1,digest)) return replay(actor,org,store,Operation.ARCHIVE,request,digest);
        var before=existing(org,store,id,true);editable(before,version);
        if (!locations.archive(org,store,id,version)) throw ZoneService.conflict();
        var after=existing(org,store,id,false);
        audit.securityChange(actor,org,"storage-location",id,"STORAGE_LOCATION_ARCHIVED",Map.of("outcome","SUCCEEDED","before",before,"after",after),reason);
        return new Result(after,true);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public StorageLocation detail(UUID actor,UUID org,UUID store,UUID id) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_STORE,false);
        return existing(org,store,id,false);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(UUID actor,UUID org,UUID store,boolean includeArchived,String q,int offset,int limit) {
        access.requireStoreIncludingArchived(actor,org,store,StoreAction.READ_STORE,false);
        ZoneService.page(offset,limit);
        if (q==null || q.length()>100 || q.codePoints().anyMatch(Character::isISOControl)) ZoneService.invalid();
        var permissions=access.permissions(actor,org);
        var rows=locations.list(org,store,includeArchived,q.strip(),offset,limit+1);
        boolean canManage=permissions.allStoreActions().contains(StoreAction.MANAGE_STORAGE_LOCATIONS)
                || permissions.storeGrants().stream().anyMatch(grant -> grant.storeId().equals(store) && grant.actions().contains(StoreAction.MANAGE_STORAGE_LOCATIONS));
        // Parent state is read independently even for an empty page.
        return new Page(rows.stream().limit(limit).toList(),rows.size()>limit,canManage && stores.detail(org,store).orElseThrow(OrganizationUnavailableException::new).active());
    }
    private Result replay(UUID actor,UUID org,UUID store,Operation operation,UUID request,byte[] digest) {
        var recorded=locations.receipt(actor,org,operation,request);
        if (!recorded.storeId().equals(store) || !MessageDigest.isEqual(recorded.digest(),digest)) throw ZoneService.conflict();
        return new Result(existing(org,store,recorded.locationId(),false),false);
    }
    private StorageLocation existing(UUID org,UUID store,UUID id,boolean lock) { return locations.detail(org,store,id,lock).orElseThrow(OrganizationUnavailableException::new); }
    private void editable(StorageLocation location,long version) { if (!location.usable()) throw new ConfigurationFailure(ConfigurationFailure.Kind.INACTIVE);if (location.version()!=version) throw ZoneService.conflict(); }
    private void validate(UUID request,long version,String reason) { if (request==null || version<0 || version==Long.MAX_VALUE || reason==null || reason.isBlank() || reason.length()>500 || reason.codePoints().anyMatch(Character::isISOControl)) ZoneService.invalid(); }
    private byte[] digest(String... fields) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for (var field:fields) { var bytes=field.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes); }
            return digest.digest();
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable",unavailable); }
    }
}
