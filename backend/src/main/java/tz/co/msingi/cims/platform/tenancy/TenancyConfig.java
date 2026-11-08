package tz.co.msingi.cims.platform.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class TenancyConfig {

    /** Replaces Boot's default JpaTransactionManager so every transaction carries the tenant. */
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new TenantAwareJpaTransactionManager(emf);
    }

    /** Runs before the Spring Security filter chain so JWT validation can compare tenants. */
    @Bean
    public FilterRegistrationBean<TenantResolutionFilter> tenantResolutionFilter(TenantLookupService lookup) {
        FilterRegistrationBean<TenantResolutionFilter> reg = new FilterRegistrationBean<>(new TenantResolutionFilter(lookup));
        reg.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 10);
        reg.addUrlPatterns("/*");
        return reg;
    }

    /** Sign-in attempts per client address; first of all, so a flood never reaches the database. */
    @Bean
    public FilterRegistrationBean<tz.co.msingi.cims.platform.security.SignInThrottleFilter> signInThrottleFilter(
            @org.springframework.beans.factory.annotation.Value("${cims.security.sign-in-per-minute:60}") int perMinute,
            @org.springframework.beans.factory.annotation.Value("${cims.security.sign-in-per-hour:1000}") int perHour) {
        FilterRegistrationBean<tz.co.msingi.cims.platform.security.SignInThrottleFilter> reg = new FilterRegistrationBean<>(
                new tz.co.msingi.cims.platform.security.SignInThrottleFilter(perMinute, perHour, java.time.Clock.systemUTC()));
        reg.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 20);
        reg.addUrlPatterns("/api/*");
        return reg;
    }
}
