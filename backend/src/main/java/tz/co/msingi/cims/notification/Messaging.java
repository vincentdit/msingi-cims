package tz.co.msingi.cims.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.notification.MessagingRepositories.MessageRepository;
import tz.co.msingi.cims.notification.MessagingRepositories.MessageTemplateRepository;
import tz.co.msingi.cims.notification.MessagingRepositories.MessagingSettingsRepository;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Sends the institution's SMS (FR-COM-01), and the same event message by email to a verified address (BR-33). Event messages are rendered from the institution's template (or the built-in
 * wording) in its message language and queued in the caller's transaction, so nothing is sent for work that rolls back.
 * Verification codes go out at once and are never stored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Messaging {

    private final MessageRepository messages;
    private final MessageTemplateRepository templates;
    private final MessagingSettingsRepository settingsRepo;
    private final TenantRepository tenants;
    private final MessageDispatcher dispatcher;
    private final SmsGateway gateway;
    private final tz.co.msingi.cims.notification.email.EmailService email;

    /** Queues the event message {@code code} for {@code phone}; nothing happens without a phone or when the institution switched it off. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void event(String code, String phone, Map<String, ?> values) {
        if (phone == null || phone.isBlank()) return;
        MessageTemplates.Definition def = MessageTemplates.require(code);
        MessageTemplate custom = templates.findByCode(code).orElse(null);
        if (custom != null && !custom.isEnabled()) return;
        String lang = settings().getDefaultLanguage();
        String text = custom != null ? ("SW".equals(lang) ? custom.getBodySw() : custom.getBodyEn()) : ("SW".equals(lang) ? def.sw() : def.en());
        Map<String, Object> v = withInstitution(values);
        String body = MessageRender.render(text, v, lang);
        queue(phone, null, Message.Kind.EVENT, code, null, lang, body);
        // the same news by email to the account's verified address (BR-33)
        email.verifiedEmailForPhone(phone).ifPresent(to -> email.queue(to, null, tz.co.msingi.cims.notification.email.EmailMessage.Kind.EVENT, code,
                def.name() + " — " + v.get("institution"), SmsText.plain(body) + "\n\n— " + v.get("institution")
                        + ("SW".equals(lang) ? " (ujumbe wa CIMS; usijibu barua pepe hii)" : " (sent by CIMS; please do not reply to this email)"), null));
    }

    /** A verification code: sent straight away (it is needed now) and logged with the code masked. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void securityCode(String phone, String code, Map<String, ?> values) {
        MessageTemplates.Definition def = MessageTemplates.require("OTP_CODE");
        MessageTemplate custom = templates.findByCode("OTP_CODE").filter(MessageTemplate::isEnabled).orElse(null);
        String lang = settings().getDefaultLanguage();
        String text = custom != null ? ("SW".equals(lang) ? custom.getBodySw() : custom.getBodyEn()) : ("SW".equals(lang) ? def.sw() : def.en());
        Map<String, Object> v = new HashMap<>(withInstitution(values));
        v.put("code", code);
        String body = MessageRender.render(text, v, lang);
        v.put("code", "******");
        Message m = newMessage(phone, null, Message.Kind.SECURITY, "OTP_CODE", null, lang, body);
        m.setBody(MessageRender.render(text, v, lang));
        messages.saveAndFlush(m);
        dispatcher.sendNow(m, SmsText.plain(body));
    }

    /**
     * An event message carrying a personal link (a workplace assessment, a survey): sent straight away and logged with the link masked,
     * so the message log cannot be used to open someone else's form.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void eventWithLink(String code, String phone, Map<String, ?> values, String linkKey) {
        if (phone == null || phone.isBlank()) return;
        MessageTemplates.Definition def = MessageTemplates.require(code);
        MessageTemplate custom = templates.findByCode(code).orElse(null);
        if (custom != null && !custom.isEnabled()) return;
        String lang = settings().getDefaultLanguage();
        String text = custom != null ? ("SW".equals(lang) ? custom.getBodySw() : custom.getBodyEn()) : ("SW".equals(lang) ? def.sw() : def.en());
        Map<String, Object> v = new HashMap<>(withInstitution(values));
        String body = MessageRender.render(text, v, lang);
        v.put(linkKey, "[personal link]");
        Message m = newMessage(phone, null, Message.Kind.EVENT, code, null, lang, body);
        m.setBody(MessageRender.render(text, v, lang));
        messages.saveAndFlush(m);
        dispatcher.sendNow(m, SmsText.plain(body));
    }

    /** Queues a bulk message (already rendered for the recipient). */
    Message queueBulk(String phone, String name, Long campaignId, String body) {
        return queue(phone, name, Message.Kind.BULK, null, campaignId, settings().getDefaultLanguage(), body);
    }

    /** Sends a message again (it was reset to QUEUED by the caller). */
    void requeue(Message m) {
        dispatcher.afterCommit(TenantContext.require(), m.getId());
    }

    private Message queue(String phone, String name, Message.Kind kind, String code, Long campaignId, String lang, String body) {
        Message m = messages.save(newMessage(phone, name, kind, code, campaignId, lang, body));
        dispatcher.afterCommit(TenantContext.require(), m.getId());
        return m;
    }

    private Message newMessage(String phone, String name, Message.Kind kind, String code, Long campaignId, String lang, String body) {
        String plain = SmsText.plain(body);
        String text = plain.length() > 700 ? plain.substring(0, 700) : plain;
        SmsText.Size size = SmsText.size(text);
        Message m = new Message();
        m.setPhone(phone);
        m.setRecipientName(name);
        m.setKind(kind);
        m.setTemplateCode(code);
        m.setCampaignId(campaignId);
        m.setLanguage(lang);
        m.setBody(text);
        m.setParts((short) Math.min(size.parts(), Short.MAX_VALUE));
        m.setEncoding(size.encoding());
        m.setQueuedAt(OffsetDateTime.now());
        return m;
    }

    MessagingSettings settings() {
        return settingsRepo.findFirstBy().orElseGet(MessagingSettings::new);
    }

    private Map<String, Object> withInstitution(Map<String, ?> values) {
        Map<String, Object> v = new HashMap<>(values);
        v.computeIfAbsent("institution", k -> tenants.findById(TenantContext.requireTenantId()).map(t -> t.getName()).orElse(""));
        return v;
    }

    SmsGateway gateway() {
        return gateway;
    }
}
