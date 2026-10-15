package tz.co.msingi.cims.platform.links;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantDomainRepository;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Links to the institution's portal sent by SMS or email to people without a CIMS account (a workplace supervisor, a graduate):
 * a random token in the link, only its hash kept, so a leaked database cannot be turned into working links.
 */
@Component
public class PortalLinks {

    private final TenantDomainRepository domains;
    private final String template;
    private final SecureRandom random = new SecureRandom();

    public PortalLinks(TenantDomainRepository domains, @Value("${cims.documents.portal-url:https://{host}}") String template) {
        this.domains = domains;
        this.template = template;
    }

    public record Token(String token, String hash) {
    }

    public Token newToken() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        return new Token(t, hash(t));
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The full address of a portal page, e.g. url("/ipt-assessment/" + token). */
    public String url(String path) {
        String host = domains.findFirstByTenantIdOrderByIdAsc(TenantContext.requireTenantId()).map(d -> d.getHost()).orElse("localhost");
        return template.replace("{host}", host).replaceAll("/+$", "") + path;
    }
}
