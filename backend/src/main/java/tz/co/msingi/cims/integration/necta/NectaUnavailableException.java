package tz.co.msingi.cims.integration.necta;

/** NECTA could not be reached or answered with an error; the applicant should try again later. */
public class NectaUnavailableException extends RuntimeException {
    public NectaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
