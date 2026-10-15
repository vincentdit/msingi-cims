package tz.co.msingi.cims.academic.campus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.Optional;

public final class CampusRepositories {
    private CampusRepositories() {
    }

    public interface CampusRepository extends JpaRepository<Campus, Long> {
        List<Campus> findAllByOrderByMainDescNameAsc();

        Optional<Campus> findFirstByMainTrue();

        boolean existsByCodeIgnoreCase(String code);
    }

    public interface FacultyRepository extends JpaRepository<Faculty, Long> {
        List<Faculty> findAllByOrderByNameAsc();

        boolean existsByCodeIgnoreCase(String code);
    }

    public interface StudentCategoryRepository extends JpaRepository<StudentCategory, Long> {
        List<StudentCategory> findAllByOrderByNameAsc();

        boolean existsByCodeIgnoreCase(String code);
    }

    public interface ProgrammeCampusRepository extends JpaRepository<ProgrammeCampus, Long> {
        List<ProgrammeCampus> findByProgrammeId(Long programmeId);

        List<ProgrammeCampus> findAll();

        @Modifying
        void deleteByProgrammeId(Long programmeId);
    }
}
