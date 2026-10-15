package tz.co.msingi.cims.finance.gepg;

import tz.co.msingi.cims.common.exception.BusinessRuleException;

/** GePG could not be reached or gave an unusable answer: nothing was decided, so the step can be tried again. */
public class GepgUnavailableException extends BusinessRuleException {

    public GepgUnavailableException(String message, Throwable cause) {
        super("GEPG_UNAVAILABLE", message);
        if (cause != null) initCause(cause);
    }
}
