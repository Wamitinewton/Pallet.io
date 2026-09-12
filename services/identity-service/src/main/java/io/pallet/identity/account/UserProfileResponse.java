package io.pallet.identity.account;

import java.util.Set;

public record UserProfileResponse(
        String sub, String orgId, Set<String> roles, String email, String displayName, UserStatus status) {}
