package tz.co.msingi.cims.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Development SMS sender: writes messages to the log instead of sending them.
 * Replace with a real gateway adapter (and remove @Component here) for production.
 */
@Slf4j
@Component
public class LoggingSmsSender implements SmsSender {
    @Override
    public void send(String phone, String message) {
        log.info("[SMS → {}] {}", maskPhone(phone), message);
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "***";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 2);
    }
}
