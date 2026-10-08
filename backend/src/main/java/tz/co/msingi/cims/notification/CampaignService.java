package tz.co.msingi.cims.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.service.PhoneNumbers;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.InvoiceRepository;
import tz.co.msingi.cims.notification.MessagingDtos.Audience;
import tz.co.msingi.cims.notification.MessagingDtos.CampaignDetail;
import tz.co.msingi.cims.notification.MessagingDtos.CampaignPreview;
import tz.co.msingi.cims.notification.MessagingDtos.CampaignRequest;
import tz.co.msingi.cims.notification.MessagingDtos.CampaignView;
import tz.co.msingi.cims.notification.MessagingDtos.MessageView;
import tz.co.msingi.cims.notification.MessagingDtos.Sample;
import tz.co.msingi.cims.notification.MessagingRepositories.MessageCampaignRepository;
import tz.co.msingi.cims.notification.MessagingRepositories.MessageRepository;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Bulk SMS to filtered groups (FR-COM-02): preview with recipients and cost, send against the bundle, delivery report, resend failed. */
@Service
@RequiredArgsConstructor
public class CampaignService {

    static final int MAX_RECIPIENTS = 5000;
    static final Map<String, List<String>> PLACEHOLDERS = Map.of(
            "STUDENTS", List.of("firstName", "fullName", "registrationNo", "programme", "balance"),
            "APPLICANTS", List.of("firstName", "fullName", "referenceNo", "programme"),
            "PHONES", List.of());

    private final Messaging messaging;
    private final MessageRepository messages;
    private final MessageCampaignRepository campaigns;
    private final MessagingAdminService admin;
    private final StudentRepository students;
    private final InvoiceRepository invoices;
    private final ApplicationRepository applications;
    private final AdmissionWindowRepository windows;
    private final ProgrammeRepository programmes;
    private final AuditService audit;

    private record Recipient(String phone, String name, Map<String, Object> values) {
    }

    private record Resolved(String label, List<Recipient> recipients, int noPhone, int duplicates, List<String> invalid) {
    }

    @Transactional(readOnly = true)
    public CampaignPreview preview(CampaignRequest req) {
        List<String> allowed = PLACEHOLDERS.get(req.audience().group());
        MessageRender.check("The message", req.body(), allowed, List.of());
        Resolved r = resolve(req.audience());
        int parts = 0;
        boolean unicode = false;
        List<Sample> samples = new ArrayList<>();
        for (Recipient x : r.recipients()) {
            String text = SmsText.plain(MessageRender.render(req.body(), x.values(), "EN"));
            SmsText.Size size = SmsText.size(text);
            parts += size.parts();
            unicode |= "UCS2".equals(size.encoding());
            if (samples.size() < 3) samples.add(new Sample(x.name(), x.phone(), text, size.parts(), size.encoding()));
        }
        MessagingSettings s = messaging.settings();
        long balance = admin.balance();
        return new CampaignPreview(r.label(), r.recipients().size(), r.noPhone(), r.duplicates(), r.invalid().stream().limit(20).toList(), allowed,
                samples, parts, s.getPricePerSms(), s.getPricePerSms().multiply(BigDecimal.valueOf(parts)), balance, balance >= parts, unicode);
    }

    @Transactional
    public CampaignView send(CampaignRequest req) {
        CampaignPreview p = preview(req);
        if (p.recipients() == 0) throw new BusinessRuleException("NO_RECIPIENTS", "Nobody in this group has a mobile number");
        if (p.recipients() > MAX_RECIPIENTS) {
            throw new BusinessRuleException("TOO_MANY_RECIPIENTS", "Send to at most " + MAX_RECIPIENTS + " people at a time; narrow the group");
        }
        if (!p.enoughBalance()) {
            throw new BusinessRuleException("INSUFFICIENT_SMS_BALANCE", "This message needs " + p.totalParts() + " SMS but the bundle has "
                    + p.balance() + " left. Top up the SMS bundle first.");
        }
        MessageCampaign c = new MessageCampaign();
        c.setTitle(req.title().trim());
        c.setBody(req.body().trim());
        c.setAudience(audienceMap(req.audience()));
        c.setAudienceLabel(p.audienceLabel());
        c.setRecipientCount(p.recipients());
        c.setSmsParts(p.totalParts());
        c.setEstimatedCost(p.estimatedCost());
        c.setSentAt(OffsetDateTime.now());
        c.setSentBy(CurrentUser.requireId());
        c.setSentByName(CurrentUser.name());
        campaigns.save(c);
        String lang = messaging.settings().getDefaultLanguage();
        for (Recipient x : resolve(req.audience()).recipients()) {
            messaging.queueBulk(x.phone(), x.name(), c.getId(), MessageRender.render(c.getBody(), x.values(), lang));
        }
        audit.record("BULK_SMS_SENT", "MessageCampaign", c.getId(), Map.of("title", c.getTitle(), "recipients", c.getRecipientCount(),
                "parts", c.getSmsParts(), "audience", c.getAudienceLabel()));
        return view(c, Map.of("QUEUED", (long) c.getRecipientCount()));
    }

    @Transactional(readOnly = true)
    public List<CampaignView> list() {
        List<MessageCampaign> list = campaigns.findAllByOrderBySentAtDesc(PageRequest.of(0, 100)).getContent();
        Map<Long, Map<String, Long>> stats = stats(list.stream().map(MessageCampaign::getId).toList());
        return list.stream().map(c -> view(c, stats.getOrDefault(c.getId(), Map.of()))).toList();
    }

    @Transactional(readOnly = true)
    public CampaignDetail get(Long id) {
        MessageCampaign c = campaigns.findById(id).orElseThrow(() -> new NotFoundException("Bulk message", id));
        return new CampaignDetail(view(c, stats(List.of(id)).getOrDefault(id, Map.of())),
                messages.findByCampaignIdOrderByIdAsc(id).stream().map(MessageView::of).toList());
    }

    /** Sends the failed messages of a bulk message again. */
    @Transactional
    public CampaignDetail resendFailed(Long id) {
        MessageCampaign c = campaigns.findById(id).orElseThrow(() -> new NotFoundException("Bulk message", id));
        List<Message> failed = messages.findByCampaignIdAndStatus(id, Message.Status.FAILED);
        if (failed.isEmpty()) throw new BusinessRuleException("NOTHING_TO_RESEND", "No message of this send has failed");
        int parts = failed.stream().mapToInt(Message::getParts).sum();
        long balance = admin.balance();
        if (balance < parts) throw new BusinessRuleException("INSUFFICIENT_SMS_BALANCE", "Resending needs " + parts + " SMS but the bundle has " + balance + " left");
        for (Message m : failed) {
            m.setStatus(Message.Status.QUEUED);
            m.setAttempts((short) 0);
            m.setError(null);
            m.setProviderRef(null);
            m.setDeliveredAt(null);
            m.setQueuedAt(OffsetDateTime.now());
            messaging.requeue(m);
        }
        audit.record("BULK_SMS_RESENT", "MessageCampaign", id, Map.of("messages", failed.size()));
        return get(c.getId());
    }

    // ------------------------------------------------------------------ audience

    private Resolved resolve(Audience a) {
        List<Recipient> raw = new ArrayList<>();
        int noPhone = 0;
        List<String> invalid = new ArrayList<>();
        String label;
        switch (a.group()) {
            case "STUDENTS" -> {
                Set<Student.Status> statuses = a.studentStatuses() == null || a.studentStatuses().isEmpty()
                        ? EnumSet.of(Student.Status.ADMITTED, Student.Status.ACTIVE)
                        : a.studentStatuses().stream().map(x -> enumValue(Student.Status.class, x)).collect(Collectors.toSet());
                Map<Long, BigDecimal> balances = new HashMap<>();
                for (Object[] row : invoices.studentBalances()) balances.put((Long) row[0], (BigDecimal) row[1]);
                for (Student s : students.findByStatusIn(statuses)) {
                    if (a.programmeId() != null && !Objects.equals(s.getProgramme().getId(), a.programmeId())) continue;
                    if (a.yearOfStudy() != null && s.getYearOfStudy() != a.yearOfStudy()) continue;
                    BigDecimal balance = balances.getOrDefault(s.getId(), BigDecimal.ZERO);
                    if (a.withFeeBalance() && balance.signum() <= 0) continue;
                    if (s.getPhone() == null || s.getPhone().isBlank()) { noPhone++; continue; }
                    Map<String, Object> v = new HashMap<>();
                    v.put("firstName", s.getFirstName());
                    v.put("fullName", s.fullName());
                    v.put("registrationNo", s.getRegistrationNo());
                    v.put("programme", s.getProgramme().getName());
                    v.put("balance", balance);
                    raw.add(new Recipient(s.getPhone(), s.fullName(), v));
                }
                label = "Students (" + statuses.stream().map(x -> x == Student.Status.ACTIVE ? "registered" : x.name().toLowerCase()).sorted()
                        .collect(Collectors.joining(", ")) + ")"
                        + (a.programmeId() == null ? "" : ", " + programmes.findById(a.programmeId()).map(p -> p.getCode()).orElse("?"))
                        + (a.yearOfStudy() == null ? "" : ", year " + a.yearOfStudy())
                        + (a.withFeeBalance() ? ", with a fee balance" : "");
            }
            case "APPLICANTS" -> {
                if (a.windowId() == null) throw new BusinessRuleException("WINDOW_REQUIRED", "Choose the admission window");
                var w = windows.findById(a.windowId()).orElseThrow(() -> new NotFoundException("Admission window", a.windowId()));
                Set<Application.Status> statuses = a.applicationStatuses() == null || a.applicationStatuses().isEmpty()
                        ? EnumSet.allOf(Application.Status.class)
                        : a.applicationStatuses().stream().map(x -> enumValue(Application.Status.class, x)).collect(Collectors.toSet());
                for (Application app : applications.findByWindowIdAndStatusInOrderByReferenceNoAsc(a.windowId(), statuses)) {
                    var ap = app.getApplicant();
                    if (ap.getPhone() == null || ap.getPhone().isBlank()) { noPhone++; continue; }
                    Map<String, Object> v = new HashMap<>();
                    v.put("firstName", ap.getFirstName());
                    v.put("fullName", ap.fullName());
                    v.put("referenceNo", app.getReferenceNo());
                    v.put("programme", app.getSelectedWindowProgramme() == null ? "" : app.getSelectedWindowProgramme().getProgramme().getName());
                    raw.add(new Recipient(ap.getPhone(), ap.fullName(), v));
                }
                label = "Applicants, " + w.getName() + (a.applicationStatuses() == null || a.applicationStatuses().isEmpty() ? ""
                        : " (" + String.join(", ", a.applicationStatuses().stream().map(x -> x.toLowerCase().replace('_', ' ')).toList()) + ")");
            }
            default -> {
                String text = a.phones() == null ? "" : a.phones();
                for (String token : text.split("[,;\\n\\r\\t]+")) {
                    String t = token.trim();
                    if (t.isEmpty()) continue;
                    try {
                        raw.add(new Recipient(PhoneNumbers.normalise(t), null, Map.of()));
                    } catch (RuntimeException e) {
                        invalid.add(t);
                    }
                }
                label = "A list of mobile numbers";
            }
        }
        Map<String, Recipient> unique = new LinkedHashMap<>();
        for (Recipient x : raw) unique.putIfAbsent(x.phone(), x);
        return new Resolved(label, new ArrayList<>(unique.values()), noPhone, raw.size() - unique.size(), invalid);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String v) {
        try {
            return Enum.valueOf(type, v);
        } catch (RuntimeException e) {
            throw new BusinessRuleException("INVALID_FILTER", "Unknown status " + v);
        }
    }

    private static Map<String, Object> audienceMap(Audience a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group", a.group());
        if (a.studentStatuses() != null) m.put("studentStatuses", a.studentStatuses());
        if (a.programmeId() != null) m.put("programmeId", a.programmeId());
        if (a.yearOfStudy() != null) m.put("yearOfStudy", a.yearOfStudy());
        if (a.withFeeBalance()) m.put("withFeeBalance", true);
        if (a.windowId() != null) m.put("windowId", a.windowId());
        if (a.applicationStatuses() != null) m.put("applicationStatuses", a.applicationStatuses());
        if (a.phones() != null) m.put("phoneCount", new LinkedHashSet<>(List.of(a.phones().split("[,;\\n\\r\\t]+"))).size());
        return m;
    }

    private Map<Long, Map<String, Long>> stats(List<Long> ids) {
        Map<Long, Map<String, Long>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Object[] row : messages.campaignStatus(ids)) {
            out.computeIfAbsent((Long) row[0], k -> new TreeMap<>()).put(((Message.Status) row[1]).name(), (Long) row[2]);
        }
        return out;
    }

    private static CampaignView view(MessageCampaign c, Map<String, Long> byStatus) {
        return new CampaignView(c.getId(), c.getTitle(), c.getBody(), c.getAudienceLabel(), c.getRecipientCount(), c.getSmsParts(),
                c.getEstimatedCost(), c.getSentAt(), c.getSentByName(), byStatus);
    }
}
