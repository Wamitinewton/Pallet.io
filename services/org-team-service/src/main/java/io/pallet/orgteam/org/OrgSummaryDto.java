package io.pallet.orgteam.org;

import io.pallet.orgteam.member.Role;

public record OrgSummaryDto(String orgId, String name, String slug, OrgKind kind, Role myRole) {}
