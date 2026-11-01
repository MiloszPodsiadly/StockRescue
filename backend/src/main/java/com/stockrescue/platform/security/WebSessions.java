package com.stockrescue.platform.security;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebSessions {
    public static final String AUTHENTICATED_AT = "authenticatedAt";
    private static final String ACTIVE_ORGANIZATION = "activeOrganizationId";
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy strategy;
    private final CsrfTokenRepository csrf;

    public WebSessions(SecurityContextRepository contexts, SessionAuthenticationStrategy strategy, CsrfTokenRepository csrf) {
        this.contexts = contexts;
        this.strategy = strategy;
        this.csrf = csrf;
    }

    public void signIn(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        strategy.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        clearOrganization(request);
        request.getSession().setAttribute(AUTHENTICATED_AT, System.currentTimeMillis());
        contexts.saveContext(context, request, response);
    }

    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        new CsrfLogoutHandler(csrf).logout(request, response, authentication);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }

    public UUID selectedOrganization(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        var value = session.getAttribute(ACTIVE_ORGANIZATION);
        if (value instanceof String id) {
            try { return UUID.fromString(id); }
            catch (IllegalArgumentException ignored) { clearOrganization(request); }
        }
        return null;
    }

    public void selectOrganization(HttpServletRequest request, UUID organization) {
        request.getSession(false).setAttribute(ACTIVE_ORGANIZATION, organization.toString());
    }

    public void clearOrganization(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.removeAttribute(ACTIVE_ORGANIZATION);
    }
}
