package tz.co.msingi.cims.platform.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

/** All queries are automatically limited to the current institution by row-level security. */
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /**
     * A successful sign-in: clears failed attempts and records the time without the optimistic-lock check, so the same person
     * signing in from two devices at once does not make one of them fail (found by the load test).
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update UserAccount u set u.failedLogins = 0, u.lastLoginAt = CURRENT_TIMESTAMP where u.id = :id")
    int recordSignIn(@org.springframework.data.repository.query.Param("id") Long id);


    Optional<UserAccount> findByUsernameIgnoreCase(String username);

    @Query("select u from UserAccount u where lower(u.username) = lower(:login) or u.phone = :login or lower(u.email) = lower(:login)")
    Optional<UserAccount> findByLogin(String login);

    boolean existsByPhone(String phone);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByPhoneAndIdNot(String phone, Long id);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    boolean existsByEmailIgnoreCase(String email);

    @Query("select distinct u from UserAccount u where u.userType = tz.co.msingi.cims.platform.security.UserAccount.UserType.STAFF order by u.fullName")
    java.util.List<UserAccount> findStaff();
}
