package tz.co.msingi.cims.portal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.accounts.AccountDtos.InvoiceView;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Statement;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.finance.fees.ClearanceGate;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.LoginService;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.TokenService;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.admission.service.PhoneNumbers;
import tz.co.msingi.cims.portal.PortalDtos.ChangeRequestView;
import tz.co.msingi.cims.portal.PortalDtos.Dashboard;
import tz.co.msingi.cims.portal.PortalDtos.NextPayment;
import tz.co.msingi.cims.portal.PortalDtos.ProfileView;
import tz.co.msingi.cims.portal.PortalDtos.StudentNotice;
import tz.co.msingi.cims.portal.PortalRepositories.NoticeReadRepository;
import tz.co.msingi.cims.portal.PortalRepositories.NoticeRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.FirstRegistrationRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** The student portal (FR-STU-01/02): the signed-in student's own dashboard, notices, profile and fees. */
@Service
@RequiredArgsConstructor
public class StudentPortalService {

    private final StudentRepository students;
    private final FirstRegistrationRepository firstRegistrations;
    private final NoticeRepository notices;
    private final NoticeReadRepository reads;
    private final AccountService accounts;
    private final ChangeRequestService changes;
    private final LoginService loginService;
    private final TenantRepository tenants;
    private final StudentNextSteps nextSteps;

    /** Sign-in with registration number, phone or email; only accounts handed over to Registry are students. */
    public TokenService.TokenResponse login(String login, String password) {
        String l = login.trim();
        if (l.matches("^[+0-9 ()-]{9,}$")) {
            try {
                l = PhoneNumbers.normalise(l);
            } catch (RuntimeException ignored) {
                // not a phone number: try it as given
            }
        }
        TokenService.TokenResponse t = loginService.login(l, password, UserAccount.UserType.APPLICANT);
        if (!t.user().roles().contains(Role.STUDENT.name())) {
            throw new BusinessRuleException("NOT_A_STUDENT",
                    "This account is not a student account yet. Applicants sign in to the admission portal.");
        }
        return t;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        Student s = me();
        Statement st = accounts.mine();
        NextPayment next = st.invoices().stream()
                .filter(i -> i.balance().signum() > 0 && !List.of("CANCELLED", "CREDITED").contains(i.status()))
                .min(Comparator.comparing((InvoiceView i) -> i.dueDate() == null ? java.time.LocalDate.MAX : i.dueDate()))
                .map(i -> new NextPayment(i.id(), i.invoiceNo(), i.description(), i.balance(), i.dueDate(), i.controlNo())).orElse(null);
        List<StudentNotice> list = noticesFor(s);
        String fr = firstRegistrations.findByStudentId(s.getId()).map(f -> f.getStatus().name()).orElse(null);
        List<tz.co.msingi.cims.finance.accounts.AccountDtos.Clearance> gates = Stream.of(ClearanceGate.Gate.REGISTRATION, ClearanceGate.Gate.EXAMINATION).map(g -> accounts.evaluate(s, g)).toList();
        return new Dashboard(tenants.findById(TenantContext.requireTenantId()).map(t -> t.getName()).orElse(""), s.getRegistrationNo(),
                s.fullName(), s.getFirstName(), s.getProgramme().getName(), s.getProgramme().getAwardName(), s.getYearOfStudy(),
                s.getEntryAcademicYear().getCode(), s.getStatus().name(), fr, st.billed(), st.paid(), st.balance(), st.credit(), next,
                gates, list.stream().filter(n -> !n.read()).count(), list.stream().limit(3).toList(), changes.pendingFor(s.getId()),
                nextSteps.forStudent(s, st, gates, fr));
    }

    /** Invoices, control numbers and payments; the account must belong to a student. */
    @Transactional(readOnly = true)
    public Statement account() {
        me();
        return accounts.mine();
    }

    @Transactional(readOnly = true)
    public List<StudentNotice> notices() {
        return noticesFor(me());
    }

    @Transactional
    public StudentNotice markRead(Long noticeId) {
        Student s = me();
        StudentNotice n = noticesFor(s).stream().filter(x -> x.id().equals(noticeId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Notice", noticeId));
        NoticeRead.Key key = new NoticeRead.Key(noticeId, CurrentUser.requireId());
        if (!reads.existsById(key)) {
            NoticeRead r = new NoticeRead();
            r.setId(key);
            reads.save(r);
        }
        return new StudentNotice(n.id(), n.title(), n.body(), n.category(), n.pinned(), n.publishAt(), n.publishedByName(), true);
    }

    @Transactional(readOnly = true)
    public ProfileView profile() {
        Student s = me();
        return new ProfileView(s.getRegistrationNo(), s.getFirstName(), s.getMiddleName(), s.getSurname(), s.getSex(), s.getDateOfBirth(),
                s.getNationality(), s.getPhone(), s.getEmail(), s.getRegion(), s.getDistrict(), s.getPostalAddress(), s.getDisability(),
                s.getNextOfKinName(), s.getNextOfKinPhone(), s.getNextOfKinRelation(), s.getProgramme().getName(), s.getProgramme().getCode(),
                s.getProgramme().getAwardName(), s.getEntryAcademicYear().getCode(), s.getIntake().getName(), s.getYearOfStudy(),
                s.getStatus().name(), s.getNactvetVerificationNo(), s.getAdmissionLetterNo(), StudentFields.labels(), changes.forStudent(s.getId()));
    }

    @Transactional
    public ChangeRequestView requestChange(Map<String, String> proposed, String reason) {
        return changes.submit(me(), proposed, reason);
    }

    @Transactional
    public ChangeRequestView cancelRequest(Long id) {
        return changes.cancel(me(), id);
    }

    private List<StudentNotice> noticesFor(Student s) {
        var live = notices.liveFor(OffsetDateTime.now(), s.getProgramme().getId(), s.getYearOfStudy());
        Set<Long> read = live.isEmpty() ? Set.of()
                : new HashSet<>(reads.readBy(CurrentUser.requireId(), live.stream().map(Notice::getId).toList()));
        return live.stream().map(n -> new StudentNotice(n.getId(), n.getTitle(), n.getBody(), n.getCategory().name(), n.isPinned(),
                n.getPublishAt(), n.getPublishedByName(), read.contains(n.getId()))).toList();
    }

    /** The signed-in student's record. */
    Student me() {
        return students.findByUserId(CurrentUser.requireId())
                .orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }
}
