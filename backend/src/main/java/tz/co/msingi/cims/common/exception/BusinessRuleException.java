package tz.co.msingi.cims.common.exception;

import lombok.Getter;

/**
 * A request that is well-formed but violates a business rule (e.g. window closed,
 * fee not paid). Mapped to HTTP 422 with a stable machine-readable code.
 */
@Getter
public class BusinessRuleException extends RuntimeException {
    private final String code;

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }
}
