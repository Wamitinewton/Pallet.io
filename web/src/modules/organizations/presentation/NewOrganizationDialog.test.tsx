import { asOrgId } from "@/shared/domain/ids";
import { Button } from "@/shared/presentation/ui";
import { error, ok, page } from "@/test/msw/envelopes";
import { orgDto, orgSummaryDto } from "@/test/msw/org-team";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen, waitFor, within } from "@testing-library/react";
import { http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NewOrganizationDialog } from "./NewOrganizationDialog";
import { organizationKeys } from "./queries";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const MY_ORGS_URL = bffUrl("/org-team/orgs");
const savanna = orgDto({ orgId: "org-savanna", name: "Savanna Pay", slug: "savanna-pay" });

let created: boolean;

function replyToCreate(reply: () => Response) {
    const bodies: unknown[] = [];
    server.use(
        http.post(MY_ORGS_URL, async ({ request }) => {
            bodies.push(await request.json());
            return reply();
        }),
    );
    return bodies;
}

async function openDialog() {
    const rendered = renderWithProviders(<NewOrganizationDialog trigger={<Button>New organization</Button>} />);
    await rendered.user.click(screen.getByRole("button", { name: "New organization" }));
    const dialog = await screen.findByRole("dialog", { name: "New organization" });
    return { ...rendered, dialog };
}

const nameInput = () => screen.getByLabelText("Name");
const slugInput = () => screen.getByLabelText("URL");

beforeEach(() => {
    vi.clearAllMocks();
    created = false;
    server.use(
        http.get(MY_ORGS_URL, () =>
            page(created ? [orgSummaryDto(), orgSummaryDto({ ...savanna, myRole: "OWNER" })] : [orgSummaryDto()], {
                size: 100,
            }),
        ),
    );
});

describe("NewOrganizationDialog", () => {
    it("suggests a URL from the name until the URL is edited", async () => {
        const { user } = await openDialog();

        expect(nameInput()).toHaveFocus();
        await user.type(nameInput(), "Café Nyota");
        expect(slugInput()).toHaveValue("cafe-nyota");

        await user.clear(slugInput());
        await user.type(slugInput(), "nyota");
        await user.type(nameInput(), " Labs");
        expect(slugInput()).toHaveValue("nyota");
    });

    it("creates the organization, lists it, then opens it", async () => {
        const bodies = replyToCreate(() => {
            created = true;
            return ok(savanna, "Organization created", { status: 201 });
        });
        const { user, queryClient } = await openDialog();

        await user.type(nameInput(), "Savanna Pay");
        await user.click(screen.getByRole("button", { name: "Create organization" }));

        await waitFor(() => {
            expect(router.push).toHaveBeenCalledExactlyOnceWith("/orgs/org-savanna");
        });
        expect(bodies).toEqual([{ name: "Savanna Pay", slug: "savanna-pay" }]);
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(await screen.findByText("Organization created")).toBeInTheDocument();
        expect(queryClient.getQueryData(organizationKeys.detail(asOrgId("org-savanna")))).toMatchObject({
            name: "Savanna Pay",
        });
    });

    it("leaves the URL to the backend when it is cleared", async () => {
        const bodies = replyToCreate(() => ok(savanna, "Organization created", { status: 201 }));
        const { user } = await openDialog();

        await user.type(nameInput(), "Savanna Pay");
        await user.clear(slugInput());
        await user.click(screen.getByRole("button", { name: "Create organization" }));

        await waitFor(() => {
            expect(bodies).toEqual([{ name: "Savanna Pay" }]);
        });
    });

    it("puts a taken URL on the URL field and stays open", async () => {
        replyToCreate(() => error(409, "SLUG_TAKEN", { message: "Slug already in use." }));
        const { user, dialog } = await openDialog();

        await user.type(nameInput(), "Savanna Pay");
        await user.click(screen.getByRole("button", { name: "Create organization" }));

        expect(await within(dialog).findByText("That URL is taken. Try another.")).toBeInTheDocument();
        expect(slugInput()).toHaveAttribute("aria-invalid", "true");
        expect(slugInput()).toHaveFocus();
        expect(router.push).not.toHaveBeenCalled();
    });

    it("checks the name and URL before asking the backend", async () => {
        const bodies = replyToCreate(() => ok(savanna));
        const { user } = await openDialog();

        await user.type(slugInput(), "Not A Slug");
        await user.click(screen.getByRole("button", { name: "Create organization" }));

        expect(await screen.findByText("Enter a name for the organization")).toBeInTheDocument();
        expect(
            screen.getByText("Use lowercase letters, numbers and single hyphens, with no hyphen at either end"),
        ).toBeInTheDocument();
        expect(bodies).toEqual([]);
    });

    it("explains a failure it can't place on a field", async () => {
        replyToCreate(() => error(503, "SERVICE_UNAVAILABLE"));
        const { user, dialog } = await openDialog();

        await user.type(nameInput(), "Savanna Pay");
        await user.click(screen.getByRole("button", { name: "Create organization" }));

        expect(await within(dialog).findByRole("alert")).toHaveTextContent("Pallet is temporarily unavailable.");
    });

    it("starts empty every time it opens", async () => {
        const { user, dialog } = await openDialog();

        await user.type(nameInput(), "Savanna Pay");
        await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
        await user.click(screen.getByRole("button", { name: "New organization" }));

        expect(await screen.findByLabelText("Name")).toHaveValue("");
        expect(slugInput()).toHaveValue("");
    });
});
