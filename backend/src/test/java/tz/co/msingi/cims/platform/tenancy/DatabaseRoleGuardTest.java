package tz.co.msingi.cims.platform.tenancy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseRoleGuardTest {

    @ParameterizedTest
    @CsvSource({"false,false,true", "true,false,false", "false,true,false", "true,true,false"})
    void onlyRolesSubjectToRowLevelSecurityAreAccepted(boolean superuser, boolean bypassRls, boolean accepted) throws Exception {
        DataSource ds = dataSource(superuser, bypassRls);
        if (accepted) {
            assertThatCode(() -> DatabaseRoleGuard.verify(ds)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> DatabaseRoleGuard.verify(ds))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    private static DataSource dataSource(boolean superuser, boolean bypassRls) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn("cims");
        when(rs.getBoolean(2)).thenReturn(superuser);
        when(rs.getBoolean(3)).thenReturn(bypassRls);
        Statement st = mock(Statement.class);
        when(st.executeQuery(anyString())).thenReturn(rs);
        Connection c = mock(Connection.class);
        when(c.createStatement()).thenReturn(st);
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(c);
        return ds;
    }
}
