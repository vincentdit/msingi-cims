package tz.co.msingi.cims.admission.advert;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.admission.advert.ProgrammeAdvertService.Advert;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.branding.InstitutionPhoto;
import tz.co.msingi.cims.platform.branding.InstitutionPhotoService;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static tz.co.msingi.cims.common.document.HtmlTemplate.escape;

/**
 * Programmes on offer, for the public: the programme pages of the portal, the short link people share (/p/{code}, which shows a
 * preview card with the poster on Facebook and WhatsApp and then opens the programme page), and the advert posters.
 */
@Tag(name = "Public portal — programmes")
@RestController
@RequiredArgsConstructor
public class ProgrammeAdvertController {

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    private final ProgrammeAdvertService adverts;
    private final TenantRepository tenants;
    private final InstitutionPhotoService photos;

    @Operation(summary = "Programmes open for applications now or soon, with fees, requirements and selling points")
    @GetMapping("/api/public/programmes")
    public List<Advert> list() {
        return adverts.list();
    }

    @Operation(summary = "One programme on offer, by its code")
    @GetMapping("/api/public/programmes/{code}")
    public Advert one(@PathVariable String code) {
        return adverts.one(code);
    }

    @Operation(summary = "The programme's advert poster: format square or portrait, lang en or sw, as PNG")
    @GetMapping("/api/public/programmes/{code}/poster.png")
    public ResponseEntity<byte[]> png(@PathVariable String code, @RequestParam(defaultValue = "square") String format,
                                      @RequestParam(defaultValue = "en") String lang, @RequestParam(defaultValue = "false") boolean download,
                                      HttpServletRequest req) {
        Advert a = adverts.one(code);
        byte[] img = PosterRenderer.png(a, institution(), link(req, a), format(format), "sw".equalsIgnoreCase(lang));
        return file(img, MediaType.IMAGE_PNG, name(a, format, lang, "png"), download);
    }

    @Operation(summary = "The programme's advert poster as PDF (for printing)")
    @GetMapping("/api/public/programmes/{code}/poster.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String code, @RequestParam(defaultValue = "portrait") String format,
                                      @RequestParam(defaultValue = "en") String lang, HttpServletRequest req) {
        Advert a = adverts.one(code);
        byte[] pdf = PosterRenderer.pdf(a, institution(), link(req, a), format(format), "sw".equalsIgnoreCase(lang));
        return file(pdf, MediaType.APPLICATION_PDF, name(a, format, lang, "pdf"), true);
    }

    /**
     * The link people share. Facebook, WhatsApp and others read its preview (title, description, poster); a person is sent on
     * to the programme page at once. A programme that is not on offer goes to the portal's home page.
     */
    @GetMapping(value = "/p/{code}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> share(@PathVariable String code, @RequestParam(required = false) String src,
                                        @RequestParam(defaultValue = "en") String lang, HttpServletRequest req) {
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        String base = base(req);
        Advert a;
        try {
            a = adverts.one(code);
        } catch (NotFoundException e) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(redirectPage(t.getName(), t.getName(), "", null, base + "/", base + "/"));
        }
        boolean sw = "sw".equalsIgnoreCase(lang);
        StringBuilder d = new StringBuilder();
        if (a.fee() != null && a.fee().total() != null && a.fee().total().signum() > 0) {
            d.append(sw ? "Ada TZS " : "Fee TZS ").append(PosterRenderer.tzs(a.fee().total())).append(sw ? " kwa mwaka" : " a year");
            if (a.fee().instalments() > 1) d.append(sw ? ", awamu " : ", in ").append(a.fee().instalments()).append(sw ? "" : " instalments");
            d.append(". ");
        }
        String req2 = sw ? a.requirementsSw() : a.requirementsEn();
        if (req2 != null && !req2.isBlank()) d.append(sw ? "Sifa: " : "Entry: ").append(req2).append(' ');
        if (!a.highlights().isEmpty()) d.append(String.join(" · ", a.highlights())).append(". ");
        d.append(sw ? "Mwisho wa maombi " : "Applications close ")
                .append(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).format(a.closesAt().atZoneSameInstant(TZ))).append('.');
        String page = base + "/programmes/" + java.net.URLEncoder.encode(a.code(), java.nio.charset.StandardCharsets.UTF_8) + "?lang=" + (sw ? "sw" : "en")
                + (src == null || src.isBlank() ? "" : "&src=" + java.net.URLEncoder.encode(src, java.nio.charset.StandardCharsets.UTF_8));
        String image = base + "/api/public/programmes/" + java.net.URLEncoder.encode(a.code(), java.nio.charset.StandardCharsets.UTF_8)
                + "/poster.png?format=square&lang=" + (sw ? "sw" : "en");
        String title = a.name() + " — " + t.getName();
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)))
                .body(redirectPage(title, t.getName(), d.toString(), image, base + ProgrammeAdvertService.path(a.code()) + (sw ? "?lang=sw" : ""), page));
    }

    private static String redirectPage(String title, String site, String description, String image, String url, String to) {
        String img = image == null ? "" : "<meta property=\"og:image\" content=\"" + escape(image) + "\" />"
                + "<meta property=\"og:image:width\" content=\"1080\" /><meta property=\"og:image:height\" content=\"1080\" />"
                + "<meta name=\"twitter:image\" content=\"" + escape(image) + "\" />";
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\" /><title>" + escape(title) + "</title>"
                + "<meta name=\"description\" content=\"" + escape(description) + "\" />"
                + "<meta property=\"og:type\" content=\"website\" /><meta property=\"og:site_name\" content=\"" + escape(site) + "\" />"
                + "<meta property=\"og:title\" content=\"" + escape(title) + "\" />"
                + "<meta property=\"og:description\" content=\"" + escape(description) + "\" />"
                + "<meta property=\"og:url\" content=\"" + escape(url) + "\" />" + img
                + "<meta name=\"twitter:card\" content=\"summary_large_image\" />"
                + "<meta http-equiv=\"refresh\" content=\"0; url=" + escape(to) + "\" />"
                + "</head><body><p><a href=\"" + escape(to) + "\">" + escape(title) + "</a></p>"
                + "<script>location.replace(" + jsString(to) + ");</script></body></html>";
    }

    private static String jsString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\"";
    }

    /** The institution as posters show it: name, colours, logo, phones, email, website and social pages. */
    PosterRenderer.Institution institution() {
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        Map<String, Object> b = t.getBranding();
        String colour = b.get("primaryColor") instanceof String c && HEX.matcher(c).matches() ? c : "#1F4E8C";
        String accent = b.get("accentColor") instanceof String c && HEX.matcher(c).matches() && !c.equalsIgnoreCase(colour) ? c : "#F2B705";
        byte[] logo = null;
        String type = null;
        if (photos.view(InstitutionPhoto.Kind.LOGO).isPresent()) {
            InstitutionPhoto p = photos.image(InstitutionPhoto.Kind.LOGO);
            logo = p.getData();
            type = p.getContentType();
        }
        List<String> phones = new ArrayList<>();
        if (t.getContactPhone() != null && !t.getContactPhone().isBlank()) phones.add(t.getContactPhone());
        if (b.get("phones") instanceof List<?> l) for (Object o : l) if (o instanceof String s && phones.size() < 3) phones.add(s);
        String email = b.get("admissionsEmail") instanceof String s && !s.isBlank() ? s : t.getContactEmail();
        List<String[]> social = new ArrayList<>();
        for (String[] n : new String[][]{{"facebook", "Facebook"}, {"instagram", "Instagram"}, {"youtube", "YouTube"}, {"tiktok", "TikTok"}, {"x", "X"}}) {
            if (b.get(n[0]) instanceof String u && !u.isBlank() && social.size() < 3) social.add(new String[]{n[1], handle(u)});
        }
        if (b.get("whatsapp") instanceof String w && !w.isBlank()) social.add(0, new String[]{"WhatsApp", w});
        return new PosterRenderer.Institution(t.getName(), t.getShortName(), colour, accent, logo, type, phones, email,
                b.get("website") instanceof String w ? w : null, social);
    }

    /** "https://facebook.com/chuoafyankinga" → "chuoafyankinga". */
    static String handle(String url) {
        String s = url.replaceFirst("(?i)^https?://(www\\.)?[^/]+/", "").replaceFirst("/+$", "");
        return s.isBlank() ? url : s.replaceFirst("^@?", "@");
    }

    static String base(HttpServletRequest req) {
        String proto = req.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isBlank()) proto = req.getScheme();
        String host = req.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) host = req.getHeader("Host");
        if (host == null || host.isBlank()) host = req.getServerName() + ":" + req.getServerPort();
        return proto.split(",")[0].trim() + "://" + host.split(",")[0].trim();
    }

    private static String link(HttpServletRequest req, Advert a) {
        return base(req) + a.path();
    }

    private static PosterRenderer.Format format(String f) {
        return "portrait".equalsIgnoreCase(f) ? PosterRenderer.Format.PORTRAIT : PosterRenderer.Format.SQUARE;
    }

    private static String name(Advert a, String format, String lang, String ext) {
        return "advert-" + a.code().replaceAll("[^A-Za-z0-9-]", "-") + "-" + format.toLowerCase(Locale.ROOT) + "-" + lang.toLowerCase(Locale.ROOT) + "." + ext;
    }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String name, boolean download) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic());
        if (download) b.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString());
        return b.body(body);
    }
}
