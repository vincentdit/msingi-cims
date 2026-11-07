package tz.co.msingi.cims.console.billing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.finance.aggregator.AggregatorProvider;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Where Msingi's own aggregator reports an institution's payment of a Msingi invoice: /api/integrations/msingi-collections/{aggregator}/{token}. */
@Tag(name = "Integrations — Msingi payment collection")
@RestController
@RequestMapping("/api/integrations/msingi-collections")
@RequiredArgsConstructor
public class MsingiCollectionNotificationController {

    private final MsingiPhonePaymentService payments;

    @Operation(summary = "Payment notification from Msingi's aggregator (Selcom, AzamPay, ClickPesa)")
    @PostMapping("/{provider:selcom|azampay|clickpesa|simulated}/{token}")
    public ResponseEntity<Map<String, Object>> notify(@PathVariable String provider, @PathVariable String token,
                                                      @RequestBody(required = false) String body, HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) headers.put(name, request.getHeader(name));
        MsingiPhonePaymentService.Outcome o = payments.notification(AggregatorProvider.valueOf(provider.toUpperCase(Locale.ROOT)), token, headers,
                body == null ? "" : body);
        Map<String, Object> answer = new HashMap<>();
        answer.put("status", o.status() == 200 ? "ACCEPTED" : "REJECTED");
        answer.put("message", o.message());
        answer.put("success", o.status() == 200);
        answer.put("result", o.status() == 200 ? "SUCCESS" : "FAIL");
        answer.put("resultcode", o.status() == 200 ? "000" : "403");
        return ResponseEntity.status(o.status()).body(answer);
    }
}
