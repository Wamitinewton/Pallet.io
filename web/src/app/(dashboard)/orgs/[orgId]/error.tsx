"use client";

import { RouteError, type RouteErrorProps } from "@/shared/presentation/errors";

export default function OrganizationError(props: RouteErrorProps) {
    return <RouteError {...props} />;
}
