package tz.co.msingi.cims.admission.letter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AdmissionLetterRepository extends JpaRepository<AdmissionLetter, Long> {

    Optional<AdmissionLetter> findByApplicationIdAndStatus(Long applicationId, AdmissionLetter.Status status);

    List<AdmissionLetter> findByApplicationIdInAndStatus(Collection<Long> applicationIds, AdmissionLetter.Status status);

    List<AdmissionLetter> findByApplicationIdOrderByEditionDesc(Long applicationId);

    Optional<AdmissionLetter> findByVerificationCode(String verificationCode);

    @Query("select coalesce(max(l.edition), 0) from AdmissionLetter l where l.application.id = :applicationId")
    int maxEdition(Long applicationId);
}
