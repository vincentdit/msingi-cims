package tz.co.msingi.cims.platform.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByEntityAndEntityIdOrderByOccurredAtAsc(String entity, Long entityId);
}
