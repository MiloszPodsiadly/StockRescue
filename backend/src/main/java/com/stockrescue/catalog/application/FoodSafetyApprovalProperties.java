package com.stockrescue.catalog.application;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment-owned trust configuration. Tenant APIs never write this registry. */
@Component
@ConfigurationProperties(prefix="stockrescue.food-safety.approval")
public class FoodSafetyApprovalProperties {
    private boolean enabled;
    private Map<String, TrustedIssuer> trustedIssuers = new HashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled=enabled; }
    public Map<String, TrustedIssuer> getTrustedIssuers() { return trustedIssuers; }
    public void setTrustedIssuers(Map<String, TrustedIssuer> trustedIssuers) {
        this.trustedIssuers=trustedIssuers==null?new HashMap<>():new HashMap<>(trustedIssuers);
    }

    public static class TrustedIssuer {
        private String issuerId;
        private String publicKeyX509;
        private boolean enabled;

        public String getIssuerId() { return issuerId; }
        public void setIssuerId(String issuerId) { this.issuerId=issuerId; }
        public String getPublicKeyX509() { return publicKeyX509; }
        public void setPublicKeyX509(String publicKeyX509) { this.publicKeyX509=publicKeyX509; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled=enabled; }
    }
}
