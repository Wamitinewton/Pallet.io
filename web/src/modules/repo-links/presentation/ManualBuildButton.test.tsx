import { manualClock } from "@/test/clock";
import { error } from "@/test/msw/envelopes";
import { disconnectedRepoLinkDto } from "@/test/msw/git-integration";
import { TEST_NOW } from "@/test/render";
import { act, screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { APP_PAGE, FATUMA, KEVIN, renderAppPage, RepoLinksBackend } from "./testing/render-repo-links";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => APP_PAGE }));

const dialog = () => screen.getByRole("dialog", { name: "Start a build" });
const branch = () => within(dialog()).getByLabelText("Branch");
const submit = () => within(dialog()).getByRole("button", { name: /Start build|Try again in/ });

async function openDialog(user: UserEvent) {
    await user.click(screen.getByRole("button", { name: "Start a build" }));
    await screen.findByRole("dialog", { name: "Start a build" });
}

/** The backend took the request, then the reply never arrived: what a timeout looks like from the browser. */
const lostReply = (work: () => Response) => {
    work();
    return HttpResponse.error();
};

beforeEach(() => {
    vi.clearAllMocks();
});

afterEach(() => {
    vi.useRealTimers();
});

describe("ManualBuildButton", () => {
    it("builds the production branch's head, with a key, and says which commit", async () => {
        const { user, backend } = await renderAppPage();

        await openDialog(user);
        expect(branch()).toHaveValue("main");
        await user.click(submit());

        expect(await screen.findByText("Build requested for 3f9c2e1 on main")).toBeInTheDocument();
        const [request] = backend.sent("build");
        expect(request?.raw).toBe("{}");
        expect(request?.idempotencyKey).toMatch(/^[A-Za-z0-9_-]{1,128}$/);
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("retries a request that got no answer with the same key, so only one build starts", async () => {
        const backend = new RepoLinksBackend();
        backend.replies.set("build", lostReply);
        const { user } = await renderAppPage({ backend });

        await openDialog(user);
        await user.click(submit());
        expect(await within(dialog()).findByRole("alert")).toHaveTextContent("We couldn't reach Pallet.");
        await user.click(submit());

        expect(await screen.findByText("Build requested for 3f9c2e1 on main")).toBeInTheDocument();
        const keys = backend.sent("build").map(({ idempotencyKey }) => idempotencyKey);
        expect(keys).toHaveLength(2);
        expect(keys[1]).toBe(keys[0]);
        expect(backend.buildsStarted).toBe(1);
    });

    it("treats another branch as another intent, with a new key", async () => {
        const backend = new RepoLinksBackend();
        backend.replies.set("build", lostReply);
        const { user } = await renderAppPage({ backend });

        await openDialog(user);
        await user.clear(branch());
        await user.type(branch(), "release");
        await user.click(submit());
        await within(dialog()).findByRole("alert");
        await user.clear(branch());
        await user.type(branch(), "develop");
        await user.click(submit());

        expect(await screen.findByText("Build requested for 3f9c2e1 on develop")).toBeInTheDocument();
        const [first, second] = backend.sent("build");
        expect(first?.raw).toBe('{"branch":"release"}');
        expect(second?.raw).toBe('{"branch":"develop"}');
        expect(second?.idempotencyKey).not.toBe(first?.idempotencyKey);
    });

    it("starts a second build with a new key once the first was accepted", async () => {
        const { user, backend } = await renderAppPage();

        await openDialog(user);
        await user.click(submit());
        await screen.findByText("Build requested for 3f9c2e1 on main");
        await openDialog(user);
        await user.click(submit());

        await waitFor(() => {
            expect(backend.buildsStarted).toBe(2);
        });
        const [first, second] = backend.sent("build");
        expect(second?.idempotencyKey).not.toBe(first?.idempotencyKey);
    });

    it("counts the hourly limit down and holds the build until it is over", async () => {
        vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
        const clock = manualClock(TEST_NOW);
        const backend = new RepoLinksBackend();
        backend.replies.set("build", () => error(429, "TOO_MANY_REQUESTS", { meta: { retryAfter: 30 } }));
        const { user } = await renderAppPage({ backend, clock });

        await openDialog(user);
        await user.click(submit());

        expect(await within(dialog()).findByRole("alert")).toHaveTextContent("Try again in 30 seconds.");
        expect(submit()).toBeDisabled();
        expect(submit()).toHaveTextContent("Try again in 30 seconds");

        act(() => {
            clock.advance(1_000);
            vi.advanceTimersByTime(250);
        });
        await waitFor(() => {
            expect(within(dialog()).getByRole("alert")).toHaveTextContent("Try again in 29 seconds.");
        });
        expect(submit()).toBeDisabled();

        act(() => {
            clock.advance(29_000);
            vi.advanceTimersByTime(250);
        });
        await waitFor(() => {
            expect(submit()).toBeEnabled();
        });
        expect(submit()).toHaveTextContent("Start build");
    });

    it("points at the GitHub page when the installation is suspended", async () => {
        const backend = new RepoLinksBackend();
        backend.replies.set("build", () => error(409, "INSTALLATION_SUSPENDED"));
        const { user } = await renderAppPage({ backend });

        await openDialog(user);
        await user.click(submit());

        const alert = await within(dialog()).findByRole("alert");
        expect(alert).toHaveTextContent("The Pallet GitHub App is suspended");
        expect(within(alert).getByRole("link", { name: "Open GitHub settings" })).toHaveAttribute(
            "href",
            "/orgs/org-kilima/github",
        );
    });

    it("refuses a branch Git wouldn't accept, and says on the field when GitHub has no such branch", async () => {
        const { user, backend } = await renderAppPage();

        await openDialog(user);
        await user.clear(branch());
        await user.type(branch(), "x.lock");
        await user.click(submit());
        expect(
            await within(dialog()).findByText("No component may start with '.' or end with '.lock'."),
        ).toBeInTheDocument();
        expect(backend.sent("build")).toEqual([]);

        await user.clear(branch());
        await user.type(branch(), "gone");
        await user.click(submit());

        expect(await within(dialog()).findByText("There's no branch named gone on GitHub.")).toBeInTheDocument();
    });

    it("is offered to a developer", async () => {
        await renderAppPage({ backend: new RepoLinksBackend(undefined, FATUMA) });

        expect(screen.getByRole("button", { name: "Start a build" })).toBeInTheDocument();
    });

    it("is not offered to a viewer", async () => {
        await renderAppPage({ backend: new RepoLinksBackend(undefined, KEVIN) });
        expect(screen.queryByRole("button", { name: "Start a build" })).not.toBeInTheDocument();
    });

    it("is not offered for a disconnected link", async () => {
        await renderAppPage({ backend: new RepoLinksBackend(disconnectedRepoLinkDto("REPOSITORY_DELETED")) });

        expect(screen.queryByRole("button", { name: "Start a build" })).not.toBeInTheDocument();
    });
});
