package tz.co.msingi.cims.organisation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public final class OrgRepositories {

    private OrgRepositories() {
    }

    public interface OrgUnitRepository extends JpaRepository<OrgUnit, Long> {
        List<OrgUnit> findAllByOrderBySortOrderAscIdAsc();

        boolean existsByParentId(Long parentId);
    }

    public interface OrgPositionRepository extends JpaRepository<OrgPosition, Long> {
        List<OrgPosition> findAllByOrderBySortOrderAscIdAsc();

        List<OrgPosition> findByUnitId(Long unitId);
    }

    public interface OrgSettingsRepository extends JpaRepository<OrgSettings, Long> {
        Optional<OrgSettings> findFirstByOrderByIdAsc();
    }
}
