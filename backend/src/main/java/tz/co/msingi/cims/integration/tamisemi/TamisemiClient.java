package tz.co.msingi.cims.integration.tamisemi;

import java.util.List;

/** Port to TAMISEMI's selection system (FR-INT-08): the students it selected to the institution's programmes for a year. */
public interface TamisemiClient {

    record Selection(String indexNumber, Integer examYear, String firstName, String middleName, String surname, String sex,
                     String dateOfBirth, String nationality, String phone, String programmeCode, String reference) {
    }

    List<Selection> selections(String institutionCode, int year);
}
