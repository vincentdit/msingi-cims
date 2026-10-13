package tz.co.msingi.cims.finance.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.ControlNumberRepository;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.PaymentRepository;
import tz.co.msingi.cims.finance.domain.Payment;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationDtos.DayRow;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationDtos.ItemView;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationDtos.MonthView;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationDtos.ReconciliationView;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationItem.Kind;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationItem.Resolution;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationRepositories.GepgReconciliationRepository;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationRepositories.ReconciliationItemRepository;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationRepositories.ReconciliationSignoffRepository;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Daily GePG reconciliation (FR-FIN-05): GePG's file for a date is matched against CIMS payments by GePG receipt
 * number. Exceptions are listed for the Accountant: payments GePG reports that CIMS never received (posted from the
 * file), CIMS payments GePG does not report, amount differences, duplicates and unknown bills. Days are closed once
 * every exception is resolved, and the Bursar signs off each month.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    static final String HEADER = "PayRefId,SpBillId,BillCtrNum,PspTrxId,PspName,PaidAmt,Ccy,TrxDtTm,UsdPayChnl,PyrCellNum";

    /** Accepted column names (GePG's and plain ones), normalised to lower case without separators. */
    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("payrefid", "receipt"), Map.entry("gepgreceiptno", "receipt"), Map.entry("receipt", "receipt"), Map.entry("receiptno", "receipt"),
            Map.entry("spbillid", "bill"), Map.entry("billid", "bill"),
            Map.entry("billctrnum", "control"), Map.entry("controlno", "control"), Map.entry("controlnumber", "control"),
            Map.entry("psptrxid", "pspReceipt"), Map.entry("pspreceiptno", "pspReceipt"),
            Map.entry("pspname", "psp"), Map.entry("psp", "psp"),
            Map.entry("paidamt", "amount"), Map.entry("amount", "amount"),
            Map.entry("ccy", "currency"), Map.entry("currency", "currency"),
            Map.entry("trxdttm", "paidAt"), Map.entry("paidat", "paidAt"), Map.entry("date", "paidAt"),
            Map.entry("usdpaychnl", "channel"), Map.entry("channel", "channel"),
            Map.entry("pyrcellnum", "phone"), Map.entry("phone", "phone"));

    private final GepgReconciliationRepository reconciliations;
    private final ReconciliationItemRepository items;
    private final ReconciliationSignoffRepository signoffs;
    private final PaymentRepository payments;
    private final ControlNumberRepository controlNumbers;
    private final BillingService billing;
    private final AuditService audit;

    private record Line(int lineNo, String receipt, String bill, String control, String pspReceipt, String psp, BigDecimal amount,
                        OffsetDateTime paidAt, String channel, String phone) {
    }

    // ------------------------------------------------------------------ import and matching

    @Transactional
    public ReconciliationView importFile(LocalDate date, String fileName, String csv) {
        if (date.isAfter(LocalDate.now(ZONE))) {
            throw new BusinessRuleException("FUTURE_DATE", "A day can be reconciled only once it has started");
        }
        List<Line> lines = parse(csv, date);
        GepgReconciliation r = reconciliations.findByReconDate(date).orElse(null);
        if (r != null) {
            if (r.getStatus() == GepgReconciliation.Status.CLOSED) {
                throw new BusinessRuleException("RECONCILIATION_CLOSED", "The reconciliation for " + date + " is closed");
            }
            if (items.countByReconciliationIdAndStatus(r.getId(), ReconciliationItem.Status.RESOLVED) > 0) {
                throw new BusinessRuleException("REIMPORT_BLOCKED",
                        "Exceptions for " + date + " have already been resolved, so the file cannot be replaced. Resolve the rest instead.");
            }
            items.deleteByReconciliationId(r.getId());
            items.flush();
        } else {
            r = new GepgReconciliation();
            r.setReconDate(date);
        }
        r.setSource(GepgReconciliation.Source.FILE);
        r.setFileName(fileName);
        r.setImportedAt(OffsetDateTime.now());
        r.setImportedByName(CurrentUser.name());
        reconciliations.saveAndFlush(r);

        List<ReconciliationItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<Long> reportedHere = new HashSet<>();
        for (Line l : lines) {
            ReconciliationItem i = fromLine(r, l);
            if (!seen.add(l.receipt().toUpperCase(Locale.ROOT))) {
                i.setKind(Kind.DUPLICATE);
                i.setStatus(ReconciliationItem.Status.OPEN);
            } else {
                Optional<Payment> p = payments.findByGepgReceiptNo(l.receipt());
                if (p.isPresent()) {
                    Payment pay = p.get();
                    i.setPaymentId(pay.getId());
                    i.setCimsAmount(pay.getAmount());
                    i.setPaidDateDiffers(!pay.getPaidAt().atZoneSameInstant(ZONE).toLocalDate().equals(date));
                    reportedHere.add(pay.getId());
                    if (pay.getAmount().compareTo(l.amount()) == 0) {
                        i.setKind(Kind.MATCHED);
                        i.setStatus(ReconciliationItem.Status.MATCHED);
                        autoResolveMissingInGepg(pay.getId(), date);
                    } else {
                        i.setKind(Kind.AMOUNT_MISMATCH);
                        i.setStatus(ReconciliationItem.Status.OPEN);
                    }
                } else {
                    i.setKind(findBill(l).isPresent() ? Kind.MISSING_IN_CIMS : Kind.UNKNOWN_BILL);
                    i.setStatus(ReconciliationItem.Status.OPEN);
                }
            }
            out.add(i);
        }

        // CIMS payments of the day that GePG did not report (here or in any other day's file)
        List<Payment> ours = paymentsOn(date);
        Set<Long> reportedElsewhere = ours.isEmpty() ? Set.of()
                : new HashSet<>(items.reportedByGepg(ours.stream().map(Payment::getId).toList()));
        for (Payment p : ours) {
            if (reportedHere.contains(p.getId()) || reportedElsewhere.contains(p.getId())) continue;
            ReconciliationItem i = new ReconciliationItem();
            i.setReconciliation(r);
            i.setKind(Kind.MISSING_IN_GEPG);
            i.setStatus(ReconciliationItem.Status.OPEN);
            i.setGepgReceiptNo(p.getGepgReceiptNo());
            i.setControlNo(p.getControlNo());
            i.setPspName(p.getPspName());
            i.setPspReceiptNo(p.getPspReceiptNo());
            i.setChannel(p.getChannel());
            i.setPayerPhone(p.getPayerPhone());
            i.setCimsAmount(p.getAmount());
            i.setPaidAt(p.getPaidAt());
            i.setPaymentId(p.getId());
            out.add(i);
        }
        items.saveAll(out);

        r.setGepgCount(lines.size());
        r.setGepgTotal(lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        r.setCimsCount(ours.size());
        r.setCimsTotal(ours.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        r.setMatchedCount((int) out.stream().filter(i -> i.getKind() == Kind.MATCHED).count());
        r.setExceptionCount((int) out.stream().filter(i -> i.getKind() != Kind.MATCHED).count());
        audit.record("GEPG_RECONCILIATION_IMPORTED", "GepgReconciliation", r.getId(), Map.of("date", date.toString(),
                "lines", lines.size(), "matched", r.getMatchedCount(), "exceptions", r.getExceptionCount()));
        return view(r);
    }

    /** A payment GePG reports today but on another day in CIMS: an open "missing in GePG" for it is settled. */
    private void autoResolveMissingInGepg(Long paymentId, LocalDate foundOn) {
        for (ReconciliationItem m : items.findByPaymentIdAndKindAndStatus(paymentId, Kind.MISSING_IN_GEPG, ReconciliationItem.Status.OPEN)) {
            m.setStatus(ReconciliationItem.Status.RESOLVED);
            m.setResolution(Resolution.AUTO_MATCHED);
            m.setNote("Found in GePG's file of " + foundOn);
            m.setResolvedAt(OffsetDateTime.now());
            m.setResolvedByName("System");
        }
    }

    // ------------------------------------------------------------------ resolution

    /** Posts a payment GePG reports but CIMS never received (missed callback): receipt, allocation and balance as usual. */
    @Transactional
    public ReconciliationView post(Long itemId, String note) {
        ReconciliationItem i = requireOpen(itemId);
        if (i.getKind() != Kind.MISSING_IN_CIMS) {
            throw new BusinessRuleException("NOT_POSTABLE", "Only payments missing in CIMS can be posted");
        }
        ControlNumber cn = controlNumbers.findByBillId(i.getBillId() == null ? "" : i.getBillId())
                .or(() -> controlNumbers.findByControlNo(i.getControlNo() == null ? "" : i.getControlNo()))
                .orElseThrow(() -> new BusinessRuleException("UNKNOWN_BILL", "The bill is not known to CIMS"));
        Payment p = billing.recordPayment(new BillingService.PaymentNotification(cn.getBillId(), cn.getControlNo(), i.getGepgReceiptNo(),
                i.getPspReceiptNo(), i.getPspName(), i.getChannel(), i.getGepgAmount(), i.getPayerPhone(), i.getPaidAt()));
        if (p == null) { // posted meanwhile (the callback arrived late)
            p = payments.findByGepgReceiptNo(i.getGepgReceiptNo()).orElseThrow();
        }
        i.setPaymentId(p.getId());
        i.setCimsAmount(p.getAmount());
        resolve(i, Resolution.POSTED, note == null || note.isBlank() ? "Posted from GePG reconciliation (callback not received)" : note.trim());
        audit.record("GEPG_RECONCILIATION_POSTED", "Payment", p.getId(), Map.of("receipt", i.getGepgReceiptNo(), "amount", i.getGepgAmount()));
        return view(i.getReconciliation());
    }

    /** Resolves an exception with an explanation (e.g. "settled with GePG", "bank reversal"). */
    @Transactional
    public ReconciliationView note(Long itemId, String note) {
        ReconciliationItem i = requireOpen(itemId);
        resolve(i, Resolution.NOTED, note.trim());
        audit.record("GEPG_RECONCILIATION_NOTED", "GepgReconciliation", i.getReconciliation().getId(),
                Map.of("kind", i.getKind().name(), "receipt", String.valueOf(i.getGepgReceiptNo()), "note", note.trim()));
        return view(i.getReconciliation());
    }

    @Transactional
    public ReconciliationView close(Long reconciliationId) {
        GepgReconciliation r = require(reconciliationId);
        if (r.getStatus() == GepgReconciliation.Status.CLOSED) {
            throw new BusinessRuleException("RECONCILIATION_CLOSED", "Already closed");
        }
        long open = items.countByReconciliationIdAndStatus(r.getId(), ReconciliationItem.Status.OPEN);
        if (open > 0) {
            throw new BusinessRuleException("OPEN_EXCEPTIONS", open + " exception(s) still to resolve before the day can be closed");
        }
        r.setStatus(GepgReconciliation.Status.CLOSED);
        r.setClosedAt(OffsetDateTime.now());
        r.setClosedByName(CurrentUser.name());
        audit.record("GEPG_RECONCILIATION_CLOSED", "GepgReconciliation", r.getId(), Map.of("date", r.getReconDate().toString()));
        return view(r);
    }

    private void resolve(ReconciliationItem i, Resolution resolution, String note) {
        i.setStatus(ReconciliationItem.Status.RESOLVED);
        i.setResolution(resolution);
        i.setNote(note);
        i.setResolvedAt(OffsetDateTime.now());
        i.setResolvedByName(CurrentUser.name());
    }

    // ------------------------------------------------------------------ month and sign-off

    @Transactional(readOnly = true)
    public MonthView month(YearMonth ym) {
        LocalDate first = ym.atDay(1), last = ym.atEndOfMonth();
        List<Payment> list = payments.findByPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAtAsc(
                first.atStartOfDay(ZONE).toOffsetDateTime(), last.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime()).stream()
                .filter(p -> p.getSource() == Payment.Source.GEPG).toList();   // only GePG payments appear in GePG's files
        Map<LocalDate, List<Payment>> byDay = list.stream().collect(Collectors.groupingBy(
                p -> p.getPaidAt().atZoneSameInstant(ZONE).toLocalDate(), TreeMap::new, Collectors.toList()));
        Map<LocalDate, GepgReconciliation> recs = reconciliations.findByReconDateBetweenOrderByReconDateAsc(first, last).stream()
                .collect(Collectors.toMap(GepgReconciliation::getReconDate, Function.identity()));
        LocalDate today = LocalDate.now(ZONE);
        List<DayRow> days = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last) && !d.isAfter(today); d = d.plusDays(1)) {
            List<Payment> ps = byDay.getOrDefault(d, List.of());
            GepgReconciliation r = recs.get(d);
            days.add(new DayRow(d, ps.size(), sum(ps), r == null ? null : r.getId(), r == null ? null : r.getStatus().name(),
                    r == null ? null : r.getExceptionCount(),
                    r == null ? null : items.countByReconciliationIdAndStatus(r.getId(), ReconciliationItem.Status.OPEN),
                    r == null ? null : r.getMatchedCount(), r == null ? null : r.getGepgCount()));
        }
        int withPayments = byDay.size();
        int closed = (int) recs.values().stream().filter(r -> r.getStatus() == GepgReconciliation.Status.CLOSED).count();
        boolean ended = today.isAfter(last);
        var signoff = signoffs.findByMonth(first);
        String blocker = signoffBlocker(ended, byDay.keySet(), recs, signoff.isPresent());
        return new MonthView(first, sum(list), list.size(), withPayments, closed, ended, blocker == null, blocker,
                signoff.map(ReconciliationSignoff::getSignedAt).orElse(null), signoff.map(ReconciliationSignoff::getSignedByName).orElse(null),
                signoff.map(ReconciliationSignoff::getNote).orElse(null), days);
    }

    /** Bursar sign-off: the month is over, every day with collections is reconciled and every reconciliation is closed. */
    @Transactional
    public MonthView signOff(YearMonth ym, String note) {
        MonthView m = month(ym);
        if (!m.canSignOff()) throw new BusinessRuleException("CANNOT_SIGN_OFF", m.signOffBlocker());
        ReconciliationSignoff s = new ReconciliationSignoff();
        s.setMonth(ym.atDay(1));
        s.setDaysReconciled(m.daysClosed());
        s.setTotalCollected(m.collected());
        s.setNote(note == null || note.isBlank() ? null : note.trim());
        s.setSignedAt(OffsetDateTime.now());
        s.setSignedBy(CurrentUser.requireId());
        s.setSignedByName(CurrentUser.name());
        signoffs.save(s);
        audit.record("RECONCILIATION_SIGNED_OFF", "ReconciliationSignoff", s.getId(), Map.of("month", ym.toString(), "collected", m.collected()));
        return month(ym);
    }

    private static String signoffBlocker(boolean ended, Set<LocalDate> daysWithPayments, Map<LocalDate, GepgReconciliation> recs, boolean signed) {
        if (signed) return "Already signed off";
        if (!ended) return "The month has not ended yet";
        List<LocalDate> missing = daysWithPayments.stream().filter(d -> !recs.containsKey(d)).sorted().toList();
        if (!missing.isEmpty()) return "Not reconciled yet: " + missing.stream().map(LocalDate::toString).collect(Collectors.joining(", "));
        List<LocalDate> open = recs.values().stream().filter(r -> r.getStatus() == GepgReconciliation.Status.OPEN)
                .map(GepgReconciliation::getReconDate).sorted().toList();
        if (!open.isEmpty()) return "Still open: " + open.stream().map(LocalDate::toString).collect(Collectors.joining(", "));
        return null;
    }

    // ------------------------------------------------------------------ views and files

    @Transactional(readOnly = true)
    public ReconciliationView get(Long id) {
        return view(require(id));
    }

    /** CIMS payments of a date in GePG's file layout: for comparing by hand or testing an import. */
    @Transactional(readOnly = true)
    public String cimsPaymentsFile(LocalDate date) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        DateTimeFormatter f = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        for (Payment p : paymentsOn(date)) {
            String bill = controlNumbers.findByControlNo(p.getControlNo()).map(ControlNumber::getBillId).orElse("");
            sb.append(Csv.line(p.getGepgReceiptNo(), bill, p.getControlNo(), p.getPspReceiptNo(), p.getPspName(),
                    p.getAmount().toPlainString(), "TZS", f.format(p.getPaidAt().atZoneSameInstant(ZONE).toLocalDateTime().withNano(0)),
                    p.getChannel(), p.getPayerPhone()));
        }
        return sb.toString();
    }

    private ReconciliationView view(GepgReconciliation r) {
        List<ReconciliationItem> list = items.findByReconciliationIdOrderByIdAsc(r.getId());
        Map<Long, Payment> pays = new HashMap<>();
        Set<Long> ids = list.stream().map(ReconciliationItem::getPaymentId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (!ids.isEmpty()) payments.findAllById(ids).forEach(p -> pays.put(p.getId(), p));
        long open = list.stream().filter(i -> i.getStatus() == ReconciliationItem.Status.OPEN).count();
        List<ItemView> views = list.stream()
                .sorted(Comparator.comparing((ReconciliationItem i) -> i.getStatus() == ReconciliationItem.Status.OPEN ? 0
                        : i.getStatus() == ReconciliationItem.Status.RESOLVED ? 1 : 2).thenComparing(ReconciliationItem::getId))
                .map(i -> {
                    Payment p = i.getPaymentId() == null ? null : pays.get(i.getPaymentId());
                    return new ItemView(i.getId(), i.getLineNo(), i.getKind().name(), i.getStatus().name(), i.getGepgReceiptNo(), i.getBillId(),
                            i.getControlNo(), i.getPspName(), i.getPspReceiptNo(), i.getChannel(), i.getPayerPhone(), i.getGepgAmount(),
                            i.getCimsAmount(), i.getPaidAt(), i.getPaymentId(), i.isPaidDateDiffers(),
                            i.getResolution() == null ? null : i.getResolution().name(), i.getNote(), i.getResolvedAt(), i.getResolvedByName(),
                            p == null ? null : p.getInvoice().getPayerName(), p == null ? null : p.getReceiptNo());
                }).toList();
        int base = Math.max(r.getGepgCount(), 1);
        Double rate = r.getGepgCount() == 0 ? null : Math.round(1000.0 * r.getMatchedCount() / base) / 10.0;
        return new ReconciliationView(r.getId(), r.getReconDate(), r.getSource().name(), r.getFileName(), r.getStatus().name(),
                r.getGepgCount(), r.getGepgTotal(), r.getCimsCount(), r.getCimsTotal(), r.getMatchedCount(), r.getExceptionCount(), open, rate,
                r.getImportedAt(), r.getImportedByName(), r.getClosedAt(), r.getClosedByName(), views);
    }

    // ------------------------------------------------------------------ parsing

    /** All rows are validated first; nothing is imported if any row is wrong. */
    private List<Line> parse(String text, LocalDate date) {
        List<List<String>> rows;
        try {
            rows = Csv.parse(text);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("INVALID_FILE", e.getMessage());
        }
        if (rows.isEmpty()) throw new BusinessRuleException("INVALID_FILE", "The file is empty");
        Map<String, Integer> col = new HashMap<>();
        List<String> header = rows.get(0);
        for (int c = 0; c < header.size(); c++) {
            String key = COLUMNS.get(header.get(c).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""));
            if (key != null) col.putIfAbsent(key, c);
        }
        if (!col.containsKey("receipt") || !col.containsKey("amount") || !(col.containsKey("bill") || col.containsKey("control"))) {
            throw new BusinessRuleException("INVALID_FILE", "The file needs at least PayRefId, PaidAmt and SpBillId or BillCtrNum columns. Expected: " + HEADER);
        }
        List<String> errors = new ArrayList<>();
        List<Line> out = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            int lineNo = r + 1;
            String receipt = cell(row, col, "receipt");
            String amountText = cell(row, col, "amount").replace(",", "");
            String currency = cell(row, col, "currency");
            if (receipt.isEmpty()) { errors.add("Line " + lineNo + ": PayRefId is missing"); continue; }
            if (receipt.length() > 40) { errors.add("Line " + lineNo + ": PayRefId is too long"); continue; }
            BigDecimal amount;
            try {
                amount = new BigDecimal(amountText).setScale(2, RoundingMode.HALF_UP);
            } catch (NumberFormatException | ArithmeticException e) {
                errors.add("Line " + lineNo + ": PaidAmt '" + amountText + "' is not a number"); continue;
            }
            if (amount.signum() <= 0) { errors.add("Line " + lineNo + ": PaidAmt must be more than zero"); continue; }
            if (!currency.isEmpty() && !currency.equalsIgnoreCase("TZS")) { errors.add("Line " + lineNo + ": only TZS collections are reconciled here"); continue; }
            OffsetDateTime paidAt;
            try {
                paidAt = parseTime(cell(row, col, "paidAt"), date);
            } catch (DateTimeParseException e) {
                errors.add("Line " + lineNo + ": TrxDtTm '" + cell(row, col, "paidAt") + "' is not a date and time"); continue;
            }
            out.add(new Line(lineNo, receipt, blankToNull(cell(row, col, "bill")), blankToNull(cell(row, col, "control")),
                    truncate(blankToNull(cell(row, col, "pspReceipt")), 60), truncate(blankToNull(cell(row, col, "psp")), 60), amount, paidAt,
                    truncate(blankToNull(cell(row, col, "channel")), 30), truncate(blankToNull(cell(row, col, "phone")), 20)));
        }
        if (!errors.isEmpty()) {
            throw new BusinessRuleException("INVALID_FILE", errors.size() + " line(s) need correcting: "
                    + String.join("; ", errors.subList(0, Math.min(10, errors.size()))) + (errors.size() > 10 ? "; …" : ""));
        }
        return out;
    }

    static OffsetDateTime parseTime(String s, LocalDate date) {
        if (s == null || s.isBlank()) return date.atTime(12, 0).atZone(ZONE).toOffsetDateTime();
        String t = s.trim().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(t);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(t).atZone(ZONE).toOffsetDateTime();
            } catch (DateTimeParseException e2) {
                return LocalDate.parse(t).atTime(12, 0).atZone(ZONE).toOffsetDateTime();
            }
        }
    }

    private ReconciliationItem fromLine(GepgReconciliation r, Line l) {
        ReconciliationItem i = new ReconciliationItem();
        i.setReconciliation(r);
        i.setLineNo(l.lineNo());
        i.setGepgReceiptNo(l.receipt());
        i.setBillId(l.bill());
        i.setControlNo(l.control());
        i.setPspName(l.psp());
        i.setPspReceiptNo(l.pspReceipt());
        i.setChannel(l.channel());
        i.setPayerPhone(l.phone());
        i.setGepgAmount(l.amount());
        i.setPaidAt(l.paidAt());
        return i;
    }

    private Optional<ControlNumber> findBill(Line l) {
        return Optional.ofNullable(l.bill()).flatMap(controlNumbers::findByBillId)
                .or(() -> Optional.ofNullable(l.control()).flatMap(controlNumbers::findByControlNo));
    }

    private List<Payment> paymentsOn(LocalDate date) {
        return payments.findByPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAtAsc(
                date.atStartOfDay(ZONE).toOffsetDateTime(), date.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime()).stream()
                .filter(p -> p.getSource() == Payment.Source.GEPG).toList();
    }

    private GepgReconciliation require(Long id) {
        return reconciliations.findById(id).orElseThrow(() -> new NotFoundException("Reconciliation", id));
    }

    private ReconciliationItem requireOpen(Long itemId) {
        ReconciliationItem i = items.findById(itemId).orElseThrow(() -> new NotFoundException("Reconciliation item", itemId));
        if (i.getReconciliation().getStatus() == GepgReconciliation.Status.CLOSED) {
            throw new BusinessRuleException("RECONCILIATION_CLOSED", "This day's reconciliation is closed");
        }
        if (i.getStatus() != ReconciliationItem.Status.OPEN) {
            throw new BusinessRuleException("NOT_OPEN", "This line is not an open exception");
        }
        return i;
    }

    private static String cell(List<String> row, Map<String, Integer> col, String key) {
        Integer i = col.get(key);
        return i == null || i >= row.size() ? "" : row.get(i).trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static BigDecimal sum(List<Payment> list) {
        return list.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
