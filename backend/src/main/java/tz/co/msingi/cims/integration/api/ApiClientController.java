package tz.co.msingi.cims.integration.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Tag(name = "API clients")
@RestController
@RequiredArgsConstructor
public class ApiClientController {

    private static final String ADMIN = "hasRole('INSTITUTION_ADMIN')";
    private final ApiClientService service;

    @GetMapping("/api/api-clients")
    @PreAuthorize(ADMIN)
    public List<ApiClientService.ClientView> list() {
        return service.list();
    }

    @GetMapping("/api/api-clients/scopes")
    @PreAuthorize(ADMIN)
    public Map<String, String> scopes() {
        return ApiClientService.SCOPES;
    }

    @PostMapping("/api/api-clients")
    @PreAuthorize(ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiClientService.IssuedSecret create(@Valid @RequestBody ApiClientService.ClientRequest r) {
        return service.create(r);
    }

    @PutMapping("/api/api-clients/{id}")
    @PreAuthorize(ADMIN)
    public ApiClientService.ClientView update(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean active,
                                              @Valid @RequestBody ApiClientService.ClientRequest r) {
        return service.update(id, r, active);
    }

    @PostMapping("/api/api-clients/{id}/secret")
    @PreAuthorize(ADMIN)
    public ApiClientService.IssuedSecret rotate(@PathVariable Long id) {
        return service.rotate(id);
    }

    @Operation(summary = "OAuth2 client credentials token (client_id and client_secret in the form or HTTP Basic)")
    @PostMapping(value = "/api/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> token(HttpServletRequest req, @RequestParam("grant_type") String grantType,
                                   @RequestParam(value = "client_id", required = false) String clientId,
                                   @RequestParam(value = "client_secret", required = false) String clientSecret,
                                   @RequestParam(value = "scope", required = false) String scope) {
        if (!"client_credentials".equals(grantType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported_grant_type"));
        }
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String[] p = new String(Base64.getDecoder().decode(auth.substring(6).trim()), StandardCharsets.UTF_8).split(":", 2);
                if (p.length == 2) {
                    clientId = p[0];
                    clientSecret = p[1];
                }
            } catch (IllegalArgumentException ignored) {
                // falls through to invalid_client
            }
        }
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.token(clientId, clientSecret, scope));
        } catch (ApiClientService.InvalidClient e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", "Basic realm=\"cims\"")
                    .body(Map.of("error", "invalid_client"));
        } catch (ApiClientService.InvalidScope e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_scope"));
        }
    }
}
