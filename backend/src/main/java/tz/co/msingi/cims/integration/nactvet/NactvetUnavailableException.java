package tz.co.msingi.cims.integration.nactvet;

/** NACTVET could not be reached or refused the request; the applicant should try again later. */
public class NactvetUnavailableException extends RuntimeException {
    public NactvetUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
