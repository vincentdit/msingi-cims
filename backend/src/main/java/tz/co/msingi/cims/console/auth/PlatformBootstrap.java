package tz.co.msingi.cims.console.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the first platform administrator when there is none, from cims.platform.bootstrap-username and
 * cims.platform.bootstrap-password (environment CIMS_PLATFORM_BOOTSTRAP_PASSWORD). They must choose their own password at
 * first sign-in. Without a configured password nothing is created and the console cannot be used until one is set.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class PlatformBootstrap implements ApplicationRunner {

    private final PlatformUserRepository users;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    @Value("${cims.platform.bootstrap-username:msingi}")
    private String username;

    @Value("${cims.platform.bootstrap-password:}")
    private String password;

    @Value("${cims.platform.bootstrap-must-change:true}")
    private boolean mustChange;

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(s -> {
            if (users.count() > 0) return;
            if (password == null || password.isBlank()) {
                log.warn("No platform administrator exists. Set CIMS_PLATFORM_BOOTSTRAP_PASSWORD (and optionally "
                        + "CIMS_PLATFORM_BOOTSTRAP_USERNAME) and restart to create the first one.");
                return;
            }
            PlatformUser u = new PlatformUser();
            u.setUsername(username.trim().toLowerCase());
            u.setFullName("Msingi Platform Administrator");
            u.setPasswordHash(encoder.encode(password));
            u.setMustChangePassword(mustChange);
            u.setCreatedByName("bootstrap");
            users.save(u);
            log.info("Created the first platform administrator '{}'", u.getUsername());
        });
    }
}
