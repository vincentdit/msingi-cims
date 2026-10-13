package tz.co.msingi.cims.finance.sponsors;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public final class SponsorRepositories {

    private SponsorRepositories() {
    }

    public interface SponsorRepository extends JpaRepository<Sponsor, Long> {
        List<Sponsor> findAllByOrderByNameAsc();

        Optional<Sponsor> findByCodeIgnoreCase(String code);
    }

    public interface SponsorshipRepository extends JpaRepository<Sponsorship, Long> {
        List<Sponsorship> findBySponsorIdAndAcademicYearIdOrderByIdAsc(Long sponsorId, Long academicYearId);

        List<Sponsorship> findBySponsorIdOrderByIdDesc(Long sponsorId);

        Optional<Sponsorship> findByStudentIdAndAcademicYearIdAndStatus(Long studentId, Long academicYearId, Sponsorship.Status status);

        long countBySponsorIdAndStatus(Long sponsorId, Sponsorship.Status status);
    }
}
