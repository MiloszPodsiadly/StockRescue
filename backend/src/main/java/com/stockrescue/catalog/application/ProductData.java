package com.stockrescue.catalog.application;

import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.catalog.domain.Gtin;
import com.stockrescue.catalog.domain.Sku;
import com.stockrescue.catalog.domain.UnitOfMeasure;
import com.stockrescue.stores.application.OperationalConfiguration.TemperatureClass;

public record ProductData(Sku sku, String name, Gtin gtin, UnitOfMeasure baseUnit, TemperatureClass temperatureClass) {
    public ProductData {
        if (sku == null || name == null || name.isBlank() || name.strip().length()>160
                || name.codePoints().anyMatch(Character::isISOControl) || baseUnit == null || temperatureClass == null) {
            throw new CatalogFailure(CatalogFailure.Kind.INVALID_PRODUCT);
        }
        name = name.strip();
    }

    public static ProductData parse(String sku, String name, String gtin, String unit, TemperatureClass temperature) {
        return new ProductData(new Sku(sku), name, gtin == null ? null : new Gtin(gtin), UnitOfMeasure.parse(unit), temperature);
    }

    public byte[] fingerprint() {
        return CatalogCommandDigest.of(sku.value(), name, gtin == null ? null : gtin.value(), baseUnit.name(), temperatureClass.name());
    }
}
