package tz.co.msingi.cims.platform.audit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.util.Map;

/**
 * Records sensitive actions in the same transaction as the business change,
 * so a change and its audit record are committed (or rolled back) together.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String entity, Long entityId, Map<String, Object> details) {
        recordAs(CurrentUser.id().orElse(null), CurrentUser.name(), action, entity, entityId, details);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void recordAs(Long actorId, String actorName, String action, String entity, Long entityId, Map<String, Object> details) {
        AuditLog log = new AuditLog();
        log.setActorUserId(actorId);
        log.setActorName(actorName);
        log.setAction(action);
        log.setEntity(entity);
        log.setEntityId(entityId);
        log.setDetails(details);
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            String forwarded = req.getHeader("X-Forwarded-For");
            log.setIpAddress(forwarded != null ? forwarded.split(",")[0].trim() : req.getRemoteAddr());
        }
        repository.save(log);
    }
}
