package tz.co.msingi.cims.reporting;

import java.util.List;

/** A group of reports in the catalogue. */
interface ReportProvider {
    List<ReportModel.Definition> definitions();
}
