package tz.co.msingi.cims.portal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.portal.PortalDtos.NoticeRequest;
import tz.co.msingi.cims.portal.PortalDtos.NoticeView;
import tz.co.msingi.cims.portal.PortalDtos.PublishRequest;
import tz.co.msingi.cims.portal.PortalRepositories.NoticeReadRepository;
import tz.co.msingi.cims.portal.PortalRepositories.NoticeRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Notices for the student portal: drafted, published (now or later, optionally by SMS) and withdrawn by staff. */
@Service
@RequiredArgsConstructor
public class NoticeService {

    /** Students who see notices and receive notice SMS. */
    static final Set<Student.Status> CURRENT = EnumSet.of(Student.Status.ADMITTED, Student.Status.ACTIVE);

    private final NoticeRepository notices;
    private final NoticeReadRepository reads;
    private final ProgrammeRepository programmes;
    private final StudentRepository students;
    private final Messaging messaging;
    private final TenantRepository tenants;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<NoticeView> list() {
        List<Student> current = currentStudents();
        OffsetDateTime now = OffsetDateTime.now();
        return notices.findAllByOrderByCreatedAtDesc().stream().map(n -> view(n, current, now)).toList();
    }

    @Transactional
    public NoticeView create(NoticeRequest req) {
        Notice n = new Notice();
        apply(n, req);
        n.setCreatedByName(CurrentUser.name());
        notices.save(n);
        audit.record("NOTICE_CREATED", "Notice", n.getId(), Map.of("title", n.getTitle()));
        return view(n, currentStudents(), OffsetDateTime.now());
    }

    @Transactional
    public NoticeView update(Long id, NoticeRequest req) {
        Notice n = require(id);
        if (n.getStatus() == Notice.Status.WITHDRAWN) {
            throw new BusinessRuleException("NOTICE_WITHDRAWN", "A withdrawn notice cannot be changed");
        }
        apply(n, req);
        audit.record("NOTICE_UPDATED", "Notice", n.getId(), Map.of("title", n.getTitle()));
        return view(n, currentStudents(), OffsetDateTime.now());
    }

    /** Publishes now or at a later time; an SMS alert goes out only when it is published now. */
    @Transactional
    public NoticeView publish(Long id, PublishRequest req) {
        Notice n = require(id);
        if (n.getStatus() != Notice.Status.DRAFT) {
            throw new BusinessRuleException("NOT_A_DRAFT", "Only a draft can be published");
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime at = req == null || req.publishAt() == null || req.publishAt().isBefore(now) ? now : req.publishAt();
        boolean later = at.isAfter(now.plusMinutes(1));
        boolean sendSms = req != null && req.sendSms();
        if (sendSms && later) {
            throw new BusinessRuleException("SMS_NEEDS_IMMEDIATE",
                    "An SMS alert can only be sent when the notice is published now. Publish it now, or schedule it without SMS.");
        }
        if (n.getExpiresAt() != null && !n.getExpiresAt().isAfter(at)) {
            throw new BusinessRuleException("ALREADY_EXPIRED", "The notice would expire before it is published");
        }
        n.setStatus(Notice.Status.PUBLISHED);
        n.setPublishAt(at);
        n.setPublishedByName(CurrentUser.name());
        List<Student> audience = audience(n, currentStudents());
        if (sendSms) {
            String inst = tenants.findById(TenantContext.requireTenantId()).map(t -> t.getShortName() == null || t.getShortName().isBlank()
                    ? t.getName() : t.getShortName()).orElse("");
            audience.forEach(s -> messaging.event("NOTICE_ALERT", s.getPhone(), Map.of("institution", inst, "title", n.getTitle())));
            n.setSmsSentCount(audience.size());
        }
        audit.record("NOTICE_PUBLISHED", "Notice", n.getId(), Map.of("title", n.getTitle(), "publishAt", at.toString(),
                "audience", audience.size(), "sms", n.getSmsSentCount()));
        return view(n, currentStudents(), now);
    }

    @Transactional
    public NoticeView withdraw(Long id) {
        Notice n = require(id);
        if (n.getStatus() != Notice.Status.PUBLISHED) {
            throw new BusinessRuleException("NOT_PUBLISHED", "Only a published notice can be withdrawn");
        }
        n.setStatus(Notice.Status.WITHDRAWN);
        n.setWithdrawnAt(OffsetDateTime.now());
        n.setWithdrawnByName(CurrentUser.name());
        audit.record("NOTICE_WITHDRAWN", "Notice", n.getId(), Map.of("title", n.getTitle()));
        return view(n, currentStudents(), OffsetDateTime.now());
    }

    private void apply(Notice n, NoticeRequest req) {
        n.setTitle(req.title().trim());
        n.setBody(req.body().trim());
        n.setCategory(req.category());
        n.setProgramme(req.programmeId() == null ? null
                : programmes.findById(req.programmeId()).orElseThrow(() -> new NotFoundException("Programme", req.programmeId())));
        n.setYearOfStudy(req.yearOfStudy());
        n.setPinned(req.pinned());
        if (req.expiresAt() != null && !req.expiresAt().isAfter(OffsetDateTime.now())) {
            throw new BusinessRuleException("INVALID_EXPIRY", "The expiry must be in the future");
        }
        n.setExpiresAt(req.expiresAt());
    }

    private List<Student> currentStudents() {
        return students.findByStatusIn(CURRENT);
    }

    static List<Student> audience(Notice n, List<Student> current) {
        return current.stream()
                .filter(s -> n.getProgramme() == null || Objects.equals(s.getProgramme().getId(), n.getProgramme().getId()))
                .filter(s -> n.getYearOfStudy() == null || s.getYearOfStudy() == n.getYearOfStudy())
                .toList();
    }

    static String audienceLabel(Notice n) {
        String who = n.getProgramme() == null ? "All students" : n.getProgramme().getName();
        return n.getYearOfStudy() == null ? who : who + ", year " + n.getYearOfStudy();
    }

    private NoticeView view(Notice n, List<Student> current, OffsetDateTime now) {
        return new NoticeView(n.getId(), n.getTitle(), n.getBody(), n.getCategory().name(),
                n.getProgramme() == null ? null : n.getProgramme().getId(), n.getProgramme() == null ? null : n.getProgramme().getName(),
                n.getYearOfStudy(), audienceLabel(n), n.isPinned(), n.getStatus().name(), n.getPublishAt(), n.getExpiresAt(), n.isLive(now),
                n.getSmsSentCount(), audience(n, current).size(), n.getId() == null ? 0 : reads.countByIdNoticeId(n.getId()),
                n.getCreatedByName(), n.getPublishedByName(), n.getWithdrawnAt(), n.getWithdrawnByName(), n.getCreatedAt());
    }

    private Notice require(Long id) {
        return notices.findById(id).orElseThrow(() -> new NotFoundException("Notice", id));
    }
}
