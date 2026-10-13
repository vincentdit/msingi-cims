package tz.co.msingi.cims.admission.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantQualificationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.ApplicantQualification;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.domain.ApplicationChoice;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ApplicantRow;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ApplicationDetailView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ChoiceView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.HistoryEntry;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ProfileView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.QualificationView;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.document.HtmlTemplate;
import tz.co.msingi.cims.common.document.PdfRenderer;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.web.PageResponse;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Admissions Office's applicant search, list export and printable application file (as NOAS: search applicants, view
 * submissions, print files). Exports and printed files are recorded in the audit trail, as they carry personal data.
 */
@Service
@RequiredArgsConstructor
public class ApplicantFileService {

    public static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    public static final int MAX_EXPORT = 20_000;
    private static final HtmlTemplate TEMPLATE = HtmlTemplate.classpath("templates/application-file.html");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final ApplicationRepository applications;
    private final ApplicantQualificationRepository qualifications;
    private final ApplicationService applicationService;
    private final TenantRepository tenants;
    private final AuditService audit;

    /** What the office searches by. */
    public record Filter(Long windowId, Application.Status status, String sex, Boolean feePaid, LocalDate from, LocalDate to,
                         Long programmeId, boolean firstChoice, boolean checksPending, String q) {
    }

    @Transactional(readOnly = true)
    public PageResponse<ApplicantRow> search(Filter f, int page, int size, String sort) {
        Sort order = switch (sort == null ? "" : sort) {
            case "name" -> Sort.by("applicant.surname", "applicant.firstName");
            case "submitted" -> Sort.by(Sort.Direction.DESC, "submittedAt");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
        Page<Application> p = find(f, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), order));
        return PageResponse.of(p, this::row);
    }

    /** The search results as CSV (UTF-8 with BOM for Excel), recorded in the audit trail. */
    @Transactional
    public byte[] export(Filter f) {
        Page<Application> p = find(f, PageRequest.of(0, MAX_EXPORT + 1, Sort.by("applicant.surname", "applicant.firstName")));
        if (p.getTotalElements() > MAX_EXPORT) {
            throw new BusinessRuleException("EXPORT_TOO_LARGE", "These filters match " + p.getTotalElements() + " applications; narrow them to at most "
                    + MAX_EXPORT + ".");
        }
        StringBuilder sb = new StringBuilder("﻿").append(Csv.line("Reference", "Surname", "First name", "Middle name", "Sex", "Index number",
                "Phone", "Email", "Region", "District", "Window", "Status", "Fee paid", "First choice", "Second choice", "Third choice",
                "Declared qualifications to check", "Submitted"));
        for (Application a : p.getContent()) {
            var ap = a.getApplicant();
            List<String> ch = choices(a).stream().map(c -> c.getWindowProgramme().getProgramme().getCode()).toList();
            sb.append(Csv.line(a.getReferenceNo(), ap.getSurname(), ap.getFirstName(), ap.getMiddleName(), ap.getSex(), index(ap.getNectaIndexNumber(),
                            ap.getNectaExamYear()), ap.getPhone(), ap.getEmail(), ap.getRegion(), ap.getDistrict(), a.getWindow().getName(),
                    a.getStatus().name(), a.isFeePaid() ? "Yes" : "No", ch.size() > 0 ? ch.get(0) : "", ch.size() > 1 ? ch.get(1) : "",
                    ch.size() > 2 ? ch.get(2) : "", pending(ap.getId()), a.getSubmittedAt() == null ? "" : WHEN.format(a.getSubmittedAt().atZoneSameInstant(ZONE))));
        }
        audit.record("APPLICANT_LIST_EXPORTED", "Application", null, describe(f, p.getTotalElements()));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The printable application file (PDF), recorded in the application's history. */
    @Transactional
    public byte[] file(Long id) {
        ApplicationDetailView d = applicationService.detail(id);
        audit.record("APPLICATION_FILE_PRINTED", "Application", id, Map.of("reference", String.valueOf(d.application().referenceNo())));
        return PdfRenderer.render(TEMPLATE.render(values(d), markup(d)), "application " + d.application().referenceNo());
    }

    // ------------------------------------------------------------------ helpers

    private Page<Application> find(Filter f, PageRequest paging) {
        if (f.from() != null && f.to() != null && f.from().isAfter(f.to())) {
            throw new BusinessRuleException("INVALID_RANGE", "The start date is after the end date.");
        }
        String sex = f.sex() == null || f.sex().isBlank() ? null : f.sex().trim().toUpperCase(Locale.ROOT);
        if (sex != null && !sex.matches("[FM]")) throw new BusinessRuleException("INVALID_FILTER", "Sex is F or M");
        return applications.find(f.windowId(), f.status(), sex, f.feePaid(),
                f.from() == null ? null : f.from().atStartOfDay(ZONE).toOffsetDateTime(),
                f.to() == null ? null : f.to().plusDays(1).atStartOfDay(ZONE).toOffsetDateTime(),
                f.programmeId(), f.firstChoice(), f.checksPending(), f.q() == null || f.q().isBlank() ? null : f.q().trim(), paging);
    }

    private ApplicantRow row(Application a) {
        var ap = a.getApplicant();
        List<ApplicationChoice> ch = choices(a);
        String first = ch.isEmpty() ? null : ch.get(0).getWindowProgramme().getProgramme().getCode();
        return new ApplicantRow(a.getId(), a.getReferenceNo(), a.getStatus().name(), ap.getId(), ap.fullName(), ap.getSex(), ap.getPhone(),
                index(ap.getNectaIndexNumber(), ap.getNectaExamYear()), ap.getRegion(), a.getWindow().getId(), a.getWindow().getName(), a.isFeePaid(),
                first, ch.size(), pending(ap.getId()), a.getSubmittedAt(), a.getCreatedAt(), a.getAmendedAt());
    }

    private static List<ApplicationChoice> choices(Application a) {
        return a.getChoices().stream().sorted(Comparator.comparingInt(ApplicationChoice::getChoiceOrder)).toList();
    }

    private long pending(Long applicantId) {
        return qualifications.findByApplicantIdOrderByIdAsc(applicantId).stream()
                .filter(q -> q.getSource() == ApplicantQualification.Source.MANUAL && q.getCheckStatus() == ApplicantQualification.CheckStatus.PENDING).count();
    }

    private static String index(String number, Short year) {
        return number == null ? null : year == null ? number : number + "/" + year;
    }

    private static Map<String, Object> describe(Filter f, long rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        if (f.windowId() != null) m.put("windowId", f.windowId());
        if (f.status() != null) m.put("status", f.status().name());
        if (f.programmeId() != null) m.put("programmeId", f.programmeId());
        if (f.sex() != null) m.put("sex", f.sex());
        if (f.feePaid() != null) m.put("feePaid", f.feePaid());
        if (f.checksPending()) m.put("checksPending", true);
        if (f.q() != null && !f.q().isBlank()) m.put("search", f.q());
        return m;
    }

    private Map<String, String> values(ApplicationDetailView d) {
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        var a = d.application();
        ProfileView p = d.applicant();
        Map<String, String> v = new HashMap<>();
        v.put("institutionName", t.getName());
        v.put("institutionShortName", t.getShortName() == null || t.getShortName().isBlank()
                ? Stream.of(t.getName().split("\\s+")).filter(w -> !w.isEmpty()).limit(3).map(w -> w.substring(0, 1)).collect(Collectors.joining()).toUpperCase(Locale.ROOT)
                : t.getShortName().length() > 5 ? t.getShortName().substring(0, 5) : t.getShortName());
        v.put("contactLine", Stream.of(t.getContactPhone(), t.getContactEmail()).filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" · ")));
        Object colour = t.getBranding().get("primaryColor");
        v.put("primaryColor", colour instanceof String c && HEX_COLOUR.matcher(c).matches() ? c : "#1F4E8C");
        v.put("referenceNo", a.referenceNo() == null ? "not yet submitted" : a.referenceNo());
        v.put("applicantName", p.fullName());
        v.put("windowName", a.windowName());
        v.put("academicYear", a.academicYear());
        v.put("status", a.status().replace('_', ' '));
        v.put("submittedLine", (a.submittedAt() == null ? "Not yet submitted" : "Submitted " + WHEN.format(a.submittedAt().atZoneSameInstant(ZONE)))
                + (a.amendedAt() == null ? "" : " · last changed " + WHEN.format(a.amendedAt().atZoneSameInstant(ZONE))));
        v.put("printedAt", WHEN.format(OffsetDateTime.now(ZONE)));
        v.put("printedBy", CurrentUser.name());
        v.put("indexNumber", nz(index(p.nectaIndexNumber(), p.nectaExamYear())));
        v.put("sex", "F".equals(p.sex()) ? "Female" : "M".equals(p.sex()) ? "Male" : "—");
        v.put("dateOfBirth", p.dateOfBirth() == null ? "—" : DAY.format(p.dateOfBirth()));
        v.put("nationality", nz(p.nationality()));
        v.put("phone", nz(p.phone()));
        v.put("email", nz(p.email()));
        v.put("address", join(p.postalAddress(), p.street(), p.ward(), p.district(), p.region()));
        v.put("disability", p.disability() == null || p.disability().isBlank() ? "None" : p.disability());
        v.put("kinName", nz(p.nextOfKinName()));
        v.put("kinRelation", nz(p.nextOfKinRelation()));
        v.put("kinPhone", nz(p.nextOfKinPhone()));
        v.put("kinEmail", nz(p.nextOfKinEmail()));
        v.put("kinAddress", join(p.nextOfKinAddress(), p.nextOfKinStreet(), p.nextOfKinWard(), p.nextOfKinDistrict(), p.nextOfKinRegion()));
        v.put("primarySchool", nz(p.primarySchool()) + (p.primarySchoolCode() == null ? "" : " (" + p.primarySchoolCode() + ")"));
        v.put("olevelSchool", nz(p.olevelSchool()));
        v.put("alevelSchool", nz(p.alevelSchool()));
        v.put("feeAmount", "TZS " + tzs(d.fee().amount()));
        v.put("feePaid", d.fee().feePaid() ? "Yes" : "No");
        v.put("invoiceNo", nz(d.fee().invoiceNo()));
        v.put("controlNo", nz(d.fee().controlNo()));
        return v;
    }

    private static Map<String, String> markup(ApplicationDetailView d) {
        Map<String, String> m = new HashMap<>();
        StringBuilder q = new StringBuilder();
        if (d.qualifications().isEmpty()) q.append("<p class=\"muted\">No results or qualifications recorded.</p>");
        for (QualificationView x : d.qualifications()) {
            String title = switch (x.type()) {
                case "CSEE" -> "Form Four (CSEE)";
                case "ACSEE" -> "Form Six (ACSEE)";
                case "NTA" -> "NTA award" + (x.awardLevel() == null ? "" : " level " + x.awardLevel());
                case "NVA" -> "NVA award" + (x.awardLevel() == null ? "" : " level " + x.awardLevel());
                default -> "Other award";
            };
            String check = "MANUAL".equals(x.source())
                    ? switch (Objects.toString(x.checkStatus(), "PENDING")) {
                        case "VERIFIED" -> "<span class=\"ok\">Verified by the office</span>";
                        case "REJECTED" -> "<span class=\"no\">Not accepted</span>" + (x.checkNote() == null ? "" : ": " + e(x.checkNote()));
                        default -> "<span class=\"wait\">Waiting to be checked</span>";
                    }
                    : "<span class=\"ok\">Verified at " + e(x.source()) + "</span>";
            q.append("<table class=\"lines\"><tr><th colspan=\"2\">").append(e(title))
                    .append(x.indexNumber() == null ? "" : " · " + e(x.indexNumber()) + (x.examYear() == null ? "" : "/" + x.examYear()))
                    .append(" · ").append(check).append("</th></tr>");
            String facts = Stream.of(x.awardName(), x.institution(), x.centreName(), x.division() == null ? null : "Division " + x.division(),
                    x.points() == null ? null : x.points() + " points", x.gpa() == null ? null : "GPA " + x.gpa().toPlainString(), x.classAward())
                    .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" · "));
            if (!facts.isEmpty()) q.append("<tr><td colspan=\"2\">").append(e(facts)).append("</td></tr>");
            if (x.subjects() != null && !x.subjects().isEmpty()) {
                q.append("<tr><td colspan=\"2\">").append(x.subjects().stream().map(s -> e(s.name()) + " <strong>" + e(s.grade()) + "</strong>")
                        .collect(Collectors.joining(" · "))).append("</td></tr>");
            }
            q.append("</table>");
        }
        m.put("qualifications", q.toString());

        StringBuilder c = new StringBuilder();
        List<ChoiceView> choices = d.application().choices();
        if (choices.isEmpty()) c.append("<tr><td colspan=\"4\" class=\"muted\">No programmes chosen.</td></tr>");
        for (ChoiceView x : choices) {
            c.append("<tr><td>").append(x.order()).append("</td><td>").append(e(x.programmeCode() + " · " + x.programmeName()))
                    .append("<br/><span class=\"muted small\">").append(e(x.awardName())).append("</span></td><td>")
                    .append(x.eligible() ? "<span class=\"ok\">Eligible</span>" : "<span class=\"no\">Not eligible</span>").append("</td><td>")
                    .append(x.eligibility() == null ? "" : e(x.eligibility().eligible() ? nz(x.eligibility().matchedPathway()) : nz(x.eligibility().summary())))
                    .append("</td></tr>");
        }
        m.put("choices", c.toString());
        String selected = d.application().selectedProgramme();
        m.put("outcome", selected == null ? "" : "<p><strong>Selected for:</strong> " + e(selected) + "</p>");

        StringBuilder h = new StringBuilder();
        for (HistoryEntry x : d.history()) {
            h.append("<tr><td>").append(e(WHEN.format(x.at().atZoneSameInstant(ZONE)))).append("</td><td>").append(e(x.label()))
                    .append("</td><td>").append(e(nz(x.by()))).append("</td></tr>");
        }
        if (d.history().isEmpty()) h.append("<tr><td colspan=\"3\" class=\"muted\">No history recorded.</td></tr>");
        m.put("history", h.toString());
        return m;
    }

    private static String e(String s) {
        return HtmlTemplate.escape(s);
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String join(String... parts) {
        String s = Stream.of(parts).filter(x -> x != null && !x.isBlank()).collect(Collectors.joining(", "));
        return s.isEmpty() ? "—" : s;
    }

    private static String tzs(BigDecimal b) {
        return b == null ? "0" : String.format(Locale.ENGLISH, "%,.0f", b);
    }
}
