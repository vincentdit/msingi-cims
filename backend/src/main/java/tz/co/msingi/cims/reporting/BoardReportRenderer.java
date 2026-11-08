package tz.co.msingi.cims.reporting;

import tz.co.msingi.cims.common.document.HtmlTemplate;
import tz.co.msingi.cims.common.document.PdfRenderer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The institution overview (OverviewService) as a PDF for the Governing Board and management meetings. */
final class BoardReportRenderer {

    private static final HtmlTemplate TEMPLATE = HtmlTemplate.classpath("templates/board-report.html");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH);

    private BoardReportRenderer() {
    }

    static byte[] pdf(OverviewService.Overview o, String institution, String colour, String generatedBy) {
        OverviewService.Totals t = o.totals();
        OverviewService.Thresholds th = o.thresholds();
        Map<String, String> v = new HashMap<>();
        String year = o.year() == null ? "—" : o.year().code();
        v.put("title", "Institution overview " + year);
        v.put("year", year);
        v.put("institution", institution);
        v.put("primaryColor", colour);
        v.put("generated", "Generated " + WHEN.format(OffsetDateTime.now().atZoneSameInstant(Sql.ZONE)) + " by " + generatedBy);
        Map<String, String> m = new HashMap<>();

        m.put("tiles", "<table class=\"tiles\"><tr>"
                + tile("Current students", num(t.students()), num(t.female()) + " female · " + num(t.male()) + " male", false)
                + tile("New students", num(t.newStudents()), t.places() > 0 ? pct(t.intakeRate()) + " of " + num(t.places()) + " places" : "No places recorded",
                low(t.intakeRate(), th.lowIntake()))
                + tile("Fees collected", pct(t.collectionRate()), money(t.collected()) + " of " + money(t.billed()), low(t.collectionRate(), th.lowCollection()))
                + tile("Pass rate", pct(t.passRate()), t.assessed() > 0 ? num(t.passed()) + " of " + num(t.assessed()) + " passed" : "No results published",
                low(t.passRate(), th.lowPass()))
                + tile("Applicants per place", t.applicantsPerPlace() == null ? "—" : String.format(Locale.ENGLISH, "%.1f", t.applicantsPerPlace()),
                t.windowName() == null ? "No admission window" : num(t.applicants()) + " for " + num(t.windowPlaces()) + " places",
                low(t.applicantsPerPlace(), th.lowDemand()))
                + "</tr></table>");

        StringBuilder att = new StringBuilder();
        for (OverviewService.ProgrammeRow p : o.programmes()) {
            if (p.attention().isEmpty()) continue;
            att.append("<li><b>").append(e(p.code() + " · " + p.name())).append("</b>: ").append(e(String.join("; ", p.attention()))).append("</li>");
        }
        m.put("attention", att.isEmpty() ? "<p>Every programme is within the thresholds.</p>" : "<ul>" + att + "</ul>");

        StringBuilder pr = new StringBuilder("<table><thead><tr><th>Programme</th><th class=\"r\">Students</th><th class=\"r\">New / places</th>"
                + "<th class=\"r\">Fees collected</th><th class=\"r\">Outstanding (TZS)</th><th class=\"r\">Pass rate</th><th class=\"r\">Applicants per place</th></tr></thead><tbody>");
        for (OverviewService.ProgrammeRow p : o.programmes()) {
            pr.append("<tr><td>").append(e(p.code() + " · " + p.name())).append("</td>")
                    .append(td(num(p.students()))).append(td(p.newStudents() + " / " + (p.places() == null ? "—" : p.places())))
                    .append(td(pct(p.collectionRate()))).append(td(p.billed().signum() > 0 ? plain(p.outstanding()) : "—"))
                    .append(td(pct(p.passRate()))).append(td(p.applicantsPerPlace() == null ? "—" : String.format(Locale.ENGLISH, "%.1f", p.applicantsPerPlace())))
                    .append("</tr>");
        }
        pr.append("<tr class=\"total\"><td>All programmes</td>").append(td(num(t.students()))).append(td(t.newStudents() + " / " + (t.places() == 0 ? "—" : t.places())))
                .append(td(pct(t.collectionRate()))).append(td(plain(t.outstanding()))).append(td(pct(t.passRate())))
                .append(td(t.applicantsPerPlace() == null ? "—" : String.format(Locale.ENGLISH, "%.1f", t.applicantsPerPlace()))).append("</tr></tbody></table>");
        m.put("programmes", pr.toString());

        OverviewService.Results r = o.results();
        m.put("results", r.sheets() == 0 ? "<p>No result sheets for this year yet.</p>"
                : "<table><thead><tr><th>Main result sheets</th><th class=\"r\">Computed</th><th class=\"r\">Recommended</th><th class=\"r\">Confirmed</th>"
                + "<th class=\"r\">Published</th><th class=\"r\">Sent to NACTVET</th><th class=\"r\">Accepted by NACTVET</th></tr></thead><tbody><tr><td>"
                + num(r.sheets()) + "</td>" + td(num(r.computed())) + td(num(r.recommended())) + td(num(r.confirmed())) + td(num(r.published()))
                + td(num(r.sentToNactvet())) + td(num(r.acceptedByNactvet())) + "</tr></tbody></table>");

        OverviewService.Debts d = o.debts();
        StringBuilder db = new StringBuilder();
        if (d.debtors() == 0) {
            db.append("<p>No student owes on the year's annual fees.</p>");
        } else {
            db.append("<p>").append(num(d.debtors())).append(d.debtors() == 1 ? " student owes " : " students owe ").append(e(money(t.outstanding())))
                    .append(" on the year's annual fees.</p><table><thead><tr>");
            for (OverviewService.Point p : d.ageing()) db.append("<th class=\"r\">").append(e(p.label())).append("</th>");
            db.append("</tr></thead><tbody><tr>");
            for (OverviewService.Point p : d.ageing()) db.append(td(plain(p.value())));
            db.append("</tr></tbody></table><p class=\"muted\" style=\"margin-top: 8pt;\">Largest balances</p><table><thead><tr><th>Registration no.</th>"
                    + "<th>Student</th><th>Programme</th><th class=\"r\">Owed (TZS)</th><th class=\"r\">Days overdue</th></tr></thead><tbody>");
            for (OverviewService.Debtor x : d.largest()) {
                db.append("<tr><td>").append(e(x.registrationNo())).append("</td><td>").append(e(x.name())).append("</td><td>").append(e(x.programmeCode()))
                        .append("</td>").append(td(plain(x.balance()))).append(td(num(x.daysOverdue()))).append("</tr>");
            }
            db.append("</tbody></table>");
        }
        m.put("debts", db.toString());
        return PdfRenderer.render(TEMPLATE.render(v, m), "institution overview");
    }

    private static String tile(String label, String value, String hint, boolean low) {
        return "<td><div class=\"label\">" + e(label) + "</div><div class=\"big" + (low ? " low" : "") + "\">" + e(value) + "</div><div class=\"muted\">"
                + e(hint) + (low ? " · below target" : "") + "</div></td>";
    }

    private static String td(String s) {
        return "<td class=\"r\">" + e(s) + "</td>";
    }

    private static boolean low(Double v, double threshold) {
        return v != null && v < threshold;
    }

    private static String pct(Double d) {
        return d == null ? "—" : Math.round(d * 100) + "%";
    }

    private static String num(long n) {
        return String.format(Locale.ENGLISH, "%,d", n);
    }

    private static String plain(BigDecimal b) {
        return b == null ? "—" : String.format(Locale.ENGLISH, "%,.0f", b);
    }

    private static String money(BigDecimal b) {
        return "TZS " + plain(b);
    }

    private static String e(String s) {
        return HtmlTemplate.escape(s == null ? "" : s);
    }

}
