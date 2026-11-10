package tz.co.msingi.cims.admission.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowView;
import tz.co.msingi.cims.admission.service.AdmissionWindowService;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.branding.InstitutionPhoto;
import tz.co.msingi.cims.platform.branding.InstitutionPhotoService;
import tz.co.msingi.cims.platform.branding.InstitutionPhotoService.PhotoView;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.util.List;
import java.util.Map;

/** Unauthenticated endpoints for the branded admission portal. */
@Tag(name = "Public portal")
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final TenantRepository tenants;
    private final AdmissionWindowService windows;
    private final InstitutionPhotoService photos;
    private final tz.co.msingi.cims.finance.collection.PaymentChannelService payments;
    private final tz.co.msingi.cims.platform.privacy.SecuritySettingsService security;
    private final tz.co.msingi.cims.finance.aggregator.CollectionAccountService mobileMoney;

    @org.springframework.beans.factory.annotation.Value("${cims.privacy.notice-version:2026-10}")
    private String privacyNoticeVersion;

    /** What the privacy notice shown at sign-up needs from the institution: its name and contacts, and how long applications are kept. */
    public record PrivacyNoticeView(String version, String institution, String contactEmail, String contactPhone, int applicantRetentionMonths) {
    }

    public record InstitutionView(String code, String name, String shortName, String nactvetRegNo, String contactEmail,
                                  String contactPhone, Map<String, Object> branding, PhotoView campusPhoto, PhotoView logo,
                                  tz.co.msingi.cims.finance.collection.PaymentChannelService.PublicPayment payment,
                                  tz.co.msingi.cims.platform.privacy.SecuritySettingsService.IdleMinutes idleMinutes,
                                  boolean paymentSimulation) {
    }

    /** Testing period (CIMS_ALLOW_PAYMENT_SIMULATION): the portals show their Simulate payment buttons. */
    @org.springframework.beans.factory.annotation.Value("${cims.payments.allow-simulation:false}")
    private boolean paymentSimulation;

    @Operation(summary = "Institution name and branding for the portal")
    @GetMapping("/institution")
    public InstitutionView institution() {
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Institution not found"));
        return new InstitutionView(t.getCode(), t.getName(), t.getShortName(), t.getNactvetRegNo(), t.getContactEmail(),
                t.getContactPhone(), t.getBranding(), photos.view(InstitutionPhoto.Kind.CAMPUS).orElse(null), photos.view(InstitutionPhoto.Kind.LOGO).orElse(null),
                payments.forPayers().withPhonePrompt(mobileMoney.phonePrompt()), security.idle(), paymentSimulation);
    }

    /**
     * The web app manifest for this institution, so the student app installs on a phone under the institution's own name and colour.
     * index.html points at the generic /manifest.webmanifest; the portal swaps in this one once it knows the institution.
     */
    @Operation(summary = "Installable app manifest with the institution's name and colour")
    @GetMapping(value = "/manifest.webmanifest", produces = "application/manifest+json")
    public org.springframework.http.ResponseEntity<Map<String, Object>> manifest() {
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Institution not found"));
        String colour = t.getBranding() != null && t.getBranding().get("primaryColor") instanceof String c && c.matches("#[0-9A-Fa-f]{6}") ? c : "#1F4E8C";
        String shortName = t.getShortName() != null && !t.getShortName().isBlank() ? t.getShortName() : t.getName();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", "/student");
        m.put("name", shortName + " Student App");
        m.put("short_name", shortName.length() > 12 ? shortName.substring(0, 12) : shortName);
        m.put("description", "Fees, results, timetable and notices from " + t.getName() + ".");
        m.put("start_url", "/student");
        m.put("scope", "/");
        m.put("display", "standalone");
        m.put("orientation", "portrait");
        m.put("background_color", "#F6F7F9");
        m.put("theme_color", colour);
        m.put("lang", "en");
        m.put("icons", List.of(
                Map.of("src", "/icon-192.png", "sizes", "192x192", "type", "image/png"),
                Map.of("src", "/icon-512.png", "sizes", "512x512", "type", "image/png"),
                Map.of("src", "/icon-maskable-512.png", "sizes", "512x512", "type", "image/png", "purpose", "maskable")));
        m.put("shortcuts", List.of(
                Map.of("name", "Fees", "url", "/student/fees"),
                Map.of("name", "Results", "url", "/student/results"),
                Map.of("name", "Timetable", "url", "/student/timetable")));
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(1)))
                .contentType(org.springframework.http.MediaType.parseMediaType("application/manifest+json")).body(m);
    }

    @Operation(summary = "The facts the applicants' privacy notice shows: version, institution, contacts and retention of applications")
    @GetMapping("/privacy-notice")
    public PrivacyNoticeView privacyNotice() {
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Institution not found"));
        return new PrivacyNoticeView(privacyNoticeVersion, t.getName(), t.getContactEmail(), t.getContactPhone(), security.get().retentionApplicantMonths());
    }

    @Operation(summary = "Admission windows open now, with programmes and entry requirements")
    @GetMapping("/windows")
    public List<WindowView> openWindows() {
        return windows.openWindows();
    }

    @Operation(summary = "Published admission windows that open later")
    @GetMapping("/windows/upcoming")
    public List<WindowView> upcomingWindows() {
        return windows.upcomingWindows();
    }
}
