package tz.co.msingi.cims.integration.http;

import tz.co.msingi.cims.common.exception.BusinessRuleException;

/** An outside system could not be reached or refused the request; nothing was changed in CIMS, so the step can be repeated. */
public class ExternalSystemUnavailableException extends BusinessRuleException {

    public ExternalSystemUnavailableException(String system, String message, Throwable cause) {
        super(system + "_UNAVAILABLE", message);
        if (cause != null) initCause(cause);
    }
}
