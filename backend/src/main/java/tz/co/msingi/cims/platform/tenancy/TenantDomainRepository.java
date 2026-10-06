package tz.co.msingi.cims.platform.tenancy;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantDomainRepository extends JpaRepository<TenantDomain, Long> {
    boolean existsByHostIgnoreCase(String host);
}
