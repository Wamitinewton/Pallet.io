"use client";

import type { OrgId } from "@/shared/domain/ids";
import type { Team } from "../domain/team";
import { DeleteTeamPanel } from "./DeleteTeamPanel";
import { RenameTeamPanel } from "./RenameTeamPanel";

export interface TeamSettingsPanelProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly team: Team;
}

/** For admins only; the backend still decides. */
export function TeamSettingsPanel({ orgId, orgName, team }: TeamSettingsPanelProps) {
    return (
        <>
            <RenameTeamPanel orgId={orgId} team={team} />
            <DeleteTeamPanel orgId={orgId} orgName={orgName} team={team} />
        </>
    );
}
