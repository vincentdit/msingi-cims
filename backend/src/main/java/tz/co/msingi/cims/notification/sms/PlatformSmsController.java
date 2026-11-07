package tz.co.msingi.cims.notification.sms;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The Msingi console's SMS page: the provider account Msingi sends through, and the institutions' sender names. */
@Tag(name = "Platform: SMS")
@RestController
@RequestMapping("/api/platform/sms")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformSmsController {

    private final SmsProviderService providers;
    private final SenderNameService senderNames;

    @GetMapping("/provider")
    public SmsProviderService.View provider() {
        return providers.view();
    }

    @PutMapping("/provider")
    public SmsProviderService.View save(@Valid @RequestBody SmsProviderService.SaveRequest req) {
        return providers.save(req);
    }

    @Operation(summary = "Prove the credentials by reading the account balance")
    @PostMapping("/provider/test")
    public SmsProviderService.View test() {
        return providers.test();
    }

    @Operation(summary = "Send one test message under Msingi's sender name")
    @PostMapping("/provider/test-sms")
    public SmsProviderService.View testSms(@Valid @RequestBody SmsProviderService.TestSms req) {
        return providers.sendTest(req);
    }

    @Operation(summary = "A new delivery-report address (the old one stops working)")
    @PostMapping("/provider/notify-address")
    public SmsProviderService.View notifyAddress() {
        return providers.newNotifyAddress();
    }

    @GetMapping("/sender-names")
    public List<SenderNameService.SenderName> senderNames() {
        return senderNames.all();
    }

    @PutMapping("/sender-names/{id}")
    public SenderNameService.SenderName decide(@PathVariable Long id, @Valid @RequestBody SenderNameService.Decision d) {
        return senderNames.decide(id, d);
    }
}
