package io.pallet.orgteam.member;

public class MembershipNotYetProjectedException extends RuntimeException {

    public MembershipNotYetProjectedException(String orgId, String userId) {
        super("No membership for user " + userId + " in organization " + orgId + " yet");
    }
}
