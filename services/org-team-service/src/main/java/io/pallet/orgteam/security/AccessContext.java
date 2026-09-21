package io.pallet.orgteam.security;

import io.pallet.orgteam.member.Role;
import java.time.Instant;

public record AccessContext(String orgId, String userId, Role role, Instant authTime) {}
