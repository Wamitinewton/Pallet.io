import { appDto } from "@/test/msw/org-team";
import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AppsBackend, CHECKOUT, PAYMENTS, renderTeamApps, WEB } from "./testing/render-apps";

describe("TeamAppsPanel", () => {
    it("lists the team's own apps, by name, as links", async () => {
        const { backend } = await renderTeamApps();

        const list = screen.getByRole("list", { name: "Apps" });
        expect(
            within(list)
                .getAllByRole("link")
                .map((link) => link.textContent),
        ).toEqual(["Checkout API"]);
        expect(within(list).getByRole("link")).toHaveAttribute("href", `/orgs/org-kilima/apps/${CHECKOUT.id}`);
        expect(Object.fromEntries(backend.sent("list")[0]?.params ?? [])).toEqual({
            teamId: PAYMENTS.id,
            page: "0",
            size: "10",
            sort: "name,asc",
        });
    });

    it("says when the team owns no apps", async () => {
        await renderTeamApps({ backend: new AppsBackend([]) });

        expect(screen.getByText(/No apps belong to this team yet/)).toBeInTheDocument();
    });

    it("links to the whole filtered list when the team has more than fit", async () => {
        const many = Array.from({ length: 12 }, (_, index) =>
            appDto({
                id: `1a2b3c4d-0000-4000-8000-${String(index).padStart(12, "0")}`,
                name: `Service ${String(index).padStart(2, "0")}`,
                slug: `service-${String(index)}`,
                teamId: WEB.id,
            }),
        );
        await renderTeamApps({ backend: new AppsBackend(many), teamId: WEB.id });

        expect(within(screen.getByRole("list", { name: "Apps" })).getAllByRole("listitem")).toHaveLength(10);
        expect(screen.getByRole("link", { name: "See all 12 apps" })).toHaveAttribute(
            "href",
            `/orgs/org-kilima/apps?team=${WEB.id}`,
        );
    });
});
