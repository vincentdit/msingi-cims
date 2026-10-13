package tz.co.msingi.cims.platform.branding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface InstitutionPhotoRepository extends JpaRepository<InstitutionPhoto, Long> {

    /** What is known about the photo, without loading the image itself. */
    record PhotoInfo(Long id, String contentType, int sizeBytes, Integer width, Integer height, String caption,
                     String uploadedByName, OffsetDateTime updatedAt, Long version) {
    }

    @Query("select new tz.co.msingi.cims.platform.branding.InstitutionPhotoRepository$PhotoInfo("
            + "p.id, p.contentType, p.sizeBytes, p.width, p.height, p.caption, p.uploadedByName, p.updatedAt, p.version) "
            + "from InstitutionPhoto p where p.kind = :kind")
    Optional<PhotoInfo> info(InstitutionPhoto.Kind kind);

    Optional<InstitutionPhoto> findByKind(InstitutionPhoto.Kind kind);
}
