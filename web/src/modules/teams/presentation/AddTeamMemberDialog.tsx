"use client";

import { MemberPicker, useOrgAccess, type Member } from "@/modules/members";
import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import {
    Avatar,
    Button,
    Callout,
    Dialog,
    DialogBody,
    DialogClose,
    DialogFooter,
    Person,
    Stack,
    useToast,
} from "@/shared/presentation/ui";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import type { Team } from "../domain/team";
import { MEMBER_NOT_FOUND } from "../domain/team-errors";
import { teamQueries } from "./queries";
import {
    ADD_TO_TEAM_COPY,
    addedCopy,
    addErrorCopy,
    addPeopleDescription,
    addPeopleTitle,
    alreadyInTeamCopy,
    CHANGE_PICK_COPY,
    INVITE_FIRST_COPY,
    INVITE_SOMEONE_COPY,
    MEMBER_PICKER_LABEL_COPY,
} from "./team-copy";
import { invitesPath } from "./team-paths";
import { useTeamUseCases } from "./team-use-cases";
import styles from "./Teams.module.css";
import { useAddTeamMember } from "./use-team-mutations";

export interface AddTeamMemberDialogProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly team: Team;
    readonly open: boolean;
    readonly onOpenChange: (open: boolean) => void;
}

export function AddTeamMemberDialog({ orgId, orgName, team, open, onOpenChange }: AddTeamMemberDialogProps) {
    const access = useOrgAccess();

    return (
        <Dialog
            open={open}
            onOpenChange={onOpenChange}
            title={addPeopleTitle(team.name)}
            description={
                <>
                    {addPeopleDescription(orgName)}
                    {access.can("invites.manage") && (
                        <>
                            {" "}
                            <Link href={invitesPath(orgId)}>{INVITE_SOMEONE_COPY}</Link> {INVITE_FIRST_COPY}
                        </>
                    )}
                </>
            }
        >
            <AddTeamMemberForm
                orgId={orgId}
                orgName={orgName}
                team={team}
                onDone={() => {
                    onOpenChange(false);
                }}
            />
        </Dialog>
    );
}

interface AddTeamMemberFormProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly team: Team;
    readonly onDone: () => void;
}

/**
 * Mounted only while the dialog is open, so each visit starts with nobody picked. The roster leaves the
 * team's current people out of the picker; anyone it misses is caught by the backend's `ALREADY_IN_TEAM`.
 */
function AddTeamMemberForm({ orgId, orgName, team, onDone }: AddTeamMemberFormProps) {
    const toast = useToast();
    const { listTeamMembers } = useTeamUseCases();
    const roster = useQuery(teamQueries.roster(listTeamMembers, orgId, team.id));
    const add = useAddTeamMember(orgId, team.id);
    const [picked, setPicked] = useState<Member>();

    const onTeam = (roster.data?.items ?? []).map((member) => member.userId);

    const submit = () => {
        if (picked === undefined || add.isPending) return;
        add.mutate(picked, {
            onSuccess: (outcome) => {
                if (outcome.status === "alreadyInTeam") toast.info(alreadyInTeamCopy(picked.displayName, team.name));
                else toast.success(addedCopy(picked.displayName, team.name));
                onDone();
            },
            onError: (error) => {
                if (error instanceof ApiError && error.is(MEMBER_NOT_FOUND)) setPicked(undefined);
            },
        });
    };

    const failure = add.isError ? messageFor(add.error, addErrorCopy(add.variables.displayName, orgName)) : undefined;

    return (
        <form
            noValidate
            onSubmit={(event) => {
                event.preventDefault();
                submit();
            }}
        >
            <DialogBody>
                <Stack>
                    {failure !== undefined && (
                        <Callout tone="red" role="alert">
                            {failure}
                        </Callout>
                    )}
                    {picked === undefined ? (
                        <MemberPicker
                            orgId={orgId}
                            label={MEMBER_PICKER_LABEL_COPY}
                            excludeUserIds={onTeam}
                            disabled={add.isPending}
                            onSelect={(member) => {
                                add.reset();
                                setPicked(member);
                            }}
                        />
                    ) : (
                        <div className={styles.picked}>
                            <Person
                                className={styles.person}
                                avatar={<Avatar name={picked.displayName} size="sm" />}
                                name={picked.displayName}
                                meta={picked.email}
                            />
                            <Button
                                variant="ghost"
                                size="sm"
                                disabled={add.isPending}
                                aria-label={`${CHANGE_PICK_COPY}: ${picked.displayName}`}
                                onClick={() => {
                                    add.reset();
                                    setPicked(undefined);
                                }}
                            >
                                {CHANGE_PICK_COPY}
                            </Button>
                        </div>
                    )}
                </Stack>
            </DialogBody>
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary" disabled={add.isPending}>
                        Cancel
                    </Button>
                </DialogClose>
                <Button type="submit" variant="primary" disabled={picked === undefined} loading={add.isPending}>
                    {ADD_TO_TEAM_COPY}
                </Button>
            </DialogFooter>
        </form>
    );
}
