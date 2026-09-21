package io.pallet.orgteam.config;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;

/** Identifies which database constraint a {@link DataIntegrityViolationException} tripped. */
public final class ConstraintViolations {

    private ConstraintViolations() {}

    /** Rethrows {@code violation} unless it tripped {@code constraintName}. */
    public static void requireViolationOf(DataIntegrityViolationException violation, String constraintName) {
        if (!violates(violation, constraintName)) {
            throw violation;
        }
    }

    private static boolean violates(DataIntegrityViolationException violation, String constraintName) {
        for (Throwable t = violation; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException hibernate
                    && constraintName.equals(hibernate.getConstraintName())) {
                return true;
            }
        }
        String message = NestedExceptionUtils.getMostSpecificCause(violation).getMessage();
        return message != null && message.contains(constraintName);
    }
}
