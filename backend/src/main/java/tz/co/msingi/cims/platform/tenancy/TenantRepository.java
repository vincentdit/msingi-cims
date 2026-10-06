package tz.co.msingi.cims.platform.tenancy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByCodeIgnoreCase(String code);

    @Query("select d.tenant from TenantDomain d where lower(d.host) = lower(:host)")
    Optional<Tenant> findByHost(String host);
}
