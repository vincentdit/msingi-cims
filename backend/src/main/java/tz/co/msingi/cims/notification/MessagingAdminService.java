package tz.co.msingi.cims.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.notification.MessagingDtos.CountRow;
import tz.co.msingi.cims.notification.MessagingDtos.MessageView;
import tz.co.msingi.cims.notification.MessagingDtos.Overview;
import tz.co.msingi.cims.notification.MessagingDtos.SettingsRequest;
import tz.co.msingi.cims.notification.MessagingDtos.TemplateRequest;
import tz.co.msingi.cims.notification.MessagingDtos.TemplateView;
import tz.co.msingi.cims.notification.MessagingDtos.TopupRequest;
import tz.co.msingi.cims.notification.MessagingDtos.TopupView;
import tz.co.msingi.cims.notification.MessagingRepositories.MessageRepository;
import tz.co.msingi.cims.notification.MessagingRepositories.MessageTemplateRepository;
import tz.co.msingi.cims.notification.MessagingRepositories.MessagingSettingsRepository;
import tz.co.msingi.cims.notification.MessagingRepositories.SmsTopupRepository;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Messaging settings, the SMS bundle, event message templates and the message log. */
@Service
@RequiredArgsConstructor
public class MessagingAdminService {

    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final MessagingSettingsRepository settingsRepo;
    private final MessageTemplateRepository templates;
    private final MessageRepository messages;
    private final SmsTopupRepository topups;
    private final AuditService audit;
    @Value("${cims.sms.mode:simulated}")
    private String gatewayMode;

    // ------------------------------------------------------------------ settings and bundle

    /** SMS parts left in the bundle. */
    @Transactional(readOnly = true)
    public long balance() {
        return topups.totalUnits() - messages.usedParts();
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        MessagingSettings s = settingsRepo.findFirstBy().orElseGet(MessagingSettings::new);
        LocalDate first = LocalDate.now(ZONE).withDayOfMonth(1);
        OffsetDateTime from = first.atStartOfDay(ZONE).toOffsetDateTime(), to = first.plusMonths(1).atStartOfDay(ZONE).toOffsetDateTime();
        List<CountRow> byStatus = messages.statusSummary(from, to).stream()
                .map(r -> new CountRow(((Message.Status) r[0]).name(), (Long) r[1], ((Number) r[2]).longValue())).toList();
        List<CountRow> byKind = messages.kindSummary(from, to).stream()
                .map(r -> new CountRow(((Message.Kind) r[0]).name(), (Long) r[1], ((Number) r[2]).longValue())).toList();
        long parts = byKind.stream().mapToLong(CountRow::parts).sum();
        long delivered = byStatus.stream().filter(r -> r.key().equals("DELIVERED")).mapToLong(CountRow::messages).sum();
        long reported = byStatus.stream().filter(r -> r.key().equals("DELIVERED") || r.key().equals("FAILED")).mapToLong(CountRow::messages).sum();
        long purchased = topups.totalUnits(), used = messages.usedParts();
        return new Overview(s.getDefaultLanguage(), s.getSenderId(), s.getPricePerSms(), s.getLowBalanceWarning(), purchased, used,
                purchased - used, purchased - used < s.getLowBalanceWarning(), byStatus, byKind,
                s.getPricePerSms().multiply(BigDecimal.valueOf(parts)),
                reported == 0 ? null : BigDecimal.valueOf(100.0 * delivered / reported).setScale(1, RoundingMode.HALF_UP).doubleValue(),
                topups.findAllByOrderByRecordedAtDesc().stream().map(MessagingAdminService::topupView).toList(), gatewayMode);
    }

    @Transactional
    public Overview updateSettings(SettingsRequest req) {
        MessagingSettings s = settingsRepo.findFirstBy().orElseGet(MessagingSettings::new);
        s.setDefaultLanguage(req.defaultLanguage());
        s.setSenderId(req.senderId() == null || req.senderId().isBlank() ? null : req.senderId().trim());
        s.setPricePerSms(req.pricePerSms());
        s.setLowBalanceWarning(req.lowBalanceWarning());
        settingsRepo.save(s);
        audit.record("MESSAGING_SETTINGS_UPDATED", "MessagingSettings", s.getId(), Map.of("language", s.getDefaultLanguage(),
                "senderId", String.valueOf(s.getSenderId()), "price", s.getPricePerSms()));
        return overview();
    }

    @Transactional
    public Overview topup(TopupRequest req) {
        SmsTopup t = new SmsTopup();
        t.setUnits(req.units());
        t.setAmountPaid(req.amountPaid());
        t.setReference(req.reference().trim());
        t.setRecordedAt(OffsetDateTime.now());
        t.setRecordedByName(CurrentUser.name());
        topups.save(t);
        audit.record("SMS_BUNDLE_TOPPED_UP", "SmsTopup", t.getId(), Map.of("units", t.getUnits(), "amount", t.getAmountPaid(), "reference", t.getReference()));
        return overview();
    }

    // ------------------------------------------------------------------ templates (FR-COM-03)

    @Transactional(readOnly = true)
    public List<TemplateView> templates() {
        return MessageTemplates.ALL.values().stream().map(d -> view(d, templates.findByCode(d.code()).orElse(null))).toList();
    }

    @Transactional
    public TemplateView saveTemplate(String code, TemplateRequest req) {
        MessageTemplates.Definition d = definition(code);
        MessageRender.check("English", req.bodyEn(), d.variables(), d.required());
        MessageRender.check("Kiswahili", req.bodySw(), d.variables(), d.required());
        if ("OTP_CODE".equals(code) && !req.enabled()) {
            throw new BusinessRuleException("CANNOT_DISABLE", "Verification codes cannot be switched off: applicants need them to sign up");
        }
        MessageTemplate t = templates.findByCode(code).orElseGet(() -> {
            MessageTemplate n = new MessageTemplate();
            n.setCode(code);
            return n;
        });
        t.setBodyEn(req.bodyEn().trim());
        t.setBodySw(req.bodySw().trim());
        t.setEnabled(req.enabled());
        t.setUpdatedByName(CurrentUser.name());
        templates.save(t);
        audit.record("MESSAGE_TEMPLATE_UPDATED", "MessageTemplate", t.getId(), Map.of("code", code, "enabled", t.isEnabled()));
        return view(d, t);
    }

    @Transactional
    public TemplateView resetTemplate(String code) {
        MessageTemplates.Definition d = definition(code);
        templates.findByCode(code).ifPresent(t -> {
            templates.delete(t);
            audit.record("MESSAGE_TEMPLATE_RESET", "MessageTemplate", t.getId(), Map.of("code", code));
        });
        return view(d, null);
    }

    private static MessageTemplates.Definition definition(String code) {
        MessageTemplates.Definition d = MessageTemplates.ALL.get(code);
        if (d == null) throw new tz.co.msingi.cims.common.exception.NotFoundException("Message template", code);
        return d;
    }

    private static TemplateView view(MessageTemplates.Definition d, MessageTemplate t) {
        String en = t == null ? d.en() : t.getBodyEn(), sw = t == null ? d.sw() : t.getBodySw();
        String sampleEn = SmsText.plain(MessageRender.render(en, d.sample(), "EN")), sampleSw = SmsText.plain(MessageRender.render(sw, d.sample(), "SW"));
        return new TemplateView(d.code(), d.name(), d.when(), d.group(), d.variables(), List.copyOf(d.required()), en, sw,
                t == null || t.isEnabled(), t != null, d.en(), d.sw(), sampleEn, sampleSw, SmsText.size(sampleEn).parts(),
                SmsText.size(sampleSw).parts(), t == null ? null : t.getUpdatedByName(), t == null ? null : t.getUpdatedAt());
    }

    // ------------------------------------------------------------------ log

    @Transactional(readOnly = true)
    public Page<MessageView> log(String status, String kind, String phone, Long campaignId, int page, int size) {
        Message.Status st = status == null || status.isBlank() ? null : Message.Status.valueOf(status);
        Message.Kind k = kind == null || kind.isBlank() ? null : Message.Kind.valueOf(kind);
        String digits = phone == null ? "" : phone.replaceAll("\\D", "").replaceFirst("^0", "");
        String p = digits.isEmpty() ? "" : "%" + digits + "%";
        return messages.search(st, k, p, campaignId, PageRequest.of(page, Math.min(size, 100))).map(MessageView::of);
    }

    private static TopupView topupView(SmsTopup t) {
        return new TopupView(t.getId(), t.getUnits(), t.getAmountPaid(), t.getReference(), t.getRecordedAt(), t.getRecordedByName());
    }
}
