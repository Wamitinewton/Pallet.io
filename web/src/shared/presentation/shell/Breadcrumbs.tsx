"use client";

import type { Route } from "next";
import Link from "next/link";
import { createContext, use, useEffect, useState, type ReactNode } from "react";
import styles from "./Breadcrumbs.module.css";

export interface Crumb {
    readonly label: string;
    readonly href?: Route | undefined;
}

const NO_CRUMBS: readonly Crumb[] = [];

const CrumbsContext = createContext<readonly Crumb[]>(NO_CRUMBS);
const SetCrumbsContext = createContext<((crumbs: readonly Crumb[]) => void) | null>(null);

export function BreadcrumbsProvider({ children }: Readonly<{ children: ReactNode }>) {
    const [crumbs, setCrumbs] = useState<readonly Crumb[]>(NO_CRUMBS);
    return (
        <SetCrumbsContext value={setCrumbs}>
            <CrumbsContext value={crumbs}>{children}</CrumbsContext>
        </SetCrumbsContext>
    );
}

/** Puts this page's trail in the top bar for as long as the page is mounted. */
export function useBreadcrumbs(crumbs: readonly Crumb[]): void {
    const setCrumbs = use(SetCrumbsContext);
    const key = JSON.stringify(crumbs);

    useEffect(() => {
        if (setCrumbs === null) return undefined;
        setCrumbs(JSON.parse(key) as Crumb[]);
        return () => {
            setCrumbs(NO_CRUMBS);
        };
    }, [key, setCrumbs]);
}

export function PageBreadcrumbs({ items }: Readonly<{ items: readonly Crumb[] }>) {
    useBreadcrumbs(items);
    return null;
}

export function Breadcrumbs() {
    const crumbs = use(CrumbsContext);
    if (crumbs.length === 0) return null;

    return (
        <nav aria-label="Breadcrumb" className={styles.crumbs}>
            <ol>
                {crumbs.map((crumb, index) => {
                    const current = index === crumbs.length - 1;
                    return (
                        <li key={`${String(index)}:${crumb.label}`}>
                            {current || crumb.href === undefined ? (
                                <span className={styles.here} {...(current && { "aria-current": "page" })}>
                                    {crumb.label}
                                </span>
                            ) : (
                                <Link href={crumb.href}>{crumb.label}</Link>
                            )}
                        </li>
                    );
                })}
            </ol>
        </nav>
    );
}
