package com.stockrescue.stores.domain;

import java.util.Locale;
import java.util.UUID;

public record LocationData(String code,String name,UUID zoneId) {
    public LocationData {
        if (code==null || code.codePoints().anyMatch(Character::isISOControl) || !code.strip().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")
                || name==null || name.isBlank() || name.strip().length()>160 || name.codePoints().anyMatch(Character::isISOControl) || zoneId==null)
            throw new ConfigurationFailure(ConfigurationFailure.Kind.INVALID);
        code=code.strip().toUpperCase(Locale.ROOT);name=name.strip();
    }
}
