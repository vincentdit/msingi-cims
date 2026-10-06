package tz.co.msingi.cims.common.exception;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String entity, Object id) {
        super(entity + " not found: " + id);
    }

    public NotFoundException(String message) {
        super(message);
    }
}
