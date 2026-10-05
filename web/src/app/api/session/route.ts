import { sessionSummary } from "@/composition/server";

export const dynamic = "force-dynamic";

export function GET(request: Request): Promise<Response> {
    return sessionSummary(request);
}
