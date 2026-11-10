package com.stockrescue.catalog.application;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.OrganizationAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.persistence.FoodSafetyProductClassificationConfirmationRepository;
import com.stockrescue.catalog.persistence.ProductRepository;
import com.stockrescue.catalog.persistence.ProductRepository.Operation;
import com.stockrescue.platform.audit.AuditWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Records and reads source-attributed operator confirmations for a specific product revision. */
@Service
public class FoodSafetyProductClassificationConfirmationService {
    public record ConfirmationResult(ProductService.View product,FoodSafetyProductClassificationConfirmation confirmation,
            FoodSafetyProductClassificationConfirmation.Status status,boolean created) { }

    private final AccessPolicy access;
    private final ProductRepository products;
    private final FoodSafetyProductClassificationConfirmationRepository confirmations;
    private final AuditWriter audit;
    private final Clock clock;

    public FoodSafetyProductClassificationConfirmationService(AccessPolicy access,ProductRepository products,
            FoodSafetyProductClassificationConfirmationRepository confirmations,AuditWriter audit,Clock clock) {
        this.access=access;this.products=products;this.confirmations=confirmations;this.audit=audit;this.clock=clock;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ConfirmationResult confirmation(UUID actor,UUID organizationId,UUID productId) {
        access.requireOrganizationAction(actor,organizationId,OrganizationAction.READ_CATALOG,false);
        var product=product(organizationId,productId,false);
        var confirmation=confirmations.latest(organizationId,productId).orElse(null);
        return confirmationResult(actor,organizationId,product,confirmation,false);
    }

    @Transactional
    public ConfirmationResult confirm(UUID actor,UUID organizationId,UUID productId,
            FoodSafetyProductClassificationConfirmationSubmission submission) {
        var membership=access.requireOrganizationAction(actor,organizationId,OrganizationAction.MANAGE_CATALOG,true);
        if (submission==null || submission.requestId()==null || submission.expectedProductVersion()<0
                || submission.expectedProductVersion()==Long.MAX_VALUE || !submission.hasValidEvidenceShape()
                || submission.reason()==null || submission.reason().isBlank() || submission.reason().length()>500
                || submission.reason().codePoints().anyMatch(Character::isISOControl)
                || submission.evidenceReference()!=null && (submission.evidenceReference().isBlank()
                        || submission.evidenceReference().length()>256
                        || submission.evidenceReference().codePoints().anyMatch(Character::isISOControl))) invalid();

        var normalized=new FoodSafetyProductClassificationConfirmationSubmission(submission.requestId(),
                submission.expectedProductVersion(),submission.category(),submission.temperatureClass(),submission.productProfile(),submission.sourceType(),
                submission.evidenceReference()==null?null:submission.evidenceReference().strip(),submission.reason().strip());
        var fingerprint=normalized.fingerprint(organizationId,productId);
        var prior=products.receiptIfPresent(actor,organizationId,Operation.CONFIRM_CLASSIFICATION,submission.requestId());
        if (prior.isPresent()) return replayConfirmation(actor,organizationId,productId,submission.requestId(),fingerprint,prior.orElseThrow());

        var resultVersion=submission.expectedProductVersion()+1;
        if (!products.reserve(actor,organizationId,Operation.CONFIRM_CLASSIFICATION,submission.requestId(),productId,resultVersion,fingerprint)) {
            var receipt=products.receipt(actor,organizationId,Operation.CONFIRM_CLASSIFICATION,submission.requestId());
            return replayConfirmation(actor,organizationId,productId,submission.requestId(),fingerprint,receipt);
        }

        var before=product(organizationId,productId,true);
        if (!before.active()) throw new CatalogFailure(CatalogFailure.Kind.INACTIVE);
        if (before.version()!=submission.expectedProductVersion()) conflict();
        var after=products.confirmSafetyAttributes(organizationId,productId,submission.expectedProductVersion(),
                submission.category(),submission.temperatureClass())
                .orElseThrow(() -> new CatalogFailure(CatalogFailure.Kind.CONFLICT));
        var candidate=new FoodSafetyProductClassificationConfirmation(UUID.randomUUID(),organizationId,productId,
                before.data().sku().value(),before.version(),after.version(),before.data().category(),after.data().category(),
                before.data().temperatureClass(),after.data().temperatureClass(),normalized.productProfile(),
                normalized.sourceType(),normalized.evidenceReference(),actor,membership.membershipId(),normalized.requestId(),
                normalized.reason(),fingerprint,clock.instant());
        var stored=confirmations.append(candidate);
        var changes=new LinkedHashMap<String,Object>();
        changes.put("outcome","SUCCEEDED");
        changes.put("before",before);
        changes.put("after",after);
        changes.put("confirmation",stored);
        audit.securityChange(actor,organizationId,"product",productId,"PRODUCT_CLASSIFICATION_CONFIRMED",changes,normalized.reason());
        return confirmationResult(actor,organizationId,after,stored,true);
    }

    private ConfirmationResult replayConfirmation(UUID actor,UUID organizationId,UUID productId,UUID requestId,
            byte[] fingerprint,ProductRepository.Receipt receipt) {
        if (!receipt.productId().equals(productId) || !MessageDigest.isEqual(receipt.digest(),fingerprint)) conflict();
        var confirmation=confirmations.byRequest(organizationId,actor,requestId)
                .orElseThrow(() -> new IllegalStateException("Product classification command receipt has no evidence"));
        var product=product(organizationId,productId,false);
        return confirmationResult(actor,organizationId,product,confirmation,false);
    }

    private ConfirmationResult confirmationResult(UUID actor,UUID organizationId,Product product,
            FoodSafetyProductClassificationConfirmation confirmation,boolean created) {
        var status=confirmation==null?FoodSafetyProductClassificationConfirmation.Status.NOT_RECORDED:confirmation.statusFor(product);
        var permissions=access.permissions(actor,organizationId);
        var actions=product.active() && permissions.organizationActions().contains(OrganizationAction.MANAGE_CATALOG)
                ?List.of(OrganizationAction.READ_CATALOG,OrganizationAction.MANAGE_CATALOG):List.of(OrganizationAction.READ_CATALOG);
        return new ConfirmationResult(new ProductService.View(product,actions),confirmation,status,created);
    }

    private Product product(UUID organizationId,UUID productId,boolean lock) {
        return products.detail(organizationId,productId,lock).orElseThrow(OrganizationUnavailableException::new);
    }
    private static void invalid() { throw new CatalogFailure(CatalogFailure.Kind.INVALID_POLICY); }
    private static void conflict() { throw new CatalogFailure(CatalogFailure.Kind.CONFLICT); }
}
