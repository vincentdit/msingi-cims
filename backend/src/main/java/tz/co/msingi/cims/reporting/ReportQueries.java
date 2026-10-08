package tz.co.msingi.cims.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Runs report SQL. Called inside the report's transaction, so row-level security limits every query to the institution. */
abstract class ReportQueries {

    protected final NamedParameterJdbcTemplate jdbc;

    protected ReportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Each result row as a list of values (timestamps as OffsetDateTime, dates as LocalDate). */
    protected List<List<Object>> rows(String sql, Sql q) {
        return jdbc.query(sql, q.params, (rs, i) -> row(rs));
    }

    static List<Object> row(ResultSet rs) throws SQLException {
        int n = rs.getMetaData().getColumnCount();
        List<Object> out = new ArrayList<>(n);
        for (int c = 1; c <= n; c++) {
            Object o = rs.getObject(c);
            if (o instanceof Timestamp) o = rs.getObject(c, OffsetDateTime.class);
            else if (o instanceof java.sql.Date d) o = d.toLocalDate();
            else if (o instanceof Integer i) o = i.longValue();
            else if (o instanceof Short s) o = s.longValue();
            out.add(o);
        }
        return out;
    }

    static double ratio(Object num, Object den) {
        long d = den instanceof Number n ? n.longValue() : 0;
        return d == 0 ? 0 : (num instanceof Number n ? n.doubleValue() : 0) / d;
    }

    static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
