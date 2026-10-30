package com.stockrescue.identity.application;

import java.util.List;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class AccountAuthenticationProvider implements AuthenticationProvider {
    private final AccountService accounts;
    public AccountAuthenticationProvider(AccountService accounts) { this.accounts = accounts; }

    @Override public Authentication authenticate(Authentication request) {
        var account = accounts.authenticate(request.getName(), request.getCredentials().toString());
        return UsernamePasswordAuthenticationToken.authenticated(account.id().toString(), null, List.of());
    }

    @Override public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
}
