import { asOrgId } from "@/shared/domain/ids";
import { error, page } from "@/test/msw/envelopes";
import { orgSummaryDto } from "@/test/msw/org-team";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen, within } from "@testing-library/react";
import { http } from "msw";
import { describe, expect, it, vi } from "vitest";
import { MyOrganizationsPanel } from "./MyOrganizationsPanel";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

const MY_ORGS_URL = bffUrl("/org-team/orgs");

const kilima = orgSummaryDto();
const personal = orgSummaryDto({ orgId: "org-amani", name: "Amani Otieno", slug: "amani-otieno", kind: "PERSONAL" });
const savanna = orgSummaryDto({ orgId: "org-savanna", name: "Savanna Pay", slug: "savanna-pay", myRole: "DEVELOPER" });

const rows = () => within(screen.getByRole("list", { name: "Your organizations" })).getAllByRole("listitem");

describe("MyOrganizationsPanel", () => {
    it("lists every organization with its kind and my role, marking and linking the current one", async () => {
        server.use(http.get(MY_ORGS_URL, () => page([kilima, personal, savanna], { size: 100 })));
        renderWithProviders(<MyOrganizationsPanel currentOrgId={asOrgId("org-kilima")} />);

        await screen.findByRole("list", { name: "Your organizations" });
        const [first, second, third] = rows();
        expect(first).toHaveTextContent("Current");
        expect(first).toHaveTextContent("Owner");
        expect(screen.getByRole("link", { name: /^Kilima Labs/ })).toHaveAttribute("aria-current", "page");
        expect(second).toHaveTextContent("Personal · amani-otieno");
        expect(screen.getByRole("link", { name: /^Savanna Pay/ })).toHaveAttribute("href", "/orgs/org-savanna");
        expect(third).toHaveTextContent("Developer");
        expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
    });

    it("reads the next hundred on request", async () => {
        const pages: string[] = [];
        server.use(
            http.get(MY_ORGS_URL, ({ request }) => {
                const requested = new URL(request.url).searchParams.get("page") ?? "0";
                pages.push(requested);
                return requested === "0"
                    ? page([kilima, personal], { size: 100, totalElements: 101 })
                    : page([savanna], { page: 1, size: 100, totalElements: 101 });
            }),
        );
        const { user } = renderWithProviders(<MyOrganizationsPanel currentOrgId={asOrgId("org-kilima")} />);

        await user.click(await screen.findByRole("button", { name: "Load more" }));

        expect(await screen.findByText("Savanna Pay")).toBeInTheDocument();
        expect(rows()).toHaveLength(3);
        expect(pages).toEqual(["0", "1"]);
        expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
    });

    it("offers to try again when the list can't load", async () => {
        let attempts = 0;
        server.use(
            http.get(MY_ORGS_URL, () => {
                attempts++;
                return attempts === 1 ? error(503, "SERVICE_UNAVAILABLE") : page([kilima], { size: 100 });
            }),
        );
        const { user } = renderWithProviders(<MyOrganizationsPanel currentOrgId={asOrgId("org-kilima")} />);

        await user.click(await screen.findByRole("button", { name: "Try again" }));

        expect(await screen.findByText("Kilima Labs")).toBeInTheDocument();
    });
});
