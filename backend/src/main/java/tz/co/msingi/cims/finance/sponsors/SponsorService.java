package tz.co.msingi.cims.finance.sponsors;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.billing.AnnualBillingService;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.PaymentAllocationRepository;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.PaymentRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.domain.InvoiceLine;
import tz.co.msingi.cims.finance.domain.Payment;
import tz.co.msingi.cims.finance.domain.PaymentAllocation;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.finance.service.PaymentPostedEvent;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos.ImportError;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos.ImportResult;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos.InvoiceRunResult;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos.RemittanceRequest;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos.SponsorDetail;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos.SponsorInvoice;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos.SponsorRequest;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos.SponsorRow;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos.SponsorshipRequest;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos.SponsorshipView;
import tz.co.msingi.cims.finance.sponsors.SponsorDtos.YearOption;
import tz.co.msingi.cims.finance.sponsors.SponsorRepositories.SponsorRepository;
import tz.co.msingi.cims.finance.sponsors.SponsorRepositories.SponsorshipRepository;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sponsorship (BP-17 steps 1-2, FR-FIN-09): sponsors such as HESLB, the students they sponsor and the share of each student's annual
 * fees they pay; a HESLB-style beneficiary list imported from CSV; sponsor invoices for the sponsors' shares (one line per student,
 * payable by GePG control number or recorded as a bank remittance); and each payment from a sponsor moved onto the students'
 * invoices as it arrives. Sponsor statements for the bursary and for the sponsor's own officer.
 */
@Service
@RequiredArgsConstructor
public class SponsorService {

    public static final String PURPOSE = "SPONSOR_FEES";
    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final Set<Invoice.Status> VOID = EnumSet.of(Invoice.Status.CANCELLED, Invoice.Status.CREDITED);
    private static final Role[] FINANCE = {Role.INSTITUTION_ADMIN, Role.BURSAR, Role.ACCOUNTANT, Role.MANAGEMENT_VIEWER, Role.AUDITOR};
    private static final Role[] MANAGERS = {Role.INSTITUTION_ADMIN, Role.BURSAR, Role.ACCOUNTANT};

    private final SponsorRepository sponsors;
    private final SponsorshipRepository sponsorships;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final StudentRepository students;
    private final AcademicYearRepository years;
    private final UserAccountRepository users;
    private final BillingService billing;
    private final Messaging messaging;
    private final AuditService audit;

    // ------------------------------------------------------------------ sponsors

    @Transactional(readOnly = true)
    public List<SponsorRow> list() {
        Set<Long> scope = visibleSponsors();
        return sponsors.findAllByOrderByNameAsc().stream().filter(s -> scope == null || scope.contains(s.getId())).map(this::row).toList();
    }

    @Transactional
    public SponsorRow create(SponsorRequest req) {
        requireManager();
        if (sponsors.findByCodeIgnoreCase(req.code().trim()).isPresent()) throw new BusinessRuleException("SPONSOR_EXISTS", "A sponsor with code " + req.code() + " exists");
        Sponsor s = new Sponsor();
        apply(s, req);
        sponsors.saveAndFlush(s);
        audit.record("SPONSOR_CREATED", "Sponsor", s.getId(), Map.of("code", s.getCode(), "name", s.getName()));
        return row(s);
    }

    @Transactional
    public SponsorRow update(Long id, SponsorRequest req) {
        requireManager();
        Sponsor s = require(id);
        sponsors.findByCodeIgnoreCase(req.code().trim()).filter(o -> !o.getId().equals(id))
                .ifPresent(o -> { throw new BusinessRuleException("SPONSOR_EXISTS", "Another sponsor has code " + req.code()); });
        apply(s, req);
        sponsors.saveAndFlush(s);
        audit.record("SPONSOR_UPDATED", "Sponsor", s.getId(), Map.of("code", s.getCode(), "name", s.getName(), "active", s.isActive()));
        return row(s);
    }

    private static void apply(Sponsor s, SponsorRequest req) {
        s.setCode(req.code().trim().toUpperCase());
        s.setName(req.name().trim());
        s.setKind(req.kind());
        s.setContactName(blank(req.contactName()));
        s.setContactEmail(blank(req.contactEmail()));
        s.setContactPhone(req.contactPhone() == null || req.contactPhone().isBlank() ? null
                : tz.co.msingi.cims.admission.service.PhoneNumbers.normalise(req.contactPhone().trim()));
        s.setAddress(blank(req.address()));
        s.setActive(req.active() == null || req.active());
    }

    // ------------------------------------------------------------------ one sponsor, for a year

    @Transactional(readOnly = true)
    public SponsorDetail detail(Long sponsorId, Long academicYearId) {
        Sponsor s = require(sponsorId);
        requireVisible(s);
        List<AcademicYear> all = years.findAllByOrderByStartDateDesc();
        Long yearId = academicYearId != null ? academicYearId : all.isEmpty() ? null : currentYear(all).getId();
        List<SponsorshipView> list = yearId == null ? List.of() : sponsorships.findBySponsorIdAndAcademicYearIdOrderByIdAsc(sponsorId, yearId).stream()
                .map(this::view).sorted(Comparator.comparing(SponsorshipView::registrationNo)).toList();
        List<SponsorInvoice> invs = invoices.findByPayerTypeAndPayerIdOrderByCreatedAtDesc(Invoice.PayerType.SPONSOR, sponsorId).stream()
                .filter(i -> PURPOSE.equals(i.getPurpose()) && (yearId == null || yearId.equals(i.getAcademicYearId()))).map(this::invoiceView).toList();
        BigDecimal shares = list.stream().filter(v -> "ACTIVE".equals(v.status())).map(SponsorshipView::share).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal invoiced = invs.stream().filter(i -> !"CANCELLED".equals(i.status())).map(SponsorInvoice::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = invs.stream().map(SponsorInvoice::paid).reduce(BigDecimal.ZERO, BigDecimal::add);
        // what can still be invoiced: each share not yet invoiced, never more than the student still owes
        BigDecimal notYet = yearId == null ? BigDecimal.ZERO : sponsorships.findBySponsorIdAndAcademicYearIdOrderByIdAsc(sponsorId, yearId).stream()
                .filter(x -> x.getStatus() == Sponsorship.Status.ACTIVE).map(x -> {
                    Invoice inv = annualInvoice(x.getStudentId(), x.getAcademicYearId());
                    if (inv == null) return BigDecimal.ZERO;
                    return share(x, inv).subtract(invoices.sponsorInvoiced(inv.getId())).min(inv.balance().subtract(invoices.sponsorPending(inv.getId())))
                            .max(BigDecimal.ZERO);
                }).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SponsorDetail(row(s), all.stream().map(y -> new YearOption(y.getId(), y.getCode())).toList(), yearId, list, invs, shares, invoiced,
                paid, invoiced.subtract(paid), notYet, isManager());
    }

    // ------------------------------------------------------------------ sponsored students (BP-17 step 1)

    @Transactional
    public SponsorshipView addSponsorship(Long sponsorId, SponsorshipRequest req) {
        requireManager();
        Sponsor s = require(sponsorId);
        if (!s.isActive()) throw new BusinessRuleException("SPONSOR_INACTIVE", s.getName() + " is not active");
        Student st = student(req.studentId(), req.registrationNo());
        AcademicYear y = years.findById(req.academicYearId()).orElseThrow(() -> new NotFoundException("Academic year", req.academicYearId()));
        Sponsorship x = create(s, st, y, req.coveragePercent(), req.capAmount(), req.feeItemCodes(), req.reference());
        messaging.event("SPONSOR_ASSIGNED", st.getPhone(), Map.of("sponsor", s.getName()));
        return view(x);
    }

    private Sponsorship create(Sponsor s, Student st, AcademicYear y, BigDecimal pct, BigDecimal cap, List<String> items, String reference) {
        sponsorships.findByStudentIdAndAcademicYearIdAndStatus(st.getId(), y.getId(), Sponsorship.Status.ACTIVE).ifPresent(o -> {
            throw new BusinessRuleException("ALREADY_SPONSORED", st.getRegistrationNo() + " is already sponsored by " + o.getSponsor().getName() + " for " + y.getCode()
                    + ". End that sponsorship first.");
        });
        Sponsorship x = new Sponsorship();
        x.setSponsor(s);
        x.setStudentId(st.getId());
        x.setAcademicYearId(y.getId());
        x.setCoveragePercent(pct.setScale(2, RoundingMode.HALF_UP));
        x.setCapAmount(cap);
        x.setFeeItemCodes(items == null || items.isEmpty() ? null : items.stream().map(String::trim).map(String::toUpperCase).filter(c -> !c.isEmpty())
                .distinct().collect(Collectors.joining(",")));
        x.setReference(blank(reference));
        x.setCreatedByName(CurrentUser.name());
        sponsorships.saveAndFlush(x);
        audit.record("SPONSORSHIP_ADDED", "Sponsorship", x.getId(), Map.of("sponsor", s.getCode(), "student", st.getRegistrationNo(), "year", y.getCode(),
                "percent", x.getCoveragePercent()));
        return x;
    }

    /**
     * A beneficiary reported by a sponsor's own system (HESLB): the sponsor pays up to {@code cap} of the student's fees for the
     * year. Creates the sponsorship, or updates the cap and reference of the one already there. Returns CREATED, UPDATED or
     * UNCHANGED; refuses when another sponsor already covers the student for that year.
     */
    @Transactional
    public String fromExchange(Long sponsorId, Long studentId, Long academicYearId, BigDecimal cap, String reference) {
        requireManager();
        Sponsor s = require(sponsorId);
        Student st = students.findById(studentId).orElseThrow(() -> new NotFoundException("Student", studentId));
        var existing = sponsorships.findByStudentIdAndAcademicYearIdAndStatus(studentId, academicYearId, Sponsorship.Status.ACTIVE);
        if (existing.isPresent()) {
            Sponsorship x = existing.get();
            if (!x.getSponsor().getId().equals(sponsorId)) {
                throw new BusinessRuleException("ALREADY_SPONSORED", st.getRegistrationNo() + " is already sponsored by " + x.getSponsor().getName());
            }
            boolean same = (cap == null ? x.getCapAmount() == null : x.getCapAmount() != null && x.getCapAmount().compareTo(cap) == 0)
                    && java.util.Objects.equals(x.getReference(), blank(reference));
            if (same) return "UNCHANGED";
            x.setCapAmount(cap);
            x.setReference(blank(reference));
            sponsorships.saveAndFlush(x);
            audit.record("SPONSORSHIP_UPDATED", "Sponsorship", x.getId(), Map.of("sponsor", s.getCode(), "student", st.getRegistrationNo(),
                    "cap", String.valueOf(cap)));
            return "UPDATED";
        }
        AcademicYear y = years.findById(academicYearId).orElseThrow(() -> new NotFoundException("Academic year", academicYearId));
        create(s, st, y, new BigDecimal("100"), cap, List.of(), reference);
        messaging.event("SPONSOR_ASSIGNED", st.getPhone(), Map.of("sponsor", s.getName()));
        return "CREATED";
    }

    /** The sponsor with this code, created with the given name and kind if the institution does not have it yet. */
    @Transactional
    public Long ensureSponsor(String code, String name, Sponsor.Kind kind) {
        requireManager();
        return sponsors.findByCodeIgnoreCase(code).map(Sponsor::getId).orElseGet(() ->
                create(new SponsorRequest(code, name, kind, null, null, null, null, true)).id());
    }

    @Transactional
    public SponsorshipView endSponsorship(Long id) {
        requireManager();
        Sponsorship x = sponsorships.findById(id).orElseThrow(() -> new NotFoundException("Sponsorship", id));
        x.setStatus(Sponsorship.Status.ENDED);
        sponsorships.saveAndFlush(x);
        audit.record("SPONSORSHIP_ENDED", "Sponsorship", x.getId(), Map.of("sponsor", x.getSponsor().getCode(), "studentId", x.getStudentId()));
        return view(x);
    }

    /**
     * Imports a beneficiary list (e.g. from HESLB) as CSV with a header row: registration number, coverage percent, cap amount (optional),
     * reference (optional). Rows that cannot be used are reported and skipped.
     */
    @Transactional
    public ImportResult importList(Long sponsorId, Long academicYearId, String csv) {
        requireManager();
        Sponsor s = require(sponsorId);
        AcademicYear y = years.findById(academicYearId).orElseThrow(() -> new NotFoundException("Academic year", academicYearId));
        List<List<String>> rows = Csv.parse(csv == null ? "" : csv.replace("﻿", ""));
        if (rows.isEmpty()) throw new BusinessRuleException("EMPTY_FILE", "The file has no rows");
        List<String> head = rows.get(0).stream().map(h -> h.trim().toLowerCase().replaceAll("[^a-z]", "")).toList();
        int reg = index(head, "registrationno", "regno", "registrationnumber", "studentno");
        int pct = index(head, "coveragepercent", "percent", "coverage");
        int cap = index(head, "capamount", "cap", "amount");
        int ref = index(head, "reference", "ref", "loanno", "indexno");
        if (reg < 0 || (pct < 0 && cap < 0)) {
            throw new BusinessRuleException("BAD_HEADER", "The first row must name the columns: registrationNo, coveragePercent, capAmount, reference");
        }
        int created = 0, skipped = 0;
        List<ImportError> errors = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.stream().allMatch(String::isBlank)) continue;
            try {
                String regNo = cell(r, reg);
                Student st = students.findByRegistrationNoIgnoreCase(regNo).orElseThrow(() -> new BusinessRuleException("UNKNOWN_STUDENT", "No student " + regNo));
                BigDecimal p = pct < 0 || cell(r, pct).isBlank() ? new BigDecimal("100") : new BigDecimal(cell(r, pct).replace("%", "").trim());
                BigDecimal c = cap < 0 || cell(r, cap).isBlank() ? null : new BigDecimal(cell(r, cap).replace(",", "").trim());
                if (p.signum() <= 0 || p.compareTo(new BigDecimal("100")) > 0) throw new BusinessRuleException("BAD_PERCENT", "Coverage must be between 0 and 100%");
                if (sponsorships.findByStudentIdAndAcademicYearIdAndStatus(st.getId(), y.getId(), Sponsorship.Status.ACTIVE)
                        .filter(o -> o.getSponsor().getId().equals(sponsorId)).isPresent()) {
                    skipped++;
                    continue;
                }
                create(s, st, y, p, c, List.of(), ref < 0 ? null : cell(r, ref));
                messaging.event("SPONSOR_ASSIGNED", st.getPhone(), Map.of("sponsor", s.getName()));
                created++;
            } catch (BusinessRuleException e) {
                errors.add(new ImportError(i + 1, e.getMessage()));
            } catch (NumberFormatException e) {
                errors.add(new ImportError(i + 1, "A number could not be read"));
            }
        }
        audit.record("SPONSORSHIPS_IMPORTED", "Sponsor", sponsorId, Map.of("year", y.getCode(), "created", created, "skipped", skipped, "errors", errors.size()));
        return new ImportResult(created, skipped, errors);
    }

    // ------------------------------------------------------------------ sponsor invoices (BP-17 step 2)

    /**
     * Invoices the sponsor for its students' shares of their annual fees for the year that have not been invoiced yet. A share never
     * exceeds what the student still owes.
     */
    @Transactional
    public InvoiceRunResult invoice(Long sponsorId, Long academicYearId) {
        requireManager();
        Sponsor s = require(sponsorId);
        AcademicYear y = years.findById(academicYearId).orElseThrow(() -> new NotFoundException("Academic year", academicYearId));
        List<BillingService.NewInvoice.Line> lines = new ArrayList<>();
        List<Long[]> beneficiaries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Sponsorship x : sponsorships.findBySponsorIdAndAcademicYearIdOrderByIdAsc(sponsorId, academicYearId)) {
            if (x.getStatus() != Sponsorship.Status.ACTIVE) continue;
            Student st = students.findById(x.getStudentId()).orElseThrow();
            Invoice inv = annualInvoice(st.getId(), academicYearId);
            if (inv == null) {
                skipped.add(st.getRegistrationNo() + ": no fees invoice for " + y.getCode() + " yet");
                continue;
            }
            BigDecimal notInvoiced = share(x, inv).subtract(invoices.sponsorInvoiced(inv.getId()));
            BigDecimal owed = inv.balance().subtract(invoices.sponsorPending(inv.getId()));
            if (notInvoiced.signum() <= 0) continue;   // already invoiced to the sponsor
            if (owed.signum() <= 0) {
                skipped.add(st.getRegistrationNo() + ": nothing left to pay on the " + y.getCode() + " fees");
                continue;
            }
            BigDecimal due = notInvoiced.min(owed);
            lines.add(new BillingService.NewInvoice.Line("SPONSORED", st.getRegistrationNo() + " " + st.fullName() + " · fees " + y.getCode(), due));
            beneficiaries.add(new Long[]{inv.getId(), st.getId()});
        }
        if (lines.isEmpty()) {
            throw new BusinessRuleException("NOTHING_TO_INVOICE", "Nothing to invoice for " + y.getCode() + ": the sponsored students' shares are already invoiced"
                    + (skipped.isEmpty() ? "." : " or paid (" + String.join("; ", skipped) + ")."));
        }
        Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.SPONSOR, s.getId(), s.getName(), s.getContactPhone(),
                s.getContactEmail(), PURPOSE, null, "Sponsored students' fees " + y.getCode() + " · " + lines.size() + " student" + (lines.size() == 1 ? "" : "s"),
                lines, academicYearId, null));
        for (int i = 0; i < inv.getLines().size(); i++) {
            InvoiceLine l = inv.getLines().get(i);
            l.setBeneficiaryInvoiceId(beneficiaries.get(i)[0]);
            l.setBeneficiaryStudentId(beneficiaries.get(i)[1]);
        }
        invoices.saveAndFlush(inv);
        audit.record("SPONSOR_INVOICED", "Invoice", inv.getId(), Map.of("sponsor", s.getCode(), "year", y.getCode(), "students", lines.size(),
                "amount", inv.getTotalAmount()));
        return new InvoiceRunResult(inv.getId(), inv.getInvoiceNo(), lines.size(), inv.getTotalAmount(), skipped);
    }

    /** A sponsor's payment by bank transfer, recorded by the Accountant against a sponsor invoice. */
    @Transactional
    public SponsorInvoice remittance(Long invoiceId, RemittanceRequest req) {
        requireManager();
        Invoice inv = invoices.findById(invoiceId).orElseThrow(() -> new NotFoundException("Invoice", invoiceId));
        if (inv.getPayerType() != Invoice.PayerType.SPONSOR) throw new BusinessRuleException("NOT_SPONSOR_INVOICE", "That is not a sponsor invoice");
        if (VOID.contains(inv.getStatus())) throw new BusinessRuleException("INVOICE_VOID", "That invoice is cancelled");
        if (inv.isPaid()) throw new BusinessRuleException("INVOICE_PAID", "That invoice is already paid");
        String ref = req.reference().trim();
        if (payments.findByInvoiceIdOrderByPaidAtAsc(inv.getId()).stream().anyMatch(p -> ref.equalsIgnoreCase(p.getPspReceiptNo()))) {
            throw new BusinessRuleException("DUPLICATE_REMITTANCE", "A remittance with reference " + ref + " is already recorded on this invoice");
        }
        if (req.paidOn().isAfter(java.time.LocalDate.now(ZONE))) throw new BusinessRuleException("FUTURE_DATE", "The payment date cannot be in the future");
        billing.postDirect(inv, req.amount(), Payment.Source.BANK, "BANK", ref, null, req.paidOn().atTime(12, 0).atZone(ZONE).toOffsetDateTime());
        return invoiceView(inv);
    }

    /** As a sponsor's payment arrives (GePG or bank), each student's share is moved onto that student's invoice. */
    @EventListener
    public void onPayment(PaymentPostedEvent e) {
        if (!Invoice.PayerType.SPONSOR.name().equals(e.payerType())) return;
        Payment p = payments.findById(e.paymentId()).orElse(null);
        if (p == null) return;
        Invoice sponsorInv = p.getInvoice();
        Map<Long, BigDecimal> perStudentInvoice = new HashMap<>();
        for (PaymentAllocation a : allocations.findByPaymentIdIn(List.of(p.getId()))) {
            Long target = a.getInvoiceLine().getBeneficiaryInvoiceId();
            if (target != null) perStudentInvoice.merge(target, a.getAmount(), BigDecimal::add);
        }
        for (Map.Entry<Long, BigDecimal> en : perStudentInvoice.entrySet()) {
            Invoice student = invoices.findById(en.getKey()).orElse(null);
            if (student == null || VOID.contains(student.getStatus())) continue;
            Payment moved = billing.postDirect(student, en.getValue(), Payment.Source.INTERNAL, "SPONSOR", "Sponsor invoice " + sponsorInv.getInvoiceNo(),
                    p.getReceiptNo(), p.getPaidAt());
            moved.setPspName(sponsorInv.getPayerName().length() > 60 ? sponsorInv.getPayerName().substring(0, 60) : sponsorInv.getPayerName());
        }
    }

    // ------------------------------------------------------------------ statements

    /** The sponsor's statement for a year as Excel: each student's share, what was invoiced and paid, and the sponsor invoices. */
    @Transactional
    public byte[] statementExcel(Long sponsorId, Long academicYearId) {
        SponsorDetail d = detail(sponsorId, academicYearId);
        String year = d.years().stream().filter(y -> y.id().equals(d.academicYearId())).map(YearOption::code).findFirst().orElse("");
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            Sheet sh = wb.createSheet("Students");
            sh.createRow(0).createCell(0).setCellValue(d.sponsor().name() + " · sponsored students " + year);
            sh.createRow(1).createCell(0).setCellValue("Shares " + plain(d.shares()) + " · invoiced " + plain(d.invoiced()) + " · paid " + plain(d.paid())
                    + " · balance " + plain(d.balance()));
            String[] cols = {"Registration no.", "Student", "Programme", "Coverage %", "Cap", "Reference", "Share", "Invoiced", "Paid", "Outstanding", "Status"};
            header(sh.createRow(3), cols, head);
            int ri = 4;
            for (SponsorshipView v : d.sponsorships()) {
                Row r = sh.createRow(ri++);
                r.createCell(0).setCellValue(v.registrationNo());
                r.createCell(1).setCellValue(v.studentName());
                r.createCell(2).setCellValue(v.programmeCode());
                r.createCell(3).setCellValue(v.coveragePercent().doubleValue());
                if (v.capAmount() != null) num(r, 4, v.capAmount(), money);
                r.createCell(5).setCellValue(v.reference() == null ? "" : v.reference());
                num(r, 6, v.share(), money);
                num(r, 7, v.invoiced(), money);
                num(r, 8, v.paid(), money);
                num(r, 9, v.outstanding(), money);
                r.createCell(10).setCellValue(v.status());
            }
            int[] w = {22, 30, 12, 11, 14, 18, 14, 14, 14, 14, 10};
            for (int i = 0; i < w.length; i++) sh.setColumnWidth(i, w[i] * 256);
            Sheet si = wb.createSheet("Invoices");
            header(si.createRow(0), new String[]{"Invoice", "Description", "Total", "Paid", "Balance", "Status", "Payment number", "Issued"}, head);
            ri = 1;
            for (SponsorInvoice i : d.invoices()) {
                Row r = si.createRow(ri++);
                r.createCell(0).setCellValue(i.invoiceNo());
                r.createCell(1).setCellValue(i.description());
                num(r, 2, i.total(), money);
                num(r, 3, i.paid(), money);
                num(r, 4, i.balance(), money);
                r.createCell(5).setCellValue(i.status());
                r.createCell(6).setCellValue(i.controlNo() == null ? "" : i.controlNo());
                r.createCell(7).setCellValue(i.createdAt().atZoneSameInstant(ZONE).toLocalDate().toString());
            }
            int[] w2 = {24, 50, 14, 14, 14, 14, 18, 12};
            for (int i = 0; i < w2.length; i++) si.setColumnWidth(i, w2[i] * 256);
            wb.write(out);
            audit.record("SPONSOR_STATEMENT_EXPORTED", "Sponsor", sponsorId, Map.of("year", year, "students", d.sponsorships().size()));
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Control number for a sponsor invoice, for the bursary or the sponsor's own officer. */
    @Transactional
    public SponsorInvoice controlNumber(Long invoiceId) {
        Invoice inv = invoices.findById(invoiceId).orElseThrow(() -> new NotFoundException("Invoice", invoiceId));
        if (inv.getPayerType() != Invoice.PayerType.SPONSOR) throw new NotFoundException("Invoice", invoiceId);
        requireVisible(require(inv.getPayerId()));
        billing.ensureControlNumber(invoiceId);
        return invoiceView(inv);
    }

    // ------------------------------------------------------------------ helpers

    /** The student's share for a sponsorship: the percentage of the covered fee items of their annual invoice, up to the cap. */
    static BigDecimal share(Sponsorship x, Invoice inv) {
        List<String> items = x.feeItems();
        BigDecimal base = inv.getLines().stream().filter(l -> items.isEmpty() || items.contains(l.getFeeItemCode().toUpperCase()))
                .map(InvoiceLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal s = base.multiply(x.getCoveragePercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return x.getCapAmount() == null ? s : s.min(x.getCapAmount());
    }

    private Invoice annualInvoice(Long studentId, Long yearId) {
        return invoices.findByPayerTypeAndPayerIdAndPurposeAndStatusNotIn(Invoice.PayerType.STUDENT, studentId, AnnualBillingService.PURPOSE, VOID).stream()
                .filter(i -> yearId.equals(i.getAcademicYearId())).findFirst().orElse(null);
    }

    private SponsorshipView view(Sponsorship x) {
        Student st = students.findById(x.getStudentId()).orElseThrow();
        AcademicYear y = years.findById(x.getAcademicYearId()).orElseThrow();
        Invoice inv = annualInvoice(st.getId(), y.getId());
        BigDecimal share = inv == null ? BigDecimal.ZERO : share(x, inv);
        BigDecimal invoiced = inv == null ? BigDecimal.ZERO : invoices.sponsorInvoiced(inv.getId());
        BigDecimal pending = inv == null ? BigDecimal.ZERO : invoices.sponsorPending(inv.getId());
        return new SponsorshipView(x.getId(), st.getId(), st.getRegistrationNo(), st.fullName(), st.getProgramme().getCode(), y.getId(), y.getCode(),
                x.getCoveragePercent(), x.getCapAmount(), x.feeItems(), x.getReference(), x.getStatus().name(), share, invoiced, invoiced.subtract(pending), pending,
                inv == null ? "No fees invoice for " + y.getCode() + " yet" : null);
    }

    private SponsorInvoice invoiceView(Invoice i) {
        ControlNumber cn = billing.currentControlNumber(i.getId());
        return new SponsorInvoice(i.getId(), i.getInvoiceNo(), i.getDescription(), i.getTotalAmount(), i.getPaidAmount(), i.balance(), i.getStatus().name(),
                cn == null ? null : cn.getControlNo(), cn == null ? null : cn.getExpiresAt(), i.getCreatedAt(), i.getLines().size());
    }

    private SponsorRow row(Sponsor s) {
        List<Invoice> invs = invoices.findByPayerTypeAndPayerIdOrderByCreatedAtDesc(Invoice.PayerType.SPONSOR, s.getId()).stream()
                .filter(i -> !VOID.contains(i.getStatus())).toList();
        BigDecimal invoiced = invs.stream().map(Invoice::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = invs.stream().map(Invoice::getPaidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SponsorRow(s.getId(), s.getCode(), s.getName(), s.getKind().name(), s.getContactName(), s.getContactPhone(), s.getContactEmail(),
                s.getAddress(), s.isActive(), sponsorships.countBySponsorIdAndStatus(s.getId(), Sponsorship.Status.ACTIVE), invoiced, paid, invoiced.subtract(paid));
    }

    private Student student(Long id, String regNo) {
        if (id != null) return students.findById(id).orElseThrow(() -> new NotFoundException("Student", id));
        if (regNo == null || regNo.isBlank()) throw new BusinessRuleException("STUDENT_REQUIRED", "Choose the student");
        return students.findByRegistrationNoIgnoreCase(regNo.trim()).orElseThrow(() -> new BusinessRuleException("UNKNOWN_STUDENT", "No student " + regNo.trim()));
    }

    private static AcademicYear currentYear(List<AcademicYear> all) {
        java.time.LocalDate today = java.time.LocalDate.now(ZONE);
        return all.stream().filter(y -> y.getStartDate() != null && !y.getStartDate().isAfter(today)).findFirst().orElse(all.get(all.size() - 1));
    }

    /** The sponsors the signed-in person may see: null for everyone in the bursary; a Sponsor Officer only their own. */
    public Set<Long> visibleSponsors() {
        if (has(FINANCE)) return null;
        Long me = CurrentUser.requireId();
        UserAccount u = users.findById(me).orElseThrow();
        return u.getRoles().stream().filter(r -> r.getRole() == Role.SPONSOR_OFFICER && "SPONSOR".equals(r.getScopeType()) && r.getScopeId() != null)
                .map(UserAccount.RoleAssignment::getScopeId).collect(Collectors.toSet());
    }

    private void requireVisible(Sponsor s) {
        Set<Long> scope = visibleSponsors();
        if (scope != null && !scope.contains(s.getId())) throw new AccessDeniedException("Not your sponsor");
    }

    private boolean isManager() {
        return has(MANAGERS);
    }

    private void requireManager() {
        if (!isManager()) throw new AccessDeniedException("Only the bursary manages sponsors");
    }

    private static boolean has(Role... roles) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        Set<String> mine = auth.getAuthorities().stream().map(Object::toString).collect(Collectors.toSet());
        for (Role r : roles) if (mine.contains(r.authority())) return true;
        return false;
    }

    private Sponsor require(Long id) {
        return sponsors.findById(id).orElseThrow(() -> new NotFoundException("Sponsor", id));
    }

    private static int index(List<String> head, String... names) {
        for (String n : names) {
            int i = head.indexOf(n);
            if (i >= 0) return i;
        }
        return -1;
    }

    private static String cell(List<String> r, int i) {
        return i < r.size() ? r.get(i).trim() : "";
    }

    private static void header(Row r, String[] cols, CellStyle style) {
        for (int i = 0; i < cols.length; i++) {
            r.createCell(i).setCellValue(cols[i]);
            r.getCell(i).setCellStyle(style);
        }
    }

    private static void num(Row r, int c, BigDecimal v, CellStyle style) {
        r.createCell(c).setCellValue(v == null ? 0 : v.doubleValue());
        r.getCell(c).setCellStyle(style);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : String.format(java.util.Locale.ENGLISH, "%,.2f", v);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
