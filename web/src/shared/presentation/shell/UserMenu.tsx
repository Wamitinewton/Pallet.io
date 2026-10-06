"use client";

import type { Route } from "next";
import Link from "next/link";
import { Avatar } from "../ui/avatar/Avatar";
import { Button } from "../ui/button/Button";
import { Icon } from "../ui/icons/Icon";
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from "../ui/menu/Menu";
import styles from "./UserMenu.module.css";

export interface UserMenuProps {
    readonly name: string | undefined;
    readonly email: string | undefined;
    readonly accountHref: Route;
    readonly securityHref: Route;
    readonly onSignOut: () => void;
    readonly signingOut: boolean;
}

export function UserMenu({ name, email, accountHref, securityHref, onSignOut, signingOut }: UserMenuProps) {
    return (
        <Menu>
            <MenuTrigger>
                <Button variant="ghost" className={styles.trigger} aria-label="Account menu">
                    {name === undefined ? <Icon name="user" /> : <Avatar name={name} />}
                </Button>
            </MenuTrigger>
            <MenuContent>
                {name !== undefined && (
                    <MenuLabel className={styles.who}>
                        <span className={styles.name}>{name}</span>
                        <span className={styles.email}>{email}</span>
                    </MenuLabel>
                )}
                {name !== undefined && <MenuSeparator />}
                <MenuItem asChild>
                    <Link href={accountHref}>
                        <Icon name="user" />
                        Your account
                    </Link>
                </MenuItem>
                <MenuItem asChild>
                    <Link href={securityHref}>
                        <Icon name="lock" />
                        Password and sessions
                    </Link>
                </MenuItem>
                <MenuSeparator />
                <MenuItem tone="danger" disabled={signingOut} onSelect={onSignOut}>
                    <Icon name="logout" />
                    Sign out
                </MenuItem>
            </MenuContent>
        </Menu>
    );
}
