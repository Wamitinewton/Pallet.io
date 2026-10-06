import { asOrgId } from "@/shared/domain/ids";
import type { OrgKind } from "@/shared/domain/org-kind";
import type { Capability } from "@/shared/domain/permissions";
import { ROLES, type Role } from "@/shared/domain/role";
import { ok } from "@/test/msw/envelopes";
import { memberDto } from "@/test/msw/org-team";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen } from "@testing-library/react";
import { http } from "msw";
import { describe, expect, it } from "vitest";
import { OrgAccessProvider, useOrgAccess } from "./OrgAccessProvider";

const PROBED: readonly Capability[] = ["apps.view", "apps.create", "org.rename", "invites.manage", "org.delete"];

function AccessProbe() {
    const access = useOrgAccess();
    return (
        <dl>
            <dt>role</dt>
            <dd data-testid="role">{access.role ?? "unknown"}</dd>
            <dt>user</dt>
            <dd data-testid="user">{access.userId ?? "unknown"}</dd>
            {PROBED.map((capability) => (
                <div key={capability}>
                    <dt>{capability}</dt>
                    <dd data-testid={capability}>{access.can(capability) ? "yes" : "no"}</dd>
                </div>
            ))}
        </dl>
    );
}

function replyAs(role: Role) {
    server.use(http.get(bffUrl("/org-team/orgs/org-kilima/members/me"), () => ok(memberDto({ role }))));
}

function renderProbe(orgKind: OrgKind = "TEAM") {
    return renderWithProviders(
        <OrgAccessProvider orgId={asOrgId("org-kilima")} orgKind={orgKind}>
            <AccessProbe />
        </OrgAccessProvider>,
    );
}

const allowed = () => PROBED.filter((capability) => screen.getByTestId(capability).textContent === "yes");

describe("OrgAccessProvider", () => {
    const expected: Record<Role, readonly Capability[]> = {
        VIEWER: ["apps.view"],
        DEVELOPER: ["apps.view", "apps.create"],
        ADMIN: ["apps.view", "apps.create", "org.rename", "invites.manage"],
        OWNER: PROBED,
    };

    it.each(ROLES)("lets a %s do exactly what the backend lets them", async (role) => {
        replyAs(role);
        renderProbe();

        expect(await screen.findByText(role)).toBeInTheDocument();
        expect(allowed()).toEqual(expected[role]);
        expect(screen.getByTestId("user")).toHaveTextContent("user-amani");
    });

    it("never offers to delete, or invite to, a personal organization", async () => {
        replyAs("OWNER");
        renderProbe("PERSONAL");

        expect(await screen.findByText("OWNER")).toBeInTheDocument();
        expect(allowed()).toEqual(["apps.view", "apps.create", "org.rename"]);
    });

    it("denies everything until the membership answers", () => {
        server.use(http.get(bffUrl("/org-team/orgs/org-kilima/members/me"), () => new Promise<never>(() => undefined)));
        renderProbe();

        expect(screen.getByTestId("role")).toHaveTextContent("unknown");
        expect(screen.getByTestId("user")).toHaveTextContent("unknown");
        expect(allowed()).toEqual([]);
    });

    it("refuses to be used outside an organization", () => {
        expect(() => renderWithProviders(<AccessProbe />)).toThrow("inside <OrgAccessProvider>");
    });
});
