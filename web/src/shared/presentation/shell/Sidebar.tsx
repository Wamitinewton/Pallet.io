"use client";

import type { Route } from "next";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useId, type ReactNode } from "react";
import { Brand } from "../ui/brand/Brand";
import { Icon, type IconName } from "../ui/icons/Icon";
import styles from "./Sidebar.module.css";

export interface SidebarProps {
    readonly homeHref: Route;
    readonly switcher: ReactNode;
    readonly navigation: ReactNode;
    readonly footer: ReactNode;
}

export function Sidebar({ homeHref, switcher, navigation, footer }: SidebarProps) {
    return (
        <div className={styles.sidebar}>
            <Brand asChild className={styles.brand}>
                <Link href={homeHref} />
            </Brand>
            {switcher}
            {navigation}
            <div className={styles.foot}>{footer}</div>
        </div>
    );
}

export interface NavProps {
    readonly label: string;
    readonly children: ReactNode;
}

export function Nav({ label, children }: NavProps) {
    return (
        <nav aria-label={label} className={styles.nav}>
            {children}
        </nav>
    );
}

export interface NavGroupProps {
    readonly label?: string;
    readonly children: ReactNode;
}

export function NavGroup({ label, children }: NavGroupProps) {
    const labelId = useId();
    return (
        <>
            {label !== undefined && (
                <div id={labelId} className={styles.label}>
                    {label}
                </div>
            )}
            <ul className={styles.group} {...(label !== undefined && { "aria-labelledby": labelId })}>
                {children}
            </ul>
        </>
    );
}

export function isActivePath(pathname: string, href: string, exact: boolean): boolean {
    if (pathname === href) return true;
    return !exact && pathname.startsWith(`${href}/`);
}

export interface NavLinkProps {
    readonly href: Route;
    readonly icon: IconName;
    readonly children: string;
    readonly count?: number | undefined;
    /** Only the path itself is active, not the pages below it. */
    readonly exact?: boolean;
}

export function NavLink({ href, icon, children, count, exact = false }: NavLinkProps) {
    const active = isActivePath(usePathname(), href, exact);
    return (
        <li>
            <Link
                href={href}
                className={styles.link}
                data-active={active || undefined}
                {...(count !== undefined && { "aria-label": `${children}, ${String(count)}` })}
                {...(active && { "aria-current": "page" })}
            >
                <Icon name={icon} />
                <span>{children}</span>
                {count !== undefined && <span className={styles.count}>{count}</span>}
            </Link>
        </li>
    );
}
