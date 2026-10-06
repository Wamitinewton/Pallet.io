"use client";

import { AppUnavailable } from "@/modules/apps";
import { orgIdFromParam } from "@/modules/organizations";
import { useParams } from "next/navigation";

export default function AppNotFound() {
    const { orgId } = useParams<{ orgId: string }>();
    return <AppUnavailable orgId={orgIdFromParam(orgId)} />;
}
