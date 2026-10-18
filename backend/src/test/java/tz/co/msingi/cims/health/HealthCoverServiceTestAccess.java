package tz.co.msingi.cims.health;

/** Opens the package-private name check to tests in other packages. */
public final class HealthCoverServiceTestAccess {

    private HealthCoverServiceTestAccess() {
    }

    public static boolean sameSurname(String fullName, String surname) {
        return HealthCoverService.sameSurname(fullName, surname);
    }
}
