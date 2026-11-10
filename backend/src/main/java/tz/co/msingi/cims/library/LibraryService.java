package tz.co.msingi.cims.library;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.TabularFile;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The library (FR-LIB-01..05): the catalogue of titles and their copies, lending to students and staff, renewals and returns, fines
 * for late returns and the price of lost items (billed to the student's account with a control number; staff pay at the cashier),
 * reminders, and the check the library clearance office needs before it clears a finalist.
 */
@Service
@RequiredArgsConstructor
public class LibraryService {

    public static final String PURPOSE = "LIBRARY_FINES";
    private static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Set<String> KINDS = Set.of("BOOK", "REFERENCE", "JOURNAL", "REPORT", "MEDIA", "OTHER");
    /** Students who have left cannot borrow. */
    private static final Set<Student.Status> CAN_BORROW = Set.of(Student.Status.ADMITTED, Student.Status.ACTIVE);

    private final JdbcTemplate jdbc;
    private final StudentRepository students;
    private final BillingService billing;
    private final Messaging messaging;
    private final AuditService audit;
    private final Clock clock = Clock.systemUTC();

    // ------------------------------------------------------------------ records

    public record Settings(int studentLoanDays, int staffLoanDays, int studentMaxLoans, int staffMaxLoans, int maxRenewals, BigDecimal finePerDay,
                           BigDecimal maxFine) {
    }

    public record ItemRequest(String title, String authors, String isbn, String publisher, Integer year, String edition, String subject, String callNumber,
                              String kind) {
    }

    public record CopyRequest(String barcode, Long campusId, BigDecimal price, LocalDate acquiredOn, String note) {
    }

    public record ItemSummary(Long id, String title, String authors, String isbn, String publisher, Integer year, String edition, String subject,
                              String callNumber, String kind, int copies, int available) {
    }

    public record CopyView(Long id, String barcode, String campus, Long campusId, String status, BigDecimal price, LocalDate acquiredOn, String note,
                           String borrower, LocalDate dueOn) {
    }

    public record ItemDetail(ItemSummary item, List<CopyView> copies) {
    }

    public record LoanView(Long id, Long copyId, String barcode, String title, String callNumber, String borrowerType, Long borrowerId,
                           String borrowerName, String borrowerRef, OffsetDateTime issuedAt, LocalDate dueOn, int renewals, OffsetDateTime returnedAt,
                           boolean lost, int daysLate, BigDecimal fine, String fineStatus, String fineNote, String controlNo, String issuedByName,
                           String returnedByName) {
    }

    public record Borrower(String type, Long id, String name, String reference, String status, boolean canBorrow, String reason, int onLoan,
                           int maxLoans, int overdue, BigDecimal unpaidFines, List<LoanView> loans) {
    }

    public record IssueRequest(String barcode, String borrowerType, Long borrowerId) {
    }

    public record Overview(int titles, int copies, int onLoan, int overdue, int lost, BigDecimal finesBilledThisYear, BigDecimal finesUnpaid) {
    }

    public record ImportResult(int titlesAdded, int copiesAdded, int skipped, List<String> problems) {
    }

    public record CatalogueEntry(Long id, String title, String authors, String callNumber, String kind, int copies, int available, LocalDate nextDue) {
    }

    public record MyLibrary(List<LoanView> onLoan, List<LoanView> history, BigDecimal unpaidFines, int maxLoans, int loanDays) {
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public Settings settings() {
        return jdbc.query("""
                select student_loan_days, staff_loan_days, student_max_loans, staff_max_loans, max_renewals, fine_per_day, max_fine
                from library_settings""", (rs, i) -> new Settings(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getBigDecimal(6),
                rs.getBigDecimal(7))).stream().findFirst().orElse(new Settings(14, 30, 3, 10, 1, new BigDecimal("200"), new BigDecimal("20000")));
    }

    @Transactional
    public Settings saveSettings(Settings s) {
        if (s.studentLoanDays() < 1 || s.staffLoanDays() < 1 || s.studentMaxLoans() < 1 || s.staffMaxLoans() < 1 || s.maxRenewals() < 0
                || s.finePerDay() == null || s.finePerDay().signum() < 0 || s.maxFine() == null || s.maxFine().signum() < 0) {
            throw new BusinessRuleException("SETTINGS_INVALID", "Check the numbers: loan days and limits are at least 1, fines are not negative");
        }
        jdbc.update("""
                insert into library_settings (tenant_id, student_loan_days, staff_loan_days, student_max_loans, staff_max_loans, max_renewals, fine_per_day, max_fine)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id) do update set student_loan_days = excluded.student_loan_days, staff_loan_days = excluded.staff_loan_days,
                  student_max_loans = excluded.student_max_loans, staff_max_loans = excluded.staff_max_loans, max_renewals = excluded.max_renewals,
                  fine_per_day = excluded.fine_per_day, max_fine = excluded.max_fine, updated_at = now()""",
                s.studentLoanDays(), s.staffLoanDays(), s.studentMaxLoans(), s.staffMaxLoans(), s.maxRenewals(), s.finePerDay(), s.maxFine());
        audit.record("LIBRARY_SETTINGS_CHANGED", "LibrarySettings", null, Map.of("finePerDay", s.finePerDay(), "studentLoanDays", s.studentLoanDays()));
        return settings();
    }

    // ------------------------------------------------------------------ catalogue

    @Transactional(readOnly = true)
    public List<ItemSummary> search(String q, int limit) {
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.query(ITEM + " " + """
                 where lower(i.title) like ? or lower(coalesce(i.authors, '')) like ? or coalesce(i.isbn, '') like ? or lower(coalesce(i.call_number, '')) like ?
                   or lower(coalesce(i.subject, '')) like ?
                   or exists (select 1 from library_copies c where c.item_id = i.id and lower(c.barcode) = ?)
                 order by i.title limit ?""", (rs, n) -> item(rs), like, like, like, like, like, q == null ? "" : q.trim().toLowerCase(Locale.ROOT),
                Math.min(Math.max(limit, 1), 200));
    }

    @Transactional(readOnly = true)
    public ItemDetail item(Long id) {
        ItemSummary s = jdbc.query(ITEM + " where i.id = ?", (rs, n) -> item(rs), id).stream().findFirst().orElseThrow(() -> new NotFoundException("Title not found"));
        List<CopyView> copies = jdbc.query("""
                select c.id, c.barcode, ca.name, c.campus_id, c.status, c.price, c.acquired_on, c.note, l.borrower_name, l.due_on
                from library_copies c left join campuses ca on ca.id = c.campus_id
                left join library_loans l on l.copy_id = c.id and l.returned_at is null
                where c.item_id = ? order by c.barcode""", (rs, n) -> new CopyView(rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4),
                rs.getString(5), rs.getBigDecimal(6), rs.getObject(7, LocalDate.class), rs.getString(8), rs.getString(9), rs.getObject(10, LocalDate.class)), id);
        return new ItemDetail(s, copies);
    }

    @Transactional
    public ItemDetail saveItem(Long id, ItemRequest r) {
        String title = required(r.title(), 300, "the title");
        String kind = r.kind() == null ? "BOOK" : r.kind().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) throw new BusinessRuleException("KIND_INVALID", "Choose what kind of item it is");
        String isbn = isbn(r.isbn());
        if (r.year() != null && (r.year() < 1500 || r.year() > LocalDate.now(clock).getYear() + 1)) throw new BusinessRuleException("YEAR_INVALID", "Check the year");
        Object[] v = {title, cut(r.authors(), 300), isbn, cut(r.publisher(), 150), r.year(), cut(r.edition(), 40), cut(r.subject(), 150), cut(r.callNumber(), 40), kind};
        if (id == null) {
            id = jdbc.queryForObject("""
                    insert into library_items (tenant_id, title, authors, isbn, publisher, year, edition, subject, call_number, kind)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, v);
            audit.record("LIBRARY_TITLE_ADDED", "LibraryItem", id, Map.of("title", title));
        } else {
            Object[] w = java.util.Arrays.copyOf(v, v.length + 1);
            w[v.length] = id;
            if (jdbc.update("""
                    update library_items set title = ?, authors = ?, isbn = ?, publisher = ?, year = ?, edition = ?, subject = ?, call_number = ?, kind = ?,
                      updated_at = now() where id = ?""", w) == 0) throw new NotFoundException("Title not found");
            audit.record("LIBRARY_TITLE_CHANGED", "LibraryItem", id, Map.of("title", title));
        }
        return item(id);
    }

    @Transactional
    public ItemDetail addCopy(Long itemId, CopyRequest r) {
        item(itemId);
        String barcode = barcode(r.barcode());
        if (r.price() != null && r.price().signum() < 0) throw new BusinessRuleException("PRICE_INVALID", "The price cannot be negative");
        if (exists("select count(*) from library_copies where lower(barcode) = lower(?)", barcode)) {
            throw new BusinessRuleException("BARCODE_TAKEN", "Another copy already has the number " + barcode);
        }
        String kind = jdbc.queryForObject("select kind from library_items where id = ?", String.class, itemId);
        Long id = jdbc.queryForObject("""
                insert into library_copies (tenant_id, item_id, barcode, campus_id, status, price, acquired_on, note)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, itemId, barcode, r.campusId(),
                "REFERENCE".equals(kind) ? "IN_LIBRARY" : "AVAILABLE", r.price(), r.acquiredOn(), cut(r.note(), 300));
        audit.record("LIBRARY_COPY_ADDED", "LibraryCopy", id, Map.of("barcode", barcode, "itemId", itemId));
        return item(itemId);
    }

    /** AVAILABLE (can be lent), IN_LIBRARY (read in the library only), WITHDRAWN (removed from stock); LOST is set by a loan. */
    @Transactional
    public ItemDetail setCopyStatus(Long copyId, String status, String note) {
        String s = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (!Set.of("AVAILABLE", "IN_LIBRARY", "WITHDRAWN").contains(s)) throw new BusinessRuleException("STATUS_INVALID", "Choose available, library use only or withdrawn");
        Map<String, Object> c = jdbc.queryForList("select item_id, status from library_copies where id = ?", copyId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Copy not found"));
        if ("ON_LOAN".equals(c.get("status"))) throw new BusinessRuleException("COPY_ON_LOAN", "The copy is on loan; take it back first");
        jdbc.update("update library_copies set status = ?, note = coalesce(?, note) where id = ?", s, cut(note, 300), copyId);
        audit.record("LIBRARY_COPY_STATUS", "LibraryCopy", copyId, Map.of("from", c.get("status"), "to", s));
        return item((Long) c.get("item_id"));
    }

    /**
     * Imports a catalogue from CSV or Excel: Title, Authors, ISBN, Publisher, Year, Edition, Subject, Call number, Kind, Barcode, Price.
     * Rows with the same ISBN (or, without one, the same title and authors) are copies of one title; a row's barcode adds a copy.
     */
    @Transactional
    public ImportResult importCatalogue(byte[] data) {
        List<Map<String, String>> rows = TabularFile.read(data);
        if (!rows.isEmpty() && !rows.get(0).containsKey("title")) throw new BusinessRuleException("COLUMNS", "The file needs a Title column");
        int titles = 0, copies = 0, skipped = 0;
        List<String> problems = new ArrayList<>();
        Map<String, Long> seen = new HashMap<>();
        for (Map<String, String> row : rows) {
            String line = "Row " + row.get("_row") + ": ";
            String title = row.get("title");
            if (title == null) {
                skipped++;
                problems.add(line + "no title");
                continue;
            }
            String isbn;
            try {
                isbn = isbn(row.get("isbn"));
            } catch (BusinessRuleException e) {
                isbn = null;
                problems.add(line + "ISBN ignored (" + row.get("isbn") + ")");
            }
            Integer year = null;
            try {
                if (row.get("year") != null) year = Integer.parseInt(row.get("year").replaceAll("\\.0+$", ""));
            } catch (NumberFormatException e) {
                problems.add(line + "year ignored (" + row.get("year") + ")");
            }
            String kind = row.get("kind") == null ? "BOOK" : row.get("kind").toUpperCase(Locale.ROOT);
            if (!KINDS.contains(kind)) kind = "BOOK";
            String key = isbn != null ? "isbn:" + isbn : "t:" + title.trim().toLowerCase(Locale.ROOT) + "|" + (row.get("authors") == null ? "" : row.get("authors").trim().toLowerCase(Locale.ROOT));
            Long itemId = seen.get(key);
            if (itemId == null) {
                itemId = isbn != null
                        ? jdbc.query("select id from library_items where isbn = ? order by id limit 1", (rs, n) -> rs.getLong(1), isbn).stream().findFirst().orElse(null)
                        : jdbc.query("select id from library_items where lower(title) = lower(?) and lower(coalesce(authors, '')) = lower(coalesce(?, '')) order by id limit 1",
                        (rs, n) -> rs.getLong(1), title.trim(), row.get("authors")).stream().findFirst().orElse(null);
                if (itemId == null) {
                    itemId = jdbc.queryForObject("""
                            insert into library_items (tenant_id, title, authors, isbn, publisher, year, edition, subject, call_number, kind)
                            values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, cut(title, 300), cut(row.get("authors"), 300), isbn,
                            cut(row.get("publisher"), 150), year, cut(row.get("edition"), 40), cut(row.get("subject"), 150), cut(row.get("callnumber"), 40), kind);
                    titles++;
                }
                seen.put(key, itemId);
            }
            String code = row.get("barcode") != null ? row.get("barcode") : row.get("accessionno") != null ? row.get("accessionno") : row.get("accessionnumber");
            if (code == null) continue;
            if (exists("select count(*) from library_copies where lower(barcode) = lower(?)", code.trim())) {
                skipped++;
                problems.add(line + "copy " + code.trim() + " is already in the catalogue");
                continue;
            }
            BigDecimal price = null;
            try {
                if (row.get("price") != null) price = new BigDecimal(row.get("price").replace(",", ""));
            } catch (NumberFormatException e) {
                problems.add(line + "price ignored (" + row.get("price") + ")");
            }
            jdbc.update("insert into library_copies (tenant_id, item_id, barcode, status, price) values (cims_current_tenant(), ?, ?, ?, ?)",
                    itemId, cut(code.trim(), 40), "REFERENCE".equals(kind) ? "IN_LIBRARY" : "AVAILABLE", price);
            copies++;
        }
        audit.record("LIBRARY_CATALOGUE_IMPORTED", "LibraryItem", null, Map.of("titles", titles, "copies", copies, "skipped", skipped));
        return new ImportResult(titles, copies, skipped, problems.size() > 200 ? problems.subList(0, 200) : problems);
    }

    // ------------------------------------------------------------------ borrowers

    /** Finds a student by registration number, or a member of staff by staff number or name. */
    @Transactional(readOnly = true)
    public List<Borrower> findBorrowers(String q) {
        if (q == null || q.trim().length() < 2) return List.of();
        String t = q.trim();
        List<Borrower> out = new ArrayList<>();
        students.findByRegistrationNoIgnoreCase(t).ifPresent(s -> out.add(student(s)));
        jdbc.query("select id from staff_members where status = 'ACTIVE' and (lower(staff_no) = lower(?) or lower(full_name) like lower(?)) order by full_name limit 10",
                (rs, n) -> rs.getLong(1), t, "%" + t + "%").forEach(id -> out.add(staff(id)));
        return out;
    }

    @Transactional(readOnly = true)
    public Borrower borrower(String type, Long id) {
        if ("STUDENT".equals(type)) return student(students.findById(id).orElseThrow(() -> new NotFoundException("Student not found")));
        if ("STAFF".equals(type)) return staff(id);
        throw new BusinessRuleException("BORROWER_TYPE", "A borrower is a student or a member of staff");
    }

    private Borrower student(Student s) {
        Settings set = settings();
        List<LoanView> loans = jdbc.query(LOAN + " where l.student_id = ? and l.returned_at is null order by l.due_on", (rs, n) -> loan(rs), s.getId());
        int overdue = (int) loans.stream().filter(l -> l.daysLate() > 0).count();
        BigDecimal unpaid = unpaidStudentFines(s.getId());
        String reason = !CAN_BORROW.contains(s.getStatus()) ? "The student is " + s.getStatus().name().toLowerCase(Locale.ROOT) + "."
                : overdue > 0 ? "Return the overdue items first."
                : loans.size() >= set.studentMaxLoans() ? "Already has " + loans.size() + " items, the most a student may borrow."
                : null;
        return new Borrower("STUDENT", s.getId(), s.fullName(), s.getRegistrationNo(), s.getStatus().name(), reason == null, reason, loans.size(),
                set.studentMaxLoans(), overdue, unpaid, loans);
    }

    private Borrower staff(Long id) {
        Settings set = settings();
        Map<String, Object> m = jdbc.queryForList("select full_name, staff_no, status from staff_members where id = ?", id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Staff member not found"));
        List<LoanView> loans = jdbc.query(LOAN + " where l.staff_id = ? and l.returned_at is null order by l.due_on", (rs, n) -> loan(rs), id);
        int overdue = (int) loans.stream().filter(l -> l.daysLate() > 0).count();
        BigDecimal unpaid = jdbc.queryForObject("select coalesce(sum(fine), 0) from library_loans where staff_id = ? and fine_status = 'TO_PAY'", BigDecimal.class, id);
        String reason = !"ACTIVE".equals(m.get("status")) ? "No longer works here."
                : overdue > 0 ? "Return the overdue items first."
                : loans.size() >= set.staffMaxLoans() ? "Already has " + loans.size() + " items, the most staff may borrow." : null;
        return new Borrower("STAFF", id, (String) m.get("full_name"), (String) m.get("staff_no"), (String) m.get("status"), reason == null, reason, loans.size(),
                set.staffMaxLoans(), overdue, unpaid, loans);
    }

    // ------------------------------------------------------------------ circulation

    @Transactional
    public LoanView issue(IssueRequest r) {
        Map<String, Object> copy = copyByBarcode(r.barcode());
        String status = (String) copy.get("status");
        if (!"AVAILABLE".equals(status)) {
            throw new BusinessRuleException("COPY_NOT_AVAILABLE", switch (status) {
                case "ON_LOAN" -> "This copy is already on loan; take it back first";
                case "IN_LIBRARY" -> "This copy is for use in the library only";
                default -> "This copy is " + status.toLowerCase(Locale.ROOT).replace('_', ' ');
            });
        }
        Borrower b = borrower(r.borrowerType(), r.borrowerId());
        if (!b.canBorrow()) throw new BusinessRuleException("CANNOT_BORROW", b.name() + " cannot borrow now. " + b.reason());
        Settings set = settings();
        LocalDate due = today().plusDays("STUDENT".equals(b.type()) ? set.studentLoanDays() : set.staffLoanDays());
        if (due.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) due = due.plusDays(1);
        Long id = jdbc.queryForObject("""
                insert into library_loans (tenant_id, copy_id, student_id, staff_id, borrower_name, due_on, issued_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?) returning id""", Long.class, copy.get("id"),
                "STUDENT".equals(b.type()) ? b.id() : null, "STAFF".equals(b.type()) ? b.id() : null, b.name(), due, CurrentUser.name());
        jdbc.update("update library_copies set status = 'ON_LOAN' where id = ?", copy.get("id"));
        audit.record("LIBRARY_ISSUED", "LibraryLoan", id, Map.of("barcode", copy.get("barcode"), "borrower", b.reference(), "due", due.toString()));
        return loan(id);
    }

    /** Takes a copy back. A late return is fined (capped); a student's fine goes on an invoice with a control number. */
    @Transactional
    public LoanView giveBack(String barcode) {
        Map<String, Object> copy = copyByBarcode(barcode);
        Long loanId = jdbc.query("select id from library_loans where copy_id = ? and returned_at is null", (rs, n) -> rs.getLong(1), copy.get("id")).stream().findFirst()
                .orElseThrow(() -> new BusinessRuleException("NOT_ON_LOAN", "Copy " + copy.get("barcode") + " is not on loan"));
        LoanView l = loan(loanId);
        BigDecimal fine = lateFine(l.dueOn());
        jdbc.update("update library_loans set returned_at = now(), returned_by_name = ?, fine = ? where id = ?", CurrentUser.name(), fine, loanId);
        jdbc.update("update library_copies set status = case when (select kind from library_items i where i.id = item_id) = 'REFERENCE' then 'IN_LIBRARY' else 'AVAILABLE' end where id = ?",
                copy.get("id"));
        if (fine.signum() > 0) charge(loanId, fine, "Late return of " + l.title() + " (" + l.barcode() + "), " + daysLate(l.dueOn()) + " days");
        audit.record("LIBRARY_RETURNED", "LibraryLoan", loanId, Map.of("barcode", copy.get("barcode"), "fine", fine));
        return loan(loanId);
    }

    @Transactional
    public LoanView renew(Long loanId) {
        LoanView l = open(loanId);
        Settings set = settings();
        if (l.daysLate() > 0) throw new BusinessRuleException("LOAN_OVERDUE", "The item is overdue; take it back (and charge the fine) instead of renewing");
        if (l.renewals() >= set.maxRenewals()) throw new BusinessRuleException("RENEWALS_USED", "This loan has been renewed the most times allowed");
        LocalDate due = today().plusDays("STUDENT".equals(l.borrowerType()) ? set.studentLoanDays() : set.staffLoanDays());
        if (due.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) due = due.plusDays(1);
        jdbc.update("update library_loans set due_on = ?, renewals = renewals + 1, reminded_due_at = null, reminded_late_at = null where id = ?", due, loanId);
        audit.record("LIBRARY_RENEWED", "LibraryLoan", loanId, Map.of("due", due.toString()));
        return loan(loanId);
    }

    /** The borrower has lost the item: the loan closes, the copy is marked lost and the price plus any late fine is charged. */
    @Transactional
    public LoanView lost(Long loanId, String note) {
        LoanView l = open(loanId);
        BigDecimal price = jdbc.queryForObject("select coalesce(price, 0) from library_copies where id = ?", BigDecimal.class, l.copyId());
        BigDecimal fine = lateFine(l.dueOn()).add(price);
        jdbc.update("update library_loans set returned_at = now(), returned_by_name = ?, lost = true, fine = ? where id = ?", CurrentUser.name(), fine, loanId);
        jdbc.update("update library_copies set status = 'LOST', note = ? where id = ?", cut("Lost by " + l.borrowerName() + (note == null || note.isBlank() ? "" : ": " + note.trim()), 300),
                l.copyId());
        if (fine.signum() > 0) charge(loanId, fine, "Lost library item " + l.title() + " (" + l.barcode() + ")");
        audit.record("LIBRARY_LOST", "LibraryLoan", loanId, Map.of("barcode", l.barcode(), "charged", fine));
        return loan(loanId);
    }

    /** Waives a fine (with the reason); a student's unpaid invoice is cancelled. A fine already paid cannot be waived. */
    @Transactional
    public LoanView waive(Long loanId, String reason) {
        LoanView l = loan(loanId);
        String why = required(reason, 300, "the reason for waiving the fine");
        if (!Set.of("BILLED", "TO_PAY").contains(l.fineStatus())) throw new BusinessRuleException("NO_FINE", "There is no unpaid fine on this loan");
        if ("BILLED".equals(l.fineStatus())) {
            Long inv = jdbc.queryForObject("select invoice_id from library_loans where id = ?", Long.class, loanId);
            if (inv != null && !billing.cancelIfUnpaid(inv, "Library fine waived: " + why)) {
                throw new BusinessRuleException("FINE_PAID", "The fine has been paid (in part); refund it through Finance instead");
            }
        }
        jdbc.update("update library_loans set fine_status = 'WAIVED', fine_note = ? where id = ?", cut("Waived by " + CurrentUser.name() + ": " + why, 300), loanId);
        audit.record("LIBRARY_FINE_WAIVED", "LibraryLoan", loanId, Map.of("fine", l.fine(), "reason", why));
        return loan(loanId);
    }

    /** A member of staff paid their fine at the cashier. */
    @Transactional
    public LoanView finePaid(Long loanId, String receipt) {
        LoanView l = loan(loanId);
        if (!"TO_PAY".equals(l.fineStatus())) throw new BusinessRuleException("NO_FINE", "There is no fine to be paid at the cashier on this loan");
        jdbc.update("update library_loans set fine_status = 'PAID', fine_note = ? where id = ?", cut("Paid, receipt " + required(receipt, 60, "the receipt number"), 300), loanId);
        audit.record("LIBRARY_FINE_PAID", "LibraryLoan", loanId, Map.of("fine", l.fine(), "receipt", receipt.trim()));
        return loan(loanId);
    }

    @Transactional(readOnly = true)
    public List<LoanView> loans(String which, String q) {
        String w = which == null ? "OPEN" : which.toUpperCase(Locale.ROOT);
        String where = switch (w) {
            case "OVERDUE" -> " where l.returned_at is null and l.due_on < ?";
            case "FINES" -> " where l.fine_status in ('BILLED', 'TO_PAY') and ?::date is not null";
            case "ALL" -> " where ?::date is not null";
            default -> " where l.returned_at is null and ?::date is not null";
        };
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.query(LOAN + where + (like == null ? "" : " and (lower(l.borrower_name) like ? or lower(c.barcode) like ? or lower(i.title) like ? "
                        + "or lower(coalesce(st.registration_no, sm.staff_no)) like ?)") + " order by " + ("ALL".equals(w) ? "l.issued_at desc" : "l.due_on") + " limit 500",
                (rs, n) -> loan(rs), like == null ? new Object[]{today()} : new Object[]{today(), like, like, like, like});
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        return jdbc.queryForObject("""
                select (select count(*) from library_items), (select count(*) from library_copies where status <> 'WITHDRAWN'),
                       (select count(*) from library_loans where returned_at is null),
                       (select count(*) from library_loans where returned_at is null and due_on < ?),
                       (select count(*) from library_copies where status = 'LOST'),
                       (select coalesce(sum(fine), 0) from library_loans where fine_status in ('BILLED', 'TO_PAY', 'PAID') and returned_at >= date_trunc('year', now())),
                       (select coalesce(sum(i.total_amount - i.paid_amount), 0) from invoices i where i.purpose = ? and i.status not in ('CANCELLED', 'PAID', 'CREDITED'))
                         + (select coalesce(sum(fine), 0) from library_loans where fine_status = 'TO_PAY')""",
                (rs, n) -> new Overview(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getBigDecimal(6), rs.getBigDecimal(7)), today(), PURPOSE);
    }

    // ------------------------------------------------------------------ students

    @Transactional(readOnly = true)
    public MyLibrary mine() {
        Long me = jdbc.query("select id from students where user_id = ?", (rs, n) -> rs.getLong(1), CurrentUser.requireId()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
        Settings s = settings();
        return new MyLibrary(jdbc.query(LOAN + " where l.student_id = ? and l.returned_at is null order by l.due_on", (rs, n) -> loan(rs), me),
                jdbc.query(LOAN + " where l.student_id = ? and l.returned_at is not null order by l.returned_at desc limit 20", (rs, n) -> loan(rs), me),
                unpaidStudentFines(me), s.studentMaxLoans(), s.studentLoanDays());
    }

    @Transactional(readOnly = true)
    public List<CatalogueEntry> catalogue(String q) {
        if (q == null || q.trim().length() < 2) return List.of();
        String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.query("""
                select i.id, i.title, i.authors, i.call_number, i.kind,
                  (select count(*) from library_copies c where c.item_id = i.id and c.status in ('AVAILABLE', 'ON_LOAN', 'IN_LIBRARY')),
                  (select count(*) from library_copies c where c.item_id = i.id and c.status = 'AVAILABLE'),
                  (select min(l.due_on) from library_loans l join library_copies c on c.id = l.copy_id where c.item_id = i.id and l.returned_at is null)
                from library_items i where lower(i.title) like ? or lower(coalesce(i.authors, '')) like ? or lower(coalesce(i.subject, '')) like ?
                order by i.title limit 50""", (rs, n) -> new CatalogueEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getInt(6), rs.getInt(7), rs.getObject(8, LocalDate.class)), like, like, like);
    }

    /** For the library clearance office: what the student still has out. */
    @Transactional(readOnly = true)
    public int outstanding(Long studentId) {
        Integer n = jdbc.queryForObject("select count(*) from library_loans where student_id = ? and returned_at is null", Integer.class, studentId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ reminders (daily job)

    /** Due-tomorrow reminders and the first overdue notice, each once per loan. Returns how many messages were sent. */
    @Transactional
    public int remind() {
        LocalDate today = today();
        Settings s = settings();
        int[] n = {0};
        jdbc.query("""
                select l.id, i.title, l.due_on, coalesce(st.phone, sm.phone) from library_loans l join library_copies c on c.id = l.copy_id
                join library_items i on i.id = c.item_id left join students st on st.id = l.student_id left join staff_members sm on sm.id = l.staff_id
                where l.returned_at is null and l.due_on = ? and l.reminded_due_at is null""", rs -> {
            messaging.event("LIBRARY_DUE_SOON", rs.getString(4), Map.of("title", rs.getString(2), "due", DAY.format(rs.getObject(3, LocalDate.class))));
            jdbc.update("update library_loans set reminded_due_at = now() where id = ?", rs.getLong(1));
            n[0]++;
        }, today.plusDays(1));
        jdbc.query("""
                select l.id, i.title, l.due_on, coalesce(st.phone, sm.phone) from library_loans l join library_copies c on c.id = l.copy_id
                join library_items i on i.id = c.item_id left join students st on st.id = l.student_id left join staff_members sm on sm.id = l.staff_id
                where l.returned_at is null and l.due_on < ? and l.reminded_late_at is null""", rs -> {
            messaging.event("LIBRARY_OVERDUE", rs.getString(4), Map.of("title", rs.getString(2), "due", DAY.format(rs.getObject(3, LocalDate.class)),
                    "finePerDay", "TZS " + s.finePerDay().setScale(0, RoundingMode.HALF_UP).toPlainString()));
            jdbc.update("update library_loans set reminded_late_at = now() where id = ?", rs.getLong(1));
            n[0]++;
        }, today);
        return n[0];
    }

    // ------------------------------------------------------------------ helpers

    private static final String ITEM = """
            select i.id, i.title, i.authors, i.isbn, i.publisher, i.year, i.edition, i.subject, i.call_number, i.kind,
              (select count(*) from library_copies c where c.item_id = i.id and c.status <> 'WITHDRAWN') as copies,
              (select count(*) from library_copies c where c.item_id = i.id and c.status = 'AVAILABLE') as available
            from library_items i""";

    private static final String LOAN = """
            select l.id, l.copy_id, c.barcode, i.title, i.call_number, case when l.student_id is null then 'STAFF' else 'STUDENT' end as btype,
                   coalesce(l.student_id, l.staff_id) as bid, l.borrower_name, coalesce(st.registration_no, sm.staff_no) as bref, l.issued_at, l.due_on, l.renewals,
                   l.returned_at, l.lost, l.fine, l.fine_status, l.fine_note,
                   (select cn.control_no from control_numbers cn where cn.invoice_id = l.invoice_id order by cn.id desc limit 1) as control_no,
                   l.issued_by_name, l.returned_by_name
            from library_loans l join library_copies c on c.id = l.copy_id join library_items i on i.id = c.item_id
            left join students st on st.id = l.student_id left join staff_members sm on sm.id = l.staff_id""";

    private ItemSummary item(ResultSet rs) throws SQLException {
        return new ItemSummary(rs.getLong("id"), rs.getString("title"), rs.getString("authors"), rs.getString("isbn"), rs.getString("publisher"),
                (Integer) rs.getObject("year"), rs.getString("edition"), rs.getString("subject"), rs.getString("call_number"), rs.getString("kind"),
                rs.getInt("copies"), rs.getInt("available"));
    }

    private LoanView loan(ResultSet rs) throws SQLException {
        LocalDate due = rs.getObject("due_on", LocalDate.class);
        Timestamp returned = rs.getTimestamp("returned_at");
        int late = returned == null ? daysLate(due) : Math.max(0, (int) ChronoUnit.DAYS.between(due, returned.toInstant().atZone(TZ).toLocalDate()));
        return new LoanView(rs.getLong("id"), rs.getLong("copy_id"), rs.getString("barcode"), rs.getString("title"), rs.getString("call_number"),
                rs.getString("btype"), rs.getLong("bid"), rs.getString("borrower_name"), rs.getString("bref"), odt(rs.getTimestamp("issued_at")), due,
                rs.getInt("renewals"), odt(returned), rs.getBoolean("lost"), late, rs.getBigDecimal("fine"), rs.getString("fine_status"), rs.getString("fine_note"),
                rs.getString("control_no"), rs.getString("issued_by_name"), rs.getString("returned_by_name"));
    }

    private LoanView loan(Long id) {
        return jdbc.query(LOAN + " where l.id = ?", (rs, n) -> loan(rs), id).stream().findFirst().orElseThrow(() -> new NotFoundException("Loan not found"));
    }

    private LoanView open(Long id) {
        LoanView l = loan(id);
        if (l.returnedAt() != null) throw new BusinessRuleException("LOAN_CLOSED", "This loan is already closed");
        return l;
    }

    private void charge(Long loanId, BigDecimal amount, String what) {
        Map<String, Object> l = jdbc.queryForMap("select student_id, staff_id from library_loans where id = ?", loanId);
        if (l.get("student_id") == null) {
            jdbc.update("update library_loans set fine_status = 'TO_PAY' where id = ?", loanId);
            return;
        }
        Student s = students.findById((Long) l.get("student_id")).orElseThrow();
        Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.STUDENT, s.getId(), s.fullName(), s.getPhone(), s.getEmail(),
                PURPOSE, loanId, what, List.of(new BillingService.NewInvoice.Line("LIBRARY", what, amount))));
        ControlNumber cn = billing.ensureControlNumber(inv.getId());
        jdbc.update("update library_loans set fine_status = 'BILLED', invoice_id = ? where id = ?", inv.getId(), loanId);
        messaging.event("LIBRARY_FINE", s.getPhone(), Map.of("title", what, "amount", "TZS " + amount.setScale(0, RoundingMode.HALF_UP).toPlainString(),
                "controlNo", cn == null || cn.getControlNo() == null ? "—" : cn.getControlNo()));
    }

    private BigDecimal unpaidStudentFines(Long studentId) {
        return jdbc.queryForObject("""
                select coalesce(sum(i.total_amount - i.paid_amount), 0) from invoices i
                where i.purpose = ? and i.payer_type = 'STUDENT' and i.payer_id = ? and i.status not in ('CANCELLED', 'PAID', 'CREDITED')""",
                BigDecimal.class, PURPOSE, studentId);
    }

    private BigDecimal lateFine(LocalDate due) {
        Settings s = settings();
        BigDecimal fine = s.finePerDay().multiply(BigDecimal.valueOf(daysLate(due)));
        return fine.min(s.maxFine()).setScale(2, RoundingMode.HALF_UP);
    }

    private int daysLate(LocalDate due) {
        return Math.max(0, (int) ChronoUnit.DAYS.between(due, today()));
    }

    private Map<String, Object> copyByBarcode(String barcode) {
        String b = barcode == null ? "" : barcode.trim();
        return jdbc.queryForList("select id, barcode, status from library_copies where lower(barcode) = lower(?)", b).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No copy has the number " + b));
    }

    private boolean exists(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n != null && n > 0;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TZ));
    }

    private static OffsetDateTime odt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static String barcode(String s) {
        String b = s == null ? "" : s.trim();
        if (!b.matches("[A-Za-z0-9/._-]{1,40}")) throw new BusinessRuleException("BARCODE_INVALID", "Give the copy's number (letters, digits, / . _ -; at most 40)");
        return b;
    }

    private static String isbn(String s) {
        if (s == null || s.isBlank()) return null;
        String d = s.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT).replaceAll("\\.0$", "");
        if (!d.matches("\\d{9}[\\dX]|\\d{13}")) throw new BusinessRuleException("ISBN_INVALID", "An ISBN has 10 or 13 digits");
        return d;
    }

    private static String required(String s, int max, String what) {
        if (s == null || s.isBlank()) throw new BusinessRuleException("FIELD_REQUIRED", "Give " + what);
        return cut(s, max);
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
