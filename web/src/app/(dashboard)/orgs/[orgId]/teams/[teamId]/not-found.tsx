"use client";

import { orgIdFromParam } from "@/modules/organizations";
import { TeamUnavailable } from "@/modules/teams";
import { useParams } from "next/navigation";

export default function TeamNotFound() {
    const { orgId } = useParams<{ orgId: string }>();
    return <TeamUnavailable orgId={orgIdFromParam(orgId)} />;
}
