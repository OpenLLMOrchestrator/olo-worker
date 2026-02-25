package com.olo.bootstrap.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of versioned config compatibility validation.
 * Use before loading or running a pipeline to stop on breaking changes.
 */
public final class ValidationResult {

    private final boolean valid;
    private final List<String> errors;

    private ValidationResult(boolean valid, List<String> errors) {
        this.valid = valid;
        this.errors = errors != null ? Collections.unmodifiableList(new ArrayList<>(errors)) : List.of();
    }

    public static ValidationResult success() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult failure(List<String> errors) {
        return new ValidationResult(false, errors != null ? errors : List.of());
    }

    public static ValidationResult failure(String singleError) {
        return new ValidationResult(false, List.of(Objects.requireNonNull(singleError, "singleError")));
    }

    public boolean isValid() {
        return valid;
    }

    public List<String> getErrors() {
        return errors;
    }
}
