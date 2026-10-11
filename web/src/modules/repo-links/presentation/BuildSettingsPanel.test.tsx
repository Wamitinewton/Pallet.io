import { error } from "@/test/msw/envelopes";
import { repoLinkDto } from "@/test/msw/git-integration";
import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { APP_PAGE, KEVIN, renderAppPage, RepoLinksBackend } from "./testing/render-repo-links";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => APP_PAGE }));

const panel = () => screen.getByRole("region", { name: "Build settings" });
const branch = () => within(panel()).getByLabelText("Production branch");
const rootDirectory = () => within(panel()).getByLabelText("Root directory");
const autoDeploy = () => within(panel()).getByRole("switch", { name: "Build every push" });
const save = () => within(panel()).getByRole("button", { name: "Save changes" });

beforeEach(() => {
    vi.clearAllMocks();
});

describe("BuildSettingsPanel", () => {
    it("starts from the link's settings, with nothing to save", async () => {
        await renderAppPage({ backend: new RepoLinksBackend(repoLinkDto({ rootDirectory: "apps/web" })) });

        expect(branch()).toHaveValue("main");
        expect(rootDirectory()).toHaveValue("apps/web");
        expect(autoDeploy()).toBeChecked();
        expect(save()).toBeDisabled();
    });

    it("sends only the changed field and the version it read, never an id", async () => {
        const { user, backend } = await renderAppPage();

        await user.clear(branch());
        await user.type(branch(), "release");
        await user.click(save());

        expect(await screen.findByText("Build settings saved")).toBeInTheDocument();
        expect(backend.sent("tune").map(({ raw }) => raw)).toEqual(['{"productionBranch":"release","version":3}']);
        expect(save()).toBeDisabled();
    });

    it("sends an empty root directory to build from the repository root", async () => {
        const { user, backend } = await renderAppPage({
            backend: new RepoLinksBackend(repoLinkDto({ rootDirectory: "apps/web" })),
        });

        await user.clear(rootDirectory());
        await user.click(save());

        await waitFor(() => {
            expect(backend.sent("tune").map(({ raw }) => raw)).toEqual(['{"rootDirectory":"","version":3}']);
        });
    });

    it("turns building every push off on its own", async () => {
        const { user, backend } = await renderAppPage();

        await user.click(autoDeploy());
        await user.click(save());

        await waitFor(() => {
            expect(backend.sent("tune").map(({ raw }) => raw)).toEqual(['{"autoDeploy":false,"version":3}']);
        });
    });

    it("refuses a branch Git wouldn't accept, in the backend's words, before asking", async () => {
        const { user, backend } = await renderAppPage();

        await user.clear(branch());
        await user.type(branch(), "a..b");
        await user.click(save());

        expect(await within(panel()).findByText("Must not contain '..'.")).toBeInTheDocument();
        expect(backend.sent("tune")).toEqual([]);
    });

    it("says on the field when the branch doesn't exist on GitHub", async () => {
        const { user } = await renderAppPage();

        await user.clear(branch());
        await user.type(branch(), "nope");
        await user.click(save());

        expect(await within(panel()).findByText("There's no branch named nope on GitHub.")).toBeInTheDocument();
        expect(branch()).toHaveFocus();
    });

    it("puts the backend's root directory rule on its field", async () => {
        const backend = new RepoLinksBackend();
        backend.replies.set("tune", () =>
            error(400, "INVALID_ROOT_DIRECTORY", { message: "rootDirectory must not contain a backslash." }),
        );
        const { user } = await renderAppPage({ backend });

        await user.type(rootDirectory(), "apps");
        await user.click(save());

        expect(await within(panel()).findByText("rootDirectory must not contain a backslash.")).toBeInTheDocument();
    });

    it("keeps the person's edits when someone else saved first, showing what changed", async () => {
        const backend = new RepoLinksBackend();
        const { user } = await renderAppPage({ backend });
        if (backend.link === null) throw new Error("The scenario needs a link");
        backend.link = { ...backend.link, rootDirectory: "apps/api", version: 4 };

        await user.clear(branch());
        await user.type(branch(), "release");
        await user.click(save());

        expect(await within(panel()).findByRole("alert")).toHaveTextContent(
            "Someone else changed the root directory while you were editing. Your edits are still here",
        );
        expect(branch()).toHaveValue("release");
        await waitFor(() => {
            expect(rootDirectory()).toHaveValue("apps/api");
        });
        expect(panel()).toHaveTextContent("Now apps/api");
        expect(backend.sent("get").length).toBeGreaterThanOrEqual(2);
        expect(screen.queryByText("Build settings saved")).not.toBeInTheDocument();

        await user.click(save());

        expect(await screen.findByText("Build settings saved")).toBeInTheDocument();
        expect(backend.sent("tune").map(({ raw }) => raw)).toEqual([
            '{"productionBranch":"release","version":3}',
            '{"productionBranch":"release","version":4}',
        ]);
        expect(rootDirectory()).toHaveValue("apps/api");
    });

    it("shows a viewer the settings without a way to change them", async () => {
        await renderAppPage({ backend: new RepoLinksBackend(undefined, KEVIN) });

        expect(branch()).toBeDisabled();
        expect(autoDeploy()).toBeDisabled();
        expect(within(panel()).queryByRole("button", { name: "Save changes" })).not.toBeInTheDocument();
        expect(panel()).toHaveTextContent("Only developers and above can change these.");
    });
});
