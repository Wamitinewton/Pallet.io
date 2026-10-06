import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { Route } from "next";
import Link from "next/link";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppShell } from "./AppShell";
import { PageBreadcrumbs } from "./Breadcrumbs";

const navigation = vi.hoisted(() => ({ pathname: "/orgs/o-1" }));
vi.mock("next/navigation", () => ({ usePathname: () => navigation.pathname }));

function Shell({ page = "Overview" }: { page?: string }) {
    return (
        <AppShell
            sidebar={
                <nav aria-label="Main">
                    <Link href={"/orgs/o-1/apps" as Route}>Apps</Link>
                    <Link href={"/orgs/o-1/members" as Route}>Members</Link>
                </nav>
            }
            actions={<button type="button">Account menu</button>}
        >
            <PageBreadcrumbs items={[{ label: "Kilima Labs", href: "/orgs/o-1" as Route }, { label: page }]} />
            <h1>{page}</h1>
        </AppShell>
    );
}

beforeEach(() => {
    navigation.pathname = "/orgs/o-1";
});

describe("AppShell", () => {
    it("shows the page's breadcrumbs in the top bar, the last one as the current page", () => {
        render(<Shell />);

        const crumbs = screen.getByRole("navigation", { name: "Breadcrumb" });
        expect(within(crumbs).getByRole("link", { name: "Kilima Labs" })).toHaveAttribute("href", "/orgs/o-1");
        expect(within(crumbs).getByText("Overview")).toHaveAttribute("aria-current", "page");
    });

    it("offers a way past the navigation to the content", () => {
        render(<Shell />);

        expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main-content");
        expect(screen.getByRole("main")).toHaveAttribute("id", "main-content");
    });

    it("opens the navigation as a dialog that keeps focus inside and closes on Escape", async () => {
        const user = userEvent.setup();
        render(<Shell />);
        const toggle = screen.getByRole("button", { name: "Open navigation" });

        await user.click(toggle);
        const dialog = screen.getByRole("dialog", { name: "Navigation" });
        expect(within(dialog).getByRole("link", { name: "Apps" })).toBeInTheDocument();
        for (let step = 0; step < 6; step += 1) {
            await user.tab();
            expect(dialog).toContainElement(document.activeElement as HTMLElement);
        }

        await user.keyboard("{Escape}");
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(toggle).toHaveFocus();
    });

    it("closes the navigation once a link in it has navigated", async () => {
        const user = userEvent.setup();
        const { rerender } = render(<Shell />);
        await user.click(screen.getByRole("button", { name: "Open navigation" }));
        expect(screen.getByRole("dialog")).toBeInTheDocument();

        navigation.pathname = "/orgs/o-1/apps";
        rerender(<Shell page="Apps" />);

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
});
