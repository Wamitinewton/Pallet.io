import { OrgAccessProvider } from "@/modules/members";
import { asOrgId } from "@/shared/domain/ids";
import type { Role } from "@/shared/domain/role";
import { ok, page } from "@/test/msw/envelopes";
import { sessionSummaryBody } from "@/test/msw/identity";
import { memberDto, orgDto, orgSummaryDto, type OrgDto } from "@/test/msw/org-team";
import { bffUrl, server, sessionApiUrl } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen } from "@testing-library/react";
import { http } from "msw";
import { Suspense, type ReactNode } from "react";
import { OrgSettingsView } from "../OrgSettingsView";

export const KILIMA = asOrgId("org-kilima");
export const ORG_URL = bffUrl("/org-team/orgs/org-kilima");
export const MY_ORGS_URL = bffUrl("/org-team/orgs");

export interface OrgSettingsScenario {
    readonly role?: Role;
    readonly organization?: Partial<OrgDto>;
    /** Rendered beside the page, such as the shell's switcher, to see a change reach it. */
    readonly alongside?: ReactNode;
}

/** The settings page as the org layout mounts it: inside the caller's access, with the session and lists answering. */
export async function renderOrgSettings({ role = "OWNER", organization = {}, alongside }: OrgSettingsScenario = {}) {
    const kind = organization.kind ?? "TEAM";
    server.use(
        http.get(ORG_URL, () => ok(orgDto(organization))),
        http.get(`${ORG_URL}/members/me`, () => ok(memberDto({ role }))),
        http.get(MY_ORGS_URL, () => page([orgSummaryDto({ myRole: role, kind })], { size: 100 })),
        http.get(sessionApiUrl(""), () => ok(sessionSummaryBody(), "Current session")),
    );
    const rendered = renderWithProviders(
        <OrgAccessProvider orgId={KILIMA} orgKind={kind}>
            {alongside}
            <Suspense>
                <OrgSettingsView orgId={KILIMA} />
            </Suspense>
        </OrgAccessProvider>,
    );
    await screen.findByRole("heading", { name: "Organization settings" });
    await screen.findByRole("list", { name: "Your organizations" });
    return rendered;
}
