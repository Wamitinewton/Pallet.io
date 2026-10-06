import { render, screen, within } from "@testing-library/react";
import type { Route } from "next";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Nav, NavGroup, NavLink, Sidebar } from "./Sidebar";

const navigation = vi.hoisted(() => ({ pathname: "/" }));
vi.mock("next/navigation", () => ({ usePathname: () => navigation.pathname }));

const route = (path: string) => path as Route;

function renderSidebar() {
    render(
        <Sidebar
            homeHref={route("/orgs/o-1")}
            switcher={<button type="button">Kilima Labs</button>}
            navigation={
                <Nav label="Main">
                    <NavGroup>
                        <NavLink href={route("/orgs/o-1")} icon="home" exact>
                            Overview
                        </NavLink>
                        <NavLink href={route("/orgs/o-1/apps")} icon="apps" count={4}>
                            Apps
                        </NavLink>
                    </NavGroup>
                    <NavGroup label="Organization">
                        <NavLink href={route("/orgs/o-1/members")} icon="users" count={0}>
                            Members
                        </NavLink>
                        <NavLink href={route("/orgs/o-1/github")} icon="github">
                            GitHub
                        </NavLink>
                    </NavGroup>
                </Nav>
            }
            footer={
                <Nav label="Account">
                    <NavGroup>
                        <NavLink href={route("/account")} icon="user">
                            Your account
                        </NavLink>
                    </NavGroup>
                </Nav>
            }
        />,
    );
}

const current = () => screen.getAllByRole("link").filter((link) => link.getAttribute("aria-current") === "page");

beforeEach(() => {
    navigation.pathname = "/";
});

describe("Sidebar", () => {
    it("shows each count beside its item, zero included, and none where there is no count", () => {
        renderSidebar();

        expect(screen.getByRole("link", { name: "Apps, 4" })).toHaveAttribute("href", "/orgs/o-1/apps");
        expect(screen.getByRole("link", { name: "Members, 0" })).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "GitHub" })).toBeInTheDocument();
    });

    it("groups the organization's items under their label, apart from the account's", () => {
        renderSidebar();

        const organization = screen.getByRole("list", { name: "Organization" });
        expect(within(organization).getAllByRole("link")).toEqual([
            screen.getByRole("link", { name: "Members, 0" }),
            screen.getByRole("link", { name: "GitHub" }),
        ]);
        expect(within(screen.getByRole("navigation", { name: "Account" })).getByRole("link")).toHaveTextContent(
            "Your account",
        );
    });

    it.each([
        ["/orgs/o-1", "Overview"],
        ["/orgs/o-1/apps", "Apps, 4"],
        ["/orgs/o-1/apps/app-1/connect", "Apps, 4"],
        ["/account", "Your account"],
    ])("on %s marks only %s as the current page", (pathname, name) => {
        navigation.pathname = pathname;
        renderSidebar();

        expect(current()).toEqual([screen.getByRole("link", { name })]);
    });

    it("marks nothing on a page outside the navigation", () => {
        navigation.pathname = "/notifications";
        renderSidebar();

        expect(current()).toEqual([]);
    });
});
