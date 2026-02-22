package com.olo.worker.config;

/**
 * Thrown when config compatibility validation fails (config version, plugin contract version, or feature contract version).
 * Stops execution before breaking changes can occur.
 */
public final class ConfigIncompatibleException extends RuntimeException {

    private final ValidationResult validationResult;

    public ConfigIncompatibleException(ValidationResult validationResult) {
        super(validationResult != null ? String.join("; ", validationResult.getErrors()) : "Config validation failed");
        this.validationResult = validationResult;
    }

    public ValidationResult getValidationResult() {
        return validationResult;
    }
}
