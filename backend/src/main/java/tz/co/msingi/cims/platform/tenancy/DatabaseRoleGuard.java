package tz.co.msingi.cims.platform.tenancy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Refuses to start when the application connects as a PostgreSQL role that
 * bypasses row-level security (a superuser or a role with BYPASSRLS).
 * Such a role sees every institution's rows, silently disabling tenant isolation.
 * Runs as a Flyway customizer so the check happens before any migration.
 */
@Slf4j
@Configuration
public class DatabaseRoleGuard {

    static final String QUERY =
            "SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user";

    @Bean
    FlywayConfigurationCustomizer rowLevelSecurityRoleCheck(DataSource dataSource) {
        return configuration -> verify(dataSource);
    }

    static void verify(DataSource dataSource) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(QUERY)) {
            if (!rs.next()) {
                throw new IllegalStateException("Could not read the attributes of the current database role");
            }
            String role = rs.getString(1);
            if (rs.getBoolean(2) || rs.getBoolean(3)) {
                throw new IllegalStateException("Database role '" + role + "' is a superuser or has BYPASSRLS. "
                        + "PostgreSQL does not apply row-level security to such roles, so institutions would see each "
                        + "other's data. Connect as an ordinary role that owns the database "
                        + "(see README: Database role).");
            }
            log.info("Database role '{}' is subject to row-level security", role);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not verify the database role: " + e.getMessage(), e);
        }
    }
}
