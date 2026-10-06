package tz.co.msingi.cims.common.exception;

/** The client exceeded a rate limit; mapped to HTTP 429. */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
