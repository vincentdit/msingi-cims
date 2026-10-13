package tz.co.msingi.cims.console.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformUserRepository extends JpaRepository<PlatformUser, Long> {

    Optional<PlatformUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    List<PlatformUser> findAllByOrderByFullNameAsc();

    long countByStatus(PlatformUser.Status status);
}
