package io.pallet.orgteam.team;

import java.io.Serializable;
import java.util.UUID;

public record TeamMemberId(UUID teamId, String userId) implements Serializable {}
