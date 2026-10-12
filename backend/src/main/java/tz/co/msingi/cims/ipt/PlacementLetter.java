package tz.co.msingi.cims.ipt;

import tz.co.msingi.cims.common.document.HtmlTemplate;
import tz.co.msingi.cims.common.document.PdfRenderer;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.registry.Student;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Renders the IPT placement letter to the organisation (BP-13 step 1). */
public final class PlacementLetter {

    private static final HtmlTemplate TEMPLATE = HtmlTemplate.classpath("templates/ipt-letter.html");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    public record Download(String filename, byte[] content) {
    }

    private PlacementLetter() {
    }

    static Download render(Tenant t, IptPlacement pl, Student s, String institutionSupervisor) {
        Map<String, String> v = new HashMap<>();
        Map<String, Object> branding = t.getBranding() == null ? Map.of() : t.getBranding();
        String colour = String.valueOf(branding.getOrDefault("primaryColor", ""));
        v.put("primaryColor", HEX_COLOUR.matcher(colour).matches() ? colour : "#1F4E8C");
        String shortName = t.getShortName() == null || t.getShortName().isBlank()
                ? Stream.of(t.getName().split("\\s+")).filter(w -> !w.isEmpty()).map(w -> w.substring(0, 1)).collect(Collectors.joining()) : t.getShortName();
        v.put("shortName", shortName.length() > 5 ? shortName.substring(0, 5) : shortName);
        v.put("institutionName", t.getName());
        v.put("nactvetNo", nz(t.getNactvetRegNo()));
        v.put("contactLine", Stream.of(t.getContactPhone(), t.getContactEmail()).filter(x -> x != null && !x.isBlank()).collect(Collectors.joining(" / ")));
        PlacementOrganisation o = pl.getOrganisation();
        v.put("contactName", o.getContactName() == null ? "The Human Resources Manager" : o.getContactName());
        v.put("contactTitle", nz(o.getContactTitle()));
        v.put("organisation", o.getName());
        v.put("address", nz(o.getAddress()) + (o.getRegion() == null ? "" : (o.getAddress() == null ? "" : ", ") + o.getRegion()));
        v.put("salutation", o.getContactName() == null ? "Sir/Madam" : o.getContactName());
        v.put("letterNo", pl.getLetterNo());
        v.put("issuedOn", DATE.format(pl.getLetterIssuedAt() == null ? LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"))
                : pl.getLetterIssuedAt().atZoneSameInstant(ZoneId.of("Africa/Dar_es_Salaam")).toLocalDate()));
        v.put("studentName", s.fullName());
        v.put("registrationNo", s.getRegistrationNo());
        v.put("programme", s.getProgramme().getName());
        v.put("startsOn", DATE.format(pl.getStartsOn()));
        v.put("endsOn", DATE.format(pl.getEndsOn()));
        v.put("unit", pl.getUnit() == null ? "To be assigned by the organisation" : pl.getUnit());
        v.put("workplaceSupervisor", pl.getWorkplaceSupervisorName() == null ? "To be assigned by the organisation"
                : pl.getWorkplaceSupervisorName() + (pl.getWorkplaceSupervisorTitle() == null ? "" : ", " + pl.getWorkplaceSupervisorTitle()));
        v.put("institutionSupervisor", institutionSupervisor == null ? "To be assigned" : institutionSupervisor);
        byte[] pdf = PdfRenderer.render(TEMPLATE.render(v, Map.of()), "placement letter " + pl.getLetterNo());
        return new Download("placement-letter-" + pl.getLetterNo().replaceAll("[^A-Za-z0-9]+", "-") + ".pdf", pdf);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
