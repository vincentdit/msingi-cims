package tz.co.msingi.cims.notification.email;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface EmailRepository extends JpaRepository<EmailMessage, Long> {

    Page<EmailMessage> findAllByOrderByQueuedAtDesc(Pageable page);

    List<EmailMessage> findByStatusAndQueuedAtBeforeAndAttemptsLessThan(EmailMessage.Status status, OffsetDateTime before, short attempts);
}
