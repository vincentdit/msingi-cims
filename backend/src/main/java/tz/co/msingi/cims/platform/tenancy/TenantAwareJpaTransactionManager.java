package tz.co.msingi.cims.platform.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.sql.PreparedStatement;

/**
 * Starts every JPA transaction by telling PostgreSQL which institution it serves:
 * {@code set_config('app.tenant_id', <id>, true)}. The setting is transaction-local,
 * so pooled connections never leak a tenant. Row-level security policies use it.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        Long tenantId = TenantContext.currentTenantIdOrNull();
        EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(getEntityManagerFactory());
        if (em == null) {
            return;
        }
        em.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, tenantId == null ? "" : tenantId.toString());
                ps.execute();
            }
        });
    }
}
