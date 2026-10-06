import { asOrgId } from "@/shared/domain/ids";
import { error, ok } from "@/test/msw/envelopes";
import { orgDto } from "@/test/msw/org-team";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen } from "@testing-library/react";
import { http } from "msw";
import { describe, expect, it, vi } from "vitest";
import { OrgNavigation } from "./OrgNavigation";

vi.mock("next/navigation", () => ({ usePathname: () => "/orgs/org-kilima/members" }));

describe("OrgNavigation", () => {
    it("counts apps, members and teams from the organization and links each section", async () => {
        server.use(http.get(bffUrl("/org-team/orgs/org-kilima"), () => ok(orgDto())));
        renderWithProviders(<OrgNavigation orgId={asOrgId("org-kilima")} />);

        expect(await screen.findByRole("link", { name: "Apps, 4" })).toHaveAttribute("href", "/orgs/org-kilima/apps");
        expect(screen.getByRole("link", { name: "Members, 6" })).toHaveAttribute("aria-current", "page");
        expect(screen.getByRole("link", { name: "Teams, 3" })).toHaveAttribute("href", "/orgs/org-kilima/teams");
        expect(screen.getByRole("link", { name: "Overview" })).toHaveAttribute("href", "/orgs/org-kilima");
        expect(screen.getByRole("link", { name: "Settings" })).toHaveAttribute("href", "/orgs/org-kilima/settings");
    });

    it("still navigates, without counts, when the organization can't be read", async () => {
        let answered = false;
        server.use(
            http.get(bffUrl("/org-team/orgs/org-kilima"), () => {
                answered = true;
                return error(503, "SERVICE_UNAVAILABLE");
            }),
        );
        renderWithProviders(<OrgNavigation orgId={asOrgId("org-kilima")} />);

        await vi.waitFor(() => {
            expect(answered).toBe(true);
        });
        expect(screen.getByRole("link", { name: "Apps" })).toHaveAttribute("href", "/orgs/org-kilima/apps");
        expect(screen.getByRole("link", { name: "Members" })).toBeInTheDocument();
    });
});
