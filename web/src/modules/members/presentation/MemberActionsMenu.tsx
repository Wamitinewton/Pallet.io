"use client";

import { Button, Icon, Menu, MenuContent, MenuItem, MenuSeparator, MenuTrigger } from "@/shared/presentation/ui";
import { Fragment } from "react";
import type { Member } from "../domain/member";
import type { MemberAction } from "../domain/member-actions";
import { actionsLabel, leaveCopy, removeFromCopy } from "./member-copy";

export interface MemberActionsMenuProps {
    readonly member: Member;
    readonly actions: readonly MemberAction[];
    readonly orgName: string;
    readonly onSelect: (action: MemberAction, member: Member) => void;
}

const DESTRUCTIVE: ReadonlySet<MemberAction> = new Set(["remove", "leave"]);

/** Lists only what `actions` allows; the dialogs each item opens live with the table, not inside the menu. */
export function MemberActionsMenu({ member, actions, orgName, onSelect }: MemberActionsMenuProps) {
    if (actions.length === 0) return null;

    const item = (action: MemberAction) => {
        const select = () => {
            onSelect(action, member);
        };
        switch (action) {
            case "changeRole":
                return (
                    <MenuItem onSelect={select}>
                        <Icon name="shield" />
                        Change role
                    </MenuItem>
                );
            case "transferOwnership":
                return (
                    <MenuItem onSelect={select}>
                        <Icon name="key" />
                        Make owner
                    </MenuItem>
                );
            case "remove":
                return (
                    <MenuItem tone="danger" onSelect={select}>
                        <Icon name="trash" />
                        {removeFromCopy(orgName)}
                    </MenuItem>
                );
            case "leave":
                return (
                    <MenuItem tone="danger" onSelect={select}>
                        <Icon name="logout" />
                        {leaveCopy(orgName)}
                    </MenuItem>
                );
        }
    };

    return (
        <Menu>
            <MenuTrigger>
                <Button variant="ghost" size="sm" iconOnly aria-label={actionsLabel(member.displayName)}>
                    <Icon name="more" />
                </Button>
            </MenuTrigger>
            <MenuContent>
                {actions.map((action, index) => {
                    const previous = actions[index - 1];
                    const separated = previous !== undefined && DESTRUCTIVE.has(action) && !DESTRUCTIVE.has(previous);
                    return (
                        <Fragment key={action}>
                            {separated && <MenuSeparator />}
                            {item(action)}
                        </Fragment>
                    );
                })}
            </MenuContent>
        </Menu>
    );
}
