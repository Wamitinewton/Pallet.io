package io.pallet.identity.signup;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank String organizationName,

        @NotBlank @Pattern(regexp = SLUG_PATTERN, message = SLUG_PATTERN_MESSAGE) String slug,

        @NotBlank @Email String email,
        @NotBlank String displayName,

        @NotBlank @Size(min = 12, message = "must be at least 12 characters") String password) {

    static final String SLUG_PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";
    static final String SLUG_PATTERN_MESSAGE = "must be lowercase, alphanumeric, hyphen-separated";
}
