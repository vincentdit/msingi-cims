package tz.co.msingi.cims.integration.nactvet;

import java.util.List;

/**
 * Port to NACTVET's catalogue of registered institutions and their accredited programmes
 * ({@code api-out-service/institutionprogrammeall}, as used by NOAS). One row per institution-programme offering.
 * Throws {@link NactvetUnavailableException} when NACTVET cannot be reached or refuses the request.
 */
public interface NactvetCatalogueClient {

    List<Offering> fetchOfferings();

    /** One institution-programme offering, with the fields NOAS reads from the catalogue. */
    record Offering(
            String institutionId, String institutionName, String institutionCode, String region, String district,
            String registrationNumber, String mobileNumber, String ownership,
            String programmeId, String programmeName, String programmeLevel, String courseCode,
            String boardName, String boardCode, String categoryName, String categoryCode, String programmeFee,
            String entryRequirement, String selectionCriteria, String specificNectaRequirement, String specificNtaRequirement,
            String ntaEntryMinGpa, String ntaEntryExamType,
            Integer duration, Integer maleCapacity, Integer femaleCapacity, Integer totalCapacity) {
    }
}
