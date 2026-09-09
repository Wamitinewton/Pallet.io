package io.pallet.notification.audience;

import java.io.Serializable;
import java.util.Objects;

public class OrgMemberId implements Serializable {

    private String orgId;
    private String userId;

    public OrgMemberId() {}

    public OrgMemberId(String orgId, String userId) {
        this.orgId = orgId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrgMemberId other)) {
            return false;
        }
        return Objects.equals(orgId, other.orgId) && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orgId, userId);
    }
}
