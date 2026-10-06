"use client";

import { PageBreadcrumbs } from "@/shared/presentation/shell";
import { Avatar, PageNarrow, SectionGap, Tabs, TabsContent, TabsList, TabsTrigger } from "@/shared/presentation/ui";
import { useSuspenseQuery } from "@tanstack/react-query";
import { useEffect, useRef, type ReactNode } from "react";
import styles from "./AccountView.module.css";
import { useIdentityUseCases } from "./identity-use-cases";
import { PasswordPanel } from "./PasswordPanel";
import { ProfilePanel } from "./ProfilePanel";
import { identityQueries } from "./queries";
import { SessionsPanel } from "./SessionsPanel";
import { isAccountTab, SECURITY_SECTION_ID, useAccountTab } from "./use-account-tab";

export function AccountView() {
    const { getMyProfile } = useIdentityUseCases();
    const { data: profile } = useSuspenseQuery(identityQueries.profile(getMyProfile));
    const { tab, select, scrollToSecurity, scrolledToSecurity } = useAccountTab();

    return (
        <PageNarrow>
            <PageBreadcrumbs items={[{ label: "Your account" }]} />
            <header className={styles.head}>
                <Avatar name={profile.displayName} size="lg" />
                <div className={styles.who}>
                    <h1>{profile.displayName}</h1>
                    <p className={styles.email}>{profile.email}</p>
                </div>
            </header>

            <Tabs
                value={tab}
                onValueChange={(value) => {
                    if (isAccountTab(value)) select(value);
                }}
            >
                <TabsList aria-label="Account sections" className={styles.tabs}>
                    <TabsTrigger value="profile">Profile</TabsTrigger>
                    <TabsTrigger value="security">Password and sessions</TabsTrigger>
                </TabsList>
                <TabsContent value="profile">
                    <ProfilePanel profile={profile} />
                </TabsContent>
                <TabsContent value="security">
                    <SecuritySection scroll={scrollToSecurity} onScrolled={scrolledToSecurity}>
                        <PasswordPanel email={profile.email} />
                        <SessionsPanel />
                    </SecuritySection>
                </TabsContent>
            </Tabs>
        </PageNarrow>
    );
}

interface SecuritySectionProps {
    readonly scroll: boolean;
    readonly onScrolled: () => void;
    readonly children: ReactNode;
}

/** Scrolls itself in once mounted, since the tab's content only exists after the tab is selected. */
function SecuritySection({ scroll, onScrolled, children }: SecuritySectionProps) {
    const section = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!scroll) return;
        section.current?.scrollIntoView({ block: "start" });
        onScrolled();
    }, [scroll, onScrolled]);

    return (
        <SectionGap ref={section} id={SECURITY_SECTION_ID} className={styles.security}>
            {children}
        </SectionGap>
    );
}
