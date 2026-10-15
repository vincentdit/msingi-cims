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

    public record InstitutionView(String code, String name, String shortName, String nactvetRegNo, String contactEmail,
                                  String contactPhone, Map<String, Object> branding, PhotoView campusPhoto,
                                  tz.co.msingi.cims.finance.collection.PaymentChannelService.PublicPayment payment,
                                  tz.co.msingi.cims.platform.privacy.SecuritySettingsService.IdleMinutes idleMinutes) {
    }

    @Operation(summary = "Institution name and branding for the portal")
    @GetMapping("/institution")
    public InstitutionView institution() {
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Institution not found"));
        return new InstitutionView(t.getCode(), t.getName(), t.getShortName(), t.getNactvetRegNo(), t.getContactEmail(),
                t.getContactPhone(), t.getBranding(), photos.view(InstitutionPhoto.Kind.CAMPUS).orElse(null), payments.forPayers(), security.idle());
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
