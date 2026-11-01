package com.stockrescue.stores.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record CostParameters(BigDecimal amount, String currencyCode, int durationMinutes) {
    private static final Set<String> SUPPORTED = Arrays.stream(Locale.getISOCountries())
            .map(country -> Currency.getInstance(Locale.of("", country))).filter(Objects::nonNull)
            .filter(currency -> currency.getDefaultFractionDigits() >= 0).map(Currency::getCurrencyCode).collect(Collectors.toUnmodifiableSet());
    public CostParameters {
        if (amount == null || amount.signum()<0 || amount.compareTo(new BigDecimal("1000000000000000"))>=0
                || amount.scale()>4 || currencyCode==null || !SUPPORTED.contains(currencyCode) || durationMinutes<=0) invalid();
        amount = amount.setScale(4, RoundingMode.UNNECESSARY);
    }
    public static CostParameters parse(String amount, String currency, int minutes) {
        if (amount==null || !amount.matches("(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,4})?")) invalid();
        return new CostParameters(new BigDecimal(amount), currency, minutes);
    }
    public static List<String> supportedCurrencies() { return SUPPORTED.stream().sorted().toList(); }
    private static void invalid() { throw new ConfigurationFailure(ConfigurationFailure.Kind.INVALID); }
}
