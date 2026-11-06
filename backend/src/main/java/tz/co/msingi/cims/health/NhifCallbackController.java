package tz.co.msingi.cims.health;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.util.Locale;
import java.util.Map;

/**
 * What NHIF calls on the institution's own address (e.g. https://bahari.cims.ac.tz): the token endpoint for NHIF's
 * client_credentials, and the callbacks of the Student Registration API. One address takes every kind of callback (the kind is read
 * from the payload); /callback/{kind} is there for NHIF set-ups that use one address per kind.
 */
@Tag(name = "NHIF callbacks")
@RestController
@RequestMapping("/api/integrations/nhif")
@RequiredArgsConstructor
public class NhifCallbackController {

    private final NhifSetupService setup;
    private final NhifCallbackService callbacks;

    @Operation(summary = "OAuth2 token for NHIF's callbacks (client_credentials, form-encoded)")
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> token(@RequestParam(name = "client_id", required = false) String clientId,
                                   @RequestParam(name = "client_secret", required = false) String clientSecret,
                                   @RequestParam(name = "grant_type", required = false) String grantType,
                                   @RequestParam(name = "scope", required = false) String scope) {
        try {
            return ResponseEntity.ok(setup.issueToken(clientId, clientSecret, grantType, scope));
        } catch (BusinessRuleException e) {
            int status = "unsupported_grant_type".equals(e.getCode()) ? 400 : 401;
            return ResponseEntity.status(status).body(Map.of("error", e.getCode(), "error_description", e.getMessage()));
        }
    }

    @Operation(summary = "A callback from NHIF (registration, card application, payment, card issued or change request)")
    @PostMapping("/callback")
    public ResponseEntity<NhifCallbackService.Ack> callback(@RequestHeader(name = "Authorization", required = false) String authorization,
                                                            @RequestBody(required = false) JsonNode body) {
        return answer(authorization, NhifCallbackService.Kind.UNKNOWN, body);
    }

    @Operation(summary = "A callback from NHIF of one kind: registration, card-application, payment, card-issued or change-request")
    @PostMapping("/callback/{kind}")
    public ResponseEntity<NhifCallbackService.Ack> callbackOfKind(@RequestHeader(name = "Authorization", required = false) String authorization,
                                                                  @PathVariable String kind, @RequestBody(required = false) JsonNode body) {
        NhifCallbackService.Kind k;
        try {
            k = NhifCallbackService.Kind.valueOf(kind.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(new NhifCallbackService.Ack(404, "Unknown callback kind " + kind));
        }
        return answer(authorization, k, body);
    }

    private ResponseEntity<NhifCallbackService.Ack> answer(String authorization, NhifCallbackService.Kind kind, JsonNode body) {
        if (!setup.validToken(authorization)) {
            return ResponseEntity.status(401).body(new NhifCallbackService.Ack(401, "Missing, unknown or expired token: obtain one at /api/integrations/nhif/token"));
        }
        NhifCallbackService.Ack ack = callbacks.handle(kind, body);
        return ResponseEntity.status(ack.statusCode() == 200 ? 200 : 400).body(ack);
    }
}
