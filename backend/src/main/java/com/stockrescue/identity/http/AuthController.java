package com.stockrescue.identity.http;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.stockrescue.identity.application.Account;
import com.stockrescue.identity.application.AccountService;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.platform.security.WebSessions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required or invalid credentials", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "403", description = "CSRF or access denied", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "413", description = "Request too large", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "429", description = "Authentication rate limit", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "500", description = "Request could not be completed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "503", description = "Session storage temporarily unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
})
public class AuthController {
    private final AccountService accounts;
    private final AuthenticationManager authentication;
    private final WebSessions sessions;

    public AuthController(AccountService accounts, AuthenticationManager authentication, WebSessions sessions) {
        this.accounts = accounts;
        this.authentication = authentication;
        this.sessions = sessions;
    }

    @GetMapping("/csrf")
    @ApiResponse(responseCode = "200", description = "Session-bound CSRF token", content = @Content(schema = @Schema(implementation = CsrfResponse.class)))
    @Operation(operationId = "csrf", summary = "Obtain a session-bound CSRF token")
    public CsrfResponse csrf(@Parameter(hidden = true) CsrfToken csrf) {
        return new CsrfResponse(csrf.getHeaderName(), csrf.getToken());
    }

    @PostMapping(value = "/sign-up", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ApiResponse(responseCode = "202", description = "Registration request processed", content = @Content(schema = @Schema(implementation = RegistrationResponse.class)))
    @Operation(operationId = "signUp", summary = "Submit registration without disclosing existing accounts")
    public RegistrationResponse signUp(@Valid @RequestBody Credentials credentials) {
        credentials.validatePasswordBytes();
        accounts.register(credentials.email(), credentials.password());
        return new RegistrationResponse("Registration request processed. Continue to sign in with your details.");
    }

    @PostMapping(value = "/sign-in", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Authenticated account", content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @Operation(operationId = "signIn", summary = "Authenticate and rotate the server session")
    public UserResponse signIn(@Valid @RequestBody Credentials credentials, HttpServletRequest request, HttpServletResponse response) {
        credentials.validatePasswordBytes();
        var authenticated = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(credentials.email(), credentials.password()));
        sessions.signIn(authenticated, request, response);
        return UserResponse.from(accounts.account(UUID.fromString(authenticated.getName())));
    }

    @GetMapping("/me")
    @ApiResponse(responseCode = "200", description = "Current account", content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @Operation(operationId = "me", summary = "Read the currently authenticated account", security = @SecurityRequirement(name = "session"))
    public UserResponse me(@Parameter(hidden = true) Authentication authenticated) {
        return UserResponse.from(accounts.account(UUID.fromString(authenticated.getName())));
    }

    @PostMapping("/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Session invalidated", content = @Content)
    @Operation(operationId = "signOut", summary = "Invalidate the current session", security = @SecurityRequirement(name = "session"))
    public void signOut(HttpServletRequest request, HttpServletResponse response) { sessions.signOut(request, response); }

    public record Credentials(
            @NotBlank @Size(max = 254) @Email @Pattern(regexp = "[\\x21-\\x7E]+") String email,
            @NotBlank @Size(min = 12, max = 72) String password) {
        public Credentials { email = email == null ? null : email.strip(); }
        void validatePasswordBytes() {
            if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        @Override public String toString() { return "Credentials[redacted]"; }
    }

    public record UserResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
        static UserResponse from(Account account) { return new UserResponse(account.id(), account.email(), account.createdAt()); }
    }
    public record RegistrationResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message) { }
    public record CsrfResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String headerName,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String token) { }
}
