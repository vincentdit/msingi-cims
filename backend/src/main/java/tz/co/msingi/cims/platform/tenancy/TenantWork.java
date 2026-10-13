package tz.co.msingi.cims.platform.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Runs work for one institution from outside it (the platform console, jobs): sets the institution and starts a new
 * transaction, so row-level security applies to that institution. A transaction already open on the thread is not joined,
 * because the institution is fixed when a transaction begins.
 */
@Component
public class TenantWork {

    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    public TenantWork(PlatformTransactionManager tm, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.write = new TransactionTemplate(tm);
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.read = new TransactionTemplate(tm);
        this.read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.read.setReadOnly(true);
    }

    public <T> T call(Tenant tenant, Supplier<T> work) {
        return TenantContext.callAs(tenant.toInfo(), () -> write.execute(s -> work.get()));
    }

    public void run(Tenant tenant, Runnable work) {
        TenantContext.runAs(tenant.toInfo(), () -> write.executeWithoutResult(s -> work.run()));
    }

    public <T> T read(Tenant tenant, Supplier<T> work) {
        return TenantContext.callAs(tenant.toInfo(), () -> read.execute(s -> work.get()));
    }

    /**
     * Runs work for the institution inside the transaction already open (e.g. right after creating the institution, so both
     * commit or fail together): switches row-level security to the institution, flushes the work, then switches back.
     */
    public <T> T within(Tenant tenant, Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TenantWork.within needs an open transaction");
        }
        Long previous = TenantContext.currentTenantIdOrNull();
        em.flush();
        setTenant(tenant.getId());
        try {
            T result = TenantContext.callAs(tenant.toInfo(), () -> {
                T r = work.get();
                em.flush();
                return r;
            });
            return result;
        } finally {
            setTenant(previous);
        }
    }

    private void setTenant(Long id) {
        jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, id == null ? "" : id.toString());
    }
}
