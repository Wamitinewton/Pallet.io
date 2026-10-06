"use client";

import { RouteError, type RouteErrorProps } from "@/shared/presentation/errors";

export default function DashboardError(props: RouteErrorProps) {
    return <RouteError {...props} />;
}
