package tz.co.msingi.cims.reporting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.LocalDate;
import java.time.ZoneId;

/** Builds the "and ..." conditions of a report query from the filters it honours, always with bound parameters. */
final class Sql {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    final StringBuilder where = new StringBuilder();
    final MapSqlParameterSource params = new MapSqlParameterSource();

    Sql eq(String column, String name, Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            where.append(" and ").append(column).append(" = :").append(name);
            params.addValue(name, value);
        }
        return this;
    }

    /** Timestamp column within [from, to] as calendar days in Tanzania. */
    Sql period(String column, LocalDate from, LocalDate to) {
        if (from != null) {
            where.append(" and ").append(column).append(" >= :fromTs");
            params.addValue("fromTs", from.atStartOfDay(ZONE).toOffsetDateTime());
        }
        if (to != null) {
            where.append(" and ").append(column).append(" < :toTs");
            params.addValue("toTs", to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        }
        return this;
    }

    Sql raw(String condition) {
        where.append(" and ").append(condition);
        return this;
    }

    Sql param(String name, Object value) {
        params.addValue(name, value);
        return this;
    }

    @Override
    public String toString() {
        return where + " ";
    }
}
