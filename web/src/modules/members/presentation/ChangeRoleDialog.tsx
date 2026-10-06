"use client";

import type { OrgId } from "@/shared/domain/ids";
import { ROLE_DESCRIPTION, ROLE_LABEL } from "@/shared/presentation/roles";
import {
    Button,
    Choice,
    ChoiceGroup,
    Dialog,
    DialogBody,
    DialogClose,
    DialogFooter,
    Text,
} from "@/shared/presentation/ui";
import { useState } from "react";
import { ASSIGNABLE_ROLES, type AssignableRole, type Member } from "../domain/member";
import { changeRoleDescription, changeRoleTitle, OWNER_ONLY_ROLE_NOTE_COPY } from "./member-copy";
import styles from "./MembersView.module.css";
import { useChangeRole } from "./use-member-mutations";

export interface ChangeRoleDialogProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    /** The member whose role to change; the dialog is open while one is set. */
    readonly member: Member | undefined;
    readonly onClose: () => void;
}

function isAssignable(value: string): value is AssignableRole {
    return (ASSIGNABLE_ROLES as readonly string[]).includes(value);
}

/** Closes as soon as it is saved: the row shows the new role at once and goes back only if it is refused. */
export function ChangeRoleDialog({ orgId, orgName, member, onClose }: ChangeRoleDialogProps) {
    return (
        <Dialog
            open={member !== undefined}
            onOpenChange={(open) => {
                if (!open) onClose();
            }}
            title={member === undefined ? "" : changeRoleTitle(member.displayName)}
            description={member === undefined ? "" : changeRoleDescription(member.displayName, orgName)}
        >
            {member !== undefined && <ChangeRoleForm orgId={orgId} member={member} onClose={onClose} />}
        </Dialog>
    );
}

interface ChangeRoleFormProps {
    readonly orgId: OrgId;
    readonly member: Member;
    readonly onClose: () => void;
}

function ChangeRoleForm({ orgId, member, onClose }: ChangeRoleFormProps) {
    const changeRole = useChangeRole(orgId);
    const [role, setRole] = useState<string>(member.role);

    return (
        <form
            noValidate
            onSubmit={(event) => {
                event.preventDefault();
                if (isAssignable(role) && role !== member.role) changeRole.mutate({ member, role });
                onClose();
            }}
        >
            <DialogBody>
                <ChoiceGroup aria-label="Role" value={role} onValueChange={setRole} className={styles.roleOptions}>
                    {ASSIGNABLE_ROLES.map((option) => (
                        <Choice
                            key={option}
                            value={option}
                            title={ROLE_LABEL[option]}
                            description={ROLE_DESCRIPTION[option]}
                        />
                    ))}
                </ChoiceGroup>
                <Text tone="muted" size="sm">
                    {OWNER_ONLY_ROLE_NOTE_COPY}
                </Text>
            </DialogBody>
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary">Cancel</Button>
                </DialogClose>
                <Button type="submit" variant="primary">
                    Save role
                </Button>
            </DialogFooter>
        </form>
    );
}
