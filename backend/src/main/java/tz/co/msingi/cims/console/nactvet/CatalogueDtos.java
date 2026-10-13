package tz.co.msingi.cims.console.nactvet;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class CatalogueDtos {

    private CatalogueDtos() {
    }

    public record SyncRun(Long id, OffsetDateTime startedAt, OffsetDateTime finishedAt, String status, String mode, Integer offerings,
                          Integer institutions, Integer programmes, Integer newInstitutions, Integer newProgrammes, Integer withdrawn,
                          String error, String startedBy) {
    }

    /** The catalogue as held by the platform: its source (live or simulated), size and the latest sync. */
    public record CatalogueStatus(String mode, long institutions, long programmes, long onboarded, OffsetDateTime lastSyncedAt,
                                  SyncRun latest, List<String> regions, List<String> ownerships) {
    }

    public record InstitutionRow(Long id, String nactvetId, String code, String name, String registrationNumber, String region,
                                 String district, String ownership, String mobileNumber, boolean active, long programmes,
                                 Integer minLevel, Integer maxLevel, Long tenantId, String tenantCode, OffsetDateTime lastSeenAt) {
    }

    public record InstitutionPage(List<InstitutionRow> content, long totalElements, int page, int size) {
    }

    public record ProgrammeView(Long id, String nactvetProgrammeId, String courseCode, String name, Integer ntaLevel, String board,
                                String category, String fee, String entryRequirement, String selectionCriteria, String nectaRequirement,
                                String ntaRequirement, BigDecimal ntaEntryMinGpa, Integer ntaEntryHolderLevel, Integer durationYears,
                                Integer maleCapacity, Integer femaleCapacity, Integer totalCapacity, boolean active,
                                boolean imported) {
    }

    /** What onboarding would fill in from NACTVET. */
    public record Suggestion(String code, String name, String shortName, String nactvetRegNo, String ownership, String contactPhone) {
    }

    public record InstitutionDetail(InstitutionRow institution, Suggestion suggestion, List<ProgrammeView> programmes) {
    }

    public record ImportResult(int created, int skipped, List<String> departmentsCreated, List<String> programmes) {
    }

    public record LinkRequest(Long nactvetInstitutionId, List<Long> programmeIds) {
    }
}
