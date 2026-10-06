"use client";

import type { OrgId } from "@/shared/domain/ids";
import { useEffect } from "react";
import { lastOrganizationCookie } from "./last-organization";

export function RememberLastOrganization({ orgId }: Readonly<{ orgId: OrgId }>) {
    useEffect(() => {
        document.cookie = lastOrganizationCookie(orgId, window.location.protocol === "https:");
    }, [orgId]);
    return null;
}
