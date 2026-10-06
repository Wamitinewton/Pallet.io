import { asOrgId } from "@/shared/domain/ids";
import { error, ok, page } from "@/test/msw/envelopes";
import { orgDto, orgSummaryDto } from "@/test/msw/org-team";
import { server } from "@/test/msw/server";
import { screen, waitFor } from "@testing-library/react";
import { http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { OrgSwitcher } from "./OrgSwitcher";
import { MY_ORGS_URL, ORG_URL, renderOrgSettings } from "./testing/render-org-settings";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/orgs/org-kilima/settings" }));

let storedName: string;

function replyToRename(reply: () => Response) {
    const bodies: unknown[] = [];
    server.use(
        http.patch(ORG_URL, async ({ request }) => {
            bodies.push(await request.json());
            return reply();
        }),
    );
    return bodies;
}

function serveStoredName() {
    server.use(
        http.get(ORG_URL, () => ok(orgDto({ name: storedName }))),
        http.get(MY_ORGS_URL, () => page([orgSummaryDto({ name: storedName })], { size: 100 })),
    );
}

const nameInput = () => screen.getByLabelText("Name");
const saveButton = () => screen.getByRole("button", { name: "Save" });

beforeEach(() => {
    vi.clearAllMocks();
    storedName = "Kilima Labs";
});

describe("RenameOrgPanel", () => {
    it("saves the trimmed name, then the organization, my list and the switcher all show it", async () => {
        const bodies = replyToRename(() => {
            storedName = "Kilima Cloud";
            return ok(orgDto({ name: storedName }), "Organization updated");
        });
        const { user } = await renderOrgSettings({
            alongside: <OrgSwitcher activeOrgId={asOrgId("org-kilima")} />,
        });
        serveStoredName();

        await user.clear(nameInput());
        await user.type(nameInput(), "  Kilima Cloud ");
        await user.click(saveButton());

        expect(await screen.findByText("Organization renamed")).toBeInTheDocument();
        expect(bodies).toEqual([{ name: "Kilima Cloud" }]);
        expect(screen.getByRole("button", { name: "Delete Kilima Cloud" })).toBeInTheDocument();
        await waitFor(() => {
            expect(screen.getByRole("button", { name: /^Kilima Cloud, Team/ })).toBeInTheDocument();
        });
        await waitFor(() => {
            expect(screen.getByRole("list", { name: "Your organizations" })).toHaveTextContent("Kilima Cloud");
        });
        expect(nameInput()).toHaveValue("Kilima Cloud");
        expect(saveButton()).toBeDisabled();
    });

    it("keeps Save off until the name changes, ignoring surrounding blanks", async () => {
        const { user } = await renderOrgSettings();

        expect(saveButton()).toBeDisabled();
        await user.type(nameInput(), " ");
        expect(saveButton()).toBeDisabled();
        await user.type(nameInput(), "X");
        expect(saveButton()).toBeEnabled();
    });

    it("asks for a name instead of saving a blank one", async () => {
        const bodies = replyToRename(() => ok(orgDto()));
        const { user } = await renderOrgSettings();

        await user.clear(nameInput());
        await user.type(nameInput(), "   ");
        await user.click(saveButton());

        expect(await screen.findByText("Enter a name for the organization")).toBeInTheDocument();
        expect(nameInput()).toHaveAttribute("aria-invalid", "true");
        expect(bodies).toEqual([]);
    });

    it("places the backend's field error on the name", async () => {
        replyToRename(() =>
            error(400, "VALIDATION_ERROR", {
                message: "Validation failed.",
                validationErrors: [{ field: "name", message: "must not contain control characters" }],
            }),
        );
        const { user } = await renderOrgSettings();

        await user.type(nameInput(), " Cloud");
        await user.click(saveButton());

        expect(await screen.findByText("must not contain control characters")).toBeInTheDocument();
        expect(screen.queryByText(/The name wasn't saved\./)).not.toBeInTheDocument();
    });

    it("says so, and keeps the old name, when the rename fails", async () => {
        replyToRename(() => error(503, "SERVICE_UNAVAILABLE"));
        const { user } = await renderOrgSettings();

        await user.type(nameInput(), " Cloud");
        await user.click(saveButton());

        expect(await screen.findByText(/The name wasn't saved\./)).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Delete Kilima Labs" })).toBeInTheDocument();
        expect(nameInput()).toHaveValue("Kilima Labs Cloud");
        expect(saveButton()).toBeEnabled();
    });

    it.each(["DEVELOPER", "VIEWER"] as const)("shows a %s the name without a way to change it", async (role) => {
        await renderOrgSettings({ role });

        expect(nameInput()).toHaveAttribute("readonly");
        expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
    });
});
