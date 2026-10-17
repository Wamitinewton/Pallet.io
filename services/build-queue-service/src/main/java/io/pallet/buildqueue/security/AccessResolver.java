package io.pallet.buildqueue.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.buildqueue.projection.MembershipProjection;
import io.pallet.buildqueue.projection.MembershipProjectionRepository;
import io.pallet.buildqueue.projection.MembershipProjectionRepository.MembershipAccess;
import io.pallet.buildqueue.security.AccessExceptions.NotAMemberException;
import io.pallet.buildqueue.security.AccessExceptions.OrgNotFoundException;
import io.pallet.common.error.AppException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The membership gate (ADR-0018): the org comes from the path, the caller from {@code sub}, the role from the local
 * read model. Decisions are memoized for one request only.
 */
@Component
public class AccessResolver {

    public static final String AUTHZ_DENIED = "build_queue.authz.denied";
    public static final String TAG_REASON = "reason";

    public static final String REASON_ORG_DELETED = "org_deleted";
    public static final String REASON_NO_MEMBERSHIP = "no_membership";
    public static final String REASON_NOT_A_MEMBER = "not_a_member";
    public static final String REASON_INSUFFICIENT_ROLE = "insufficient_role";

    private static final List<String> REASONS =
            List.of(REASON_ORG_DELETED, REASON_NO_MEMBERSHIP, REASON_NOT_A_MEMBER, REASON_INSUFFICIENT_ROLE);

    private static final String REQUEST_ATTRIBUTE = AccessResolver.class.getName() + ".CONTEXT";

    private final MembershipProjectionRepository memberships;
    private final MeterRegistry meterRegistry;

    AccessResolver(MembershipProjectionRepository memberships, MeterRegistry meterRegistry) {
        this.memberships = memberships;
        this.meterRegistry = meterRegistry;
        REASONS.forEach(reason -> meterRegistry.counter(AUTHZ_DENIED, TAG_REASON, reason));
    }

    /**
     * @throws OrgNotFoundException when the caller has no membership row or the org is deleted
     * @throws NotAMemberException when the caller's membership is removed or the token has no subject
     */
    public AccessContext resolve(String pathOrgId) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null
                && request.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
                        instanceof AccessContext memo
                && memo.orgId().equals(pathOrgId)) {
            return memo;
        }
        AccessContext resolved = load(pathOrgId);
        if (request != null) {
            request.setAttribute(REQUEST_ATTRIBUTE, resolved, RequestAttributes.SCOPE_REQUEST);
        }
        return resolved;
    }

    AppException denied(String reason, AppException exception) {
        meterRegistry.counter(AUTHZ_DENIED, TAG_REASON, reason).increment();
        return exception;
    }

    private AccessContext load(String pathOrgId) {
        String userId = subject();
        MembershipAccess access = memberships.findAccess(pathOrgId, userId);
        if (access.getDeleted()) {
            throw denied(REASON_ORG_DELETED, new OrgNotFoundException());
        }
        if (access.getStatus() == null) {
            throw denied(REASON_NO_MEMBERSHIP, new OrgNotFoundException());
        }
        if (!MembershipProjection.Status.ACTIVE.name().equals(access.getStatus())) {
            throw denied(REASON_NOT_A_MEMBER, new NotAMemberException());
        }
        return new AccessContext(pathOrgId, userId, Role.fromReadModel(access.getRole()));
    }

    private String subject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String subject =
                authentication != null && authentication.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : null;
        if (subject != null && !subject.isBlank()) {
            return subject;
        }
        throw denied(REASON_NOT_A_MEMBER, new NotAMemberException());
    }
}
