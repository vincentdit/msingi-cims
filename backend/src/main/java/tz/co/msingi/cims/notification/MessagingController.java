package tz.co.msingi.cims.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.finance.web.GepgCallbackController;
import tz.co.msingi.cims.notification.MessagingDtos.CampaignDetail;
import tz.co.msingi.cims.notification.MessagingDtos.CampaignPreview;
import tz.co.msingi.cims.notification.MessagingDtos.CampaignRequest;
import tz.co.msingi.cims.notification.MessagingDtos.CampaignView;
import tz.co.msingi.cims.notification.MessagingDtos.MessageView;
import tz.co.msingi.cims.notification.MessagingDtos.Overview;
import tz.co.msingi.cims.notification.MessagingDtos.SettingsRequest;
import tz.co.msingi.cims.notification.MessagingDtos.TemplateRequest;
import tz.co.msingi.cims.notification.MessagingDtos.TemplateView;
import tz.co.msingi.cims.notification.MessagingDtos.TopupRequest;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/** Messaging (FR-COM-01 to 03): settings and SMS bundle, event templates, bulk messages, the message log and delivery reports. */
@Slf4j
@Tag(name = "Messaging")
@RestController
@RequiredArgsConstructor
public class MessagingController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','ADMISSIONS_OFFICER','BURSAR','DEPUTY_PRINCIPAL_ACADEMIC','AUDITOR','MANAGEMENT_VIEWER')";
    private static final String SEND = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','ADMISSIONS_OFFICER','BURSAR','DEPUTY_PRINCIPAL_ACADEMIC')";
    private static final String ADMIN = "hasRole('INSTITUTION_ADMIN')";
    private static final String BUNDLE = "hasAnyRole('INSTITUTION_ADMIN','BURSAR')";

    private final MessagingAdminService admin;
    private final CampaignService campaigns;
    private final MessageDispatcher dispatcher;
    private final TenantRepository tenants;
    private final ObjectMapper mapper;
    @Value("${cims.sms.callback-secret:change-me}")
    private String callbackSecret;

    @GetMapping("/api/messaging/overview")
    @PreAuthorize(READ)
    public Overview overview() {
        return admin.overview();
    }

    @PutMapping("/api/messaging/settings")
    @PreAuthorize(ADMIN)
    public Overview settings(@Valid @RequestBody SettingsRequest req) {
        return admin.updateSettings(req);
    }

    @Operation(summary = "Record an SMS bundle purchase")
    @PostMapping("/api/messaging/topups")
    @PreAuthorize(BUNDLE)
    public Overview topup(@Valid @RequestBody TopupRequest req) {
        return admin.topup(req);
    }

    @GetMapping("/api/messaging/templates")
    @PreAuthorize(READ)
    public List<TemplateView> templates() {
        return admin.templates();
    }

    @PutMapping("/api/messaging/templates/{code}")
    @PreAuthorize(ADMIN)
    public TemplateView saveTemplate(@PathVariable String code, @Valid @RequestBody TemplateRequest req) {
        return admin.saveTemplate(code, req);
    }

    @DeleteMapping("/api/messaging/templates/{code}")
    @PreAuthorize(ADMIN)
    public TemplateView resetTemplate(@PathVariable String code) {
        return admin.resetTemplate(code);
    }

    @Operation(summary = "Recipients, sample messages, SMS parts and cost of a bulk message before it is sent")
    @PostMapping("/api/messaging/campaigns/preview")
    @PreAuthorize(SEND)
    public CampaignPreview preview(@Valid @RequestBody CampaignRequest req) {
        return campaigns.preview(req);
    }

    @PostMapping("/api/messaging/campaigns")
    @PreAuthorize(SEND)
    public CampaignView send(@Valid @RequestBody CampaignRequest req) {
        return campaigns.send(req);
    }

    @GetMapping("/api/messaging/campaigns")
    @PreAuthorize(READ)
    public List<CampaignView> campaigns() {
        return campaigns.list();
    }

    @GetMapping("/api/messaging/campaigns/{id}")
    @PreAuthorize(READ)
    public CampaignDetail campaign(@PathVariable Long id) {
        return campaigns.get(id);
    }

    @PostMapping("/api/messaging/campaigns/{id}/resend-failed")
    @PreAuthorize(SEND)
    public CampaignDetail resendFailed(@PathVariable Long id) {
        return campaigns.resendFailed(id);
    }

    @GetMapping("/api/messaging/messages")
    @PreAuthorize(READ)
    public tz.co.msingi.cims.common.web.PageResponse<MessageView> log(@RequestParam(required = false) String status, @RequestParam(required = false) String kind,
                                 @RequestParam(required = false) String phone, @RequestParam(required = false) Long campaignId,
                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return tz.co.msingi.cims.common.web.PageResponse.of(admin.log(status, kind, phone, campaignId, page, size), m -> m);
    }

    /**
     * Delivery reports from the SMS aggregator. {@code reference} is the "tenantId-messageId" CIMS gave with the message.
     * Signed with HMAC-SHA256 over the raw body (header X-Sms-Signature).
     */
    @Operation(summary = "SMS delivery report callback")
    @PostMapping("/api/integrations/sms/delivery-reports")
    public ResponseEntity<Map<String, String>> deliveryReport(@RequestBody String body,
                                                              @RequestHeader(name = "X-Sms-Signature", required = false) String signature) throws Exception {
        if (signature == null || !MessageDigest.isEqual(GepgCallbackController.sign(body, callbackSecret).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "REJECTED", "reason", "invalid signature"));
        }
        SmsGateway.DeliveryReport r = mapper.readValue(body, SmsGateway.DeliveryReport.class);
        Long tenantId = null;
        try {
            tenantId = r.reference() == null ? null : Long.parseLong(r.reference().substring(0, r.reference().indexOf('-')));
        } catch (RuntimeException ignored) {
            // malformed reference
        }
        var tenant = tenantId == null ? java.util.Optional.<tz.co.msingi.cims.platform.tenancy.Tenant>empty() : tenants.findById(tenantId);
        if (tenant.isEmpty()) return ResponseEntity.badRequest().body(Map.of("status", "REJECTED", "reason", "unknown reference"));
        boolean known = TenantContext.callAs(tenant.get().toInfo(), () -> dispatcher.applyReportInNewTx(r));
        return known ? ResponseEntity.ok(Map.of("status", "ACCEPTED"))
                : ResponseEntity.badRequest().body(Map.of("status", "REJECTED", "reason", "unknown message"));
    }
}
