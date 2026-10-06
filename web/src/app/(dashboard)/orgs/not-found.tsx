import { OrganizationUnavailable } from "@/modules/organizations";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Organization unavailable",
};

export default function OrganizationNotFound() {
    return <OrganizationUnavailable />;
}
