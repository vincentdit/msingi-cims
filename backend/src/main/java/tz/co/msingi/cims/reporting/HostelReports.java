package tz.co.msingi.cims.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.reporting.ReportModel.Column;
import tz.co.msingi.cims.reporting.ReportModel.Data;
import tz.co.msingi.cims.reporting.ReportModel.Definition;
import tz.co.msingi.cims.reporting.ReportModel.Filter;

import java.util.List;
import java.util.Set;

import static tz.co.msingi.cims.reporting.ReportModel.ACADEMIC_YEAR;
import static tz.co.msingi.cims.reporting.ReportModel.SEX;

/** Hostel reports (BP-12 KPI "occupancy report always current"): occupancy by hostel and gender, and the allocation list. */
@Component
class HostelReports extends ReportQueries implements ReportProvider {

    static final Set<Role> ROLES = Set.of(Role.INSTITUTION_ADMIN, Role.HOSTEL_WARDEN, Role.REGISTRAR, Role.BURSAR, Role.ACCOUNTANT,
            Role.MANAGEMENT_VIEWER, Role.AUDITOR);

    HostelReports(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public List<Definition> definitions() {
        return List.of(
                new Definition("HST-01", "Hostel", "Hostel occupancy",
                        "Per hostel and gender: rooms, beds, checked in, allocated awaiting payment or check-in, free and out of service.",
                        ROLES, false, List.of(), this::occupancy),
                new Definition("HST-02", "Hostel", "Hostel allocations",
                        "Allocations by academic year: student, bed, fee, payment, check-in and check-out, damages charged.",
                        ROLES, true, List.of(ACADEMIC_YEAR, SEX), this::allocations));
    }

    private Data occupancy(Filter f) {
        Sql q = new Sql();
        List<List<Object>> r = rows("""
                select h.name, r.gender, count(distinct r.id), count(b.id),
                       count(a.id) filter (where a.status = 'CHECKED_IN'), count(a.id) filter (where a.status = 'ALLOCATED'),
                       count(b.id) filter (where a.id is null and b.status = 'AVAILABLE' and r.status = 'AVAILABLE' and h.active),
                       count(b.id) filter (where a.id is null and not (b.status = 'AVAILABLE' and r.status = 'AVAILABLE' and h.active))
                from hostels h join hostel_rooms r on r.hostel_id = h.id join hostel_beds b on b.room_id = r.id
                left join hostel_allocations a on a.bed_id = b.id and a.status in ('ALLOCATED', 'CHECKED_IN')
                group by h.name, r.gender order by h.name, r.gender""", q);
        r.forEach(x -> {
            x.set(1, "FEMALE".equals(x.get(1)) ? "Female" : "Male");
            x.add(ratio(num(x.get(4)) + num(x.get(5)), x.get(3)));
        });
        return new Data(List.of(Column.text("hostel", "Hostel"), Column.text("gender", "Gender"), Column.number("rooms", "Rooms"),
                Column.number("beds", "Beds"), Column.number("checkedIn", "Checked in"), Column.number("allocated", "Allocated, not checked in"),
                Column.number("free", "Free"), Column.number("out", "Out of service"), Column.percent("occupancy", "Taken")), r);
    }

    private Data allocations(Filter f) {
        Sql q = new Sql().eq("w.academic_year_id", "year", f.academicYearId()).eq("s.sex", "sex", f.sex());
        List<List<Object>> r = rows("""
                select w.name, s.registration_no, s.surname, concat_ws(' ', s.first_name, s.middle_name), s.sex, h.name,
                       concat_ws(' · ', nullif(r.block, ''), 'Room ' || r.room_no), b.label, a.status, a.fee, i.status, a.pay_by,
                       a.checked_in_at, a.checked_out_at, a.damage_charge
                from hostel_allocations a join hostel_windows w on w.id = a.window_id join students s on s.id = a.student_id
                join hostel_beds b on b.id = a.bed_id join hostel_rooms r on r.id = b.room_id join hostels h on h.id = r.hostel_id
                left join invoices i on i.id = a.invoice_id
                where true""" + q + " order by w.opens_on desc, h.name, r.room_no, b.label", q);
        r.forEach(x -> {
            x.set(8, AdmissionReports.label(String.valueOf(x.get(8))));
            x.set(10, x.get(10) == null ? "No fee" : AdmissionReports.label(String.valueOf(x.get(10))));
        });
        return new Data(List.of(Column.text("window", "Window"), Column.text("regNo", "Registration no."), Column.text("surname", "Surname"),
                Column.text("names", "Other names"), Column.text("sex", "Sex"), Column.text("hostel", "Hostel"), Column.text("room", "Room"),
                Column.text("bed", "Bed"), Column.text("status", "Status"), Column.money("fee", "Fee"), Column.text("payment", "Invoice"),
                Column.date("payBy", "Pay by"), Column.dateTime("in", "Checked in"), Column.dateTime("out", "Checked out"),
                Column.money("damages", "Damages charged")), r);
    }
}
