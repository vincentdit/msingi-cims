package tz.co.msingi.cims.platform.tenancy;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantDomainRepository extends JpaRepository<TenantDomain, Long> {
    boolean existsByHostIgnoreCase(String host);

    /** The institution's primary host name (its first registered domain), used in links printed on documents. */
    java.util.Optional<TenantDomain> findFirstByTenantIdOrderByIdAsc(Long tenantId);
}
