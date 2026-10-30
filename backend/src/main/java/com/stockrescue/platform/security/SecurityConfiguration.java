package com.stockrescue.platform.security;

import java.util.List;

import com.stockrescue.platform.http.ApiProblems;
import com.stockrescue.platform.http.ProblemCode;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    public static final String CSRF_HEADER = "X-CSRF-TOKEN";

    @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean CookieSerializer cookieSerializer(@Value("${stockrescue.security.secure-cookie:true}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("SRSESSION");
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(secure);
        cookie.setSameSite("Lax");
        return cookie;
    }

    @Bean SecurityContextRepository securityContextRepository() {
        var repository = new HttpSessionSecurityContextRepository();
        repository.setDisableUrlRewriting(true);
        return repository;
    }

    @Bean CsrfTokenRepository csrfTokenRepository() {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName(CSRF_HEADER);
        return repository;
    }

    @Bean SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ApiProblems problems,
            SecurityContextRepository contexts, CsrfTokenRepository csrf, SessionAccountStatus accounts,
            WebSessions sessions, Environment environment,
            @Value("${stockrescue.security.maximum-session-age-seconds:43200}") long maximumAge) throws Exception {
        http.httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
                .csrf(config -> config.csrfTokenRepository(csrf))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> problems.write(response,
                                HttpStatus.UNAUTHORIZED, ProblemCode.AUTH_REQUIRED, "Sign in to continue."))
                        .accessDeniedHandler((request, response, exception) -> problems.write(response,
                                HttpStatus.FORBIDDEN, exception instanceof CsrfException ? ProblemCode.CSRF_INVALID : ProblemCode.ACCESS_DENIED,
                                exception instanceof CsrfException ? "Refresh this page and try again." : "This action is unavailable.")))
                .authorizeHttpRequests(authorize -> {
                    authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/health", "/api/health/liveness", "/api/health/readiness", "/api/health/integration", "/api/v1/auth/csrf").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/sign-up", "/api/v1/auth/sign-in").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/auth/me", "/api/v1/auth/me/effective-permissions",
                                    "/api/v1/auth/active-organization", "/api/v1/organizations", "/api/v1/organizations/*").authenticated()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/sign-out", "/api/v1/auth/active-organization",
                                    "/api/v1/organizations", "/api/v1/invitations/accept",
                                    "/api/v1/organizations/*/invitations", "/api/v1/organizations/*/members/*/approve").authenticated()
                            .requestMatchers(HttpMethod.GET, "/api/v1/organizations/*/members", "/api/v1/organizations/*/members/*",
                                    "/api/v1/organizations/*/invitations", "/api/v1/organizations/*/access-stores").authenticated()
                            .requestMatchers(HttpMethod.PATCH, "/api/v1/organizations/*/members/*/role").authenticated()
                            .requestMatchers(HttpMethod.PUT, "/api/v1/organizations/*/members/*/store-grants/*").authenticated()
                            .requestMatchers(HttpMethod.DELETE, "/api/v1/organizations/*/members/*", "/api/v1/organizations/*/members/*/store-grants/*",
                                    "/api/v1/organizations/*/invitations/*").authenticated()
                            .requestMatchers(HttpMethod.GET, "/api/v1/organizations/*/stores", "/api/v1/organizations/*/stores/*").authenticated()
                            .requestMatchers(HttpMethod.POST, "/api/v1/organizations/*/stores", "/api/v1/organizations/*/stores/*/archive").authenticated()
                            .requestMatchers(HttpMethod.PATCH, "/api/v1/organizations/*/stores/*").authenticated()
                            .requestMatchers(HttpMethod.GET, "/api/v1/organizations/*/stores/*/storage-zones",
                                    "/api/v1/organizations/*/transport-lanes", "/api/v1/organizations/*/transport-lanes/*", "/api/v1/organizations/*/transport-lanes/*/revisions").authenticated()
                            .requestMatchers(HttpMethod.POST, "/api/v1/organizations/*/stores/*/storage-zones", "/api/v1/organizations/*/stores/*/storage-zones/*/archive",
                                    "/api/v1/organizations/*/transport-lanes", "/api/v1/organizations/*/transport-lanes/*/revisions").authenticated()
                            .requestMatchers(HttpMethod.PATCH, "/api/v1/organizations/*/stores/*/storage-zones/*", "/api/v1/organizations/*/transport-lanes/*").authenticated();
                    if (environment.acceptsProfiles(Profiles.of("contract"))) {
                        authorize.requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs.yaml").permitAll();
                    }
                    authorize.anyRequest().denyAll();
                })
                .addFilterBefore(new AuthRequestFilter(problems), CsrfFilter.class)
                .addFilterBefore(new AccountSessionFilter(accounts, sessions, problems, maximumAge), AuthorizationFilter.class);
        return http.build();
    }
}
