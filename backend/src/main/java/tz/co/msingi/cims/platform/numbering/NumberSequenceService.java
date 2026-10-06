package tz.co.msingi.cims.platform.numbering;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

/**
 * Gap-controlled, per-institution sequences for reference, invoice and receipt numbers.
 * Uses an atomic upsert so concurrent requests never receive the same number.
 */
@Service
public class NumberSequenceService {

    @PersistenceContext
    private EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    public long next(String name) {
        Long tenantId = TenantContext.requireTenantId();
        Number value = (Number) em.createNativeQuery("""
                        INSERT INTO number_sequences (tenant_id, name, next_value) VALUES (:tenant, :name, 2)
                        ON CONFLICT (tenant_id, name) DO UPDATE SET next_value = number_sequences.next_value + 1
                        RETURNING next_value - 1""")
                .setParameter("tenant", tenantId)
                .setParameter("name", name)
                .getSingleResult();
        return value.longValue();
    }

    /** e.g. format("DEMO/APP/2027/", 42, 6) → DEMO/APP/2027/000042 */
    public static String format(String prefix, long value, int width) {
        return prefix + String.format("%0" + width + "d", value);
    }
}
