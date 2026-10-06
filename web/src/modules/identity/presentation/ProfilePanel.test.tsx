import { asUserId } from "@/shared/domain/ids";
import { error, ok } from "@/test/msw/envelopes";
import { userProfileDto } from "@/test/msw/identity";
import { bffUrl, server } from "@/test/msw/server";
import { createTestQueryClient, renderWithProviders } from "@/test/render";
import { screen, waitFor } from "@testing-library/react";
import { http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AccountMenu } from "./AccountMenu";
import { AccountView } from "./AccountView";
import { identityKeys } from "./queries";

const router = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn(), push: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const PROFILE_URL = bffUrl("/identity/users/me");

let storedName: string;

function deferred() {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
        release = resolve;
    });
    return { gate, release };
}

function replyToRename(reply: () => Response | Promise<Response>) {
    const bodies: unknown[] = [];
    server.use(
        http.patch(PROFILE_URL, async ({ request }) => {
            bodies.push(await request.json());
            return reply();
        }),
    );
    return bodies;
}

function renderAccount() {
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(identityKeys.profile(), {
        userId: asUserId("user-amani"),
        email: "amani@kilimalabs.co",
        displayName: storedName,
        status: "ACTIVE",
    });
    return renderWithProviders(
        <>
            <AccountMenu />
            <AccountView />
        </>,
        { queryClient },
    );
}

const nameInput = () => screen.getByLabelText("Name");
const saveButton = () => screen.getByRole("button", { name: "Save" });
const heading = () => screen.getByRole("heading", { level: 1 });
const menuButton = () => screen.getByRole("button", { name: "Account menu" });

beforeEach(() => {
    vi.clearAllMocks();
    storedName = "Amani Otieno";
    server.use(http.get(PROFILE_URL, () => ok(userProfileDto({ displayName: storedName }))));
});

describe("ProfilePanel", () => {
    it("renames at once in the page and the user menu, before the backend answers", async () => {
        const { gate, release } = deferred();
        const bodies = replyToRename(async () => {
            await gate;
            storedName = "Wanjiru Kamau";
            return ok(null, "Profile updated");
        });
        const { user } = renderAccount();

        await user.clear(nameInput());
        await user.type(nameInput(), "  Wanjiru Kamau ");
        await user.click(saveButton());

        await waitFor(() => {
            expect(heading()).toHaveTextContent("Wanjiru Kamau");
        });
        expect(menuButton()).toHaveTextContent("WK");

        release();

        expect(await screen.findByText("Profile updated")).toBeInTheDocument();
        expect(bodies).toEqual([{ displayName: "Wanjiru Kamau" }]);
        expect(saveButton()).toBeDisabled();
    });

    it("puts the old name back and says so when the rename fails", async () => {
        const { gate, release } = deferred();
        replyToRename(async () => {
            await gate;
            return error(500, "INTERNAL_ERROR");
        });
        const { user } = renderAccount();

        await user.clear(nameInput());
        await user.type(nameInput(), "Wanjiru Kamau");
        await user.click(saveButton());
        await waitFor(() => {
            expect(heading()).toHaveTextContent("Wanjiru Kamau");
        });

        release();

        expect(await screen.findByText(/Your name wasn't saved\./)).toBeInTheDocument();
        await waitFor(() => {
            expect(heading()).toHaveTextContent("Amani Otieno");
        });
        expect(menuButton()).toHaveTextContent("AO");
        expect(nameInput()).toHaveValue("Wanjiru Kamau");
        expect(saveButton()).toBeEnabled();
    });

    it("asks for a name instead of saving a blank one", async () => {
        const bodies = replyToRename(() => ok(null));
        const { user } = renderAccount();

        await user.clear(nameInput());
        await user.type(nameInput(), "   ");
        await user.click(saveButton());

        expect(await screen.findByText("Enter your name")).toBeInTheDocument();
        expect(nameInput()).toHaveAttribute("aria-invalid", "true");
        expect(bodies).toEqual([]);
    });

    it("keeps Save off until the name changes", async () => {
        const { user } = renderAccount();

        expect(saveButton()).toBeDisabled();
        await user.type(nameInput(), " ");
        expect(saveButton()).toBeDisabled();
        await user.type(nameInput(), "K");
        expect(saveButton()).toBeEnabled();
    });

    it("shows the sign-in email as confirmed and read-only", () => {
        renderAccount();

        expect(screen.getByLabelText("Email")).toHaveValue("amani@kilimalabs.co");
        expect(screen.getByLabelText("Email")).toHaveAttribute("readonly");
        expect(screen.getByText(/Confirmed\./)).toBeInTheDocument();
    });
});
