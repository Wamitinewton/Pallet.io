import { sessionReauthenticate } from "@/composition/server";

export const dynamic = "force-dynamic";

export function POST(request: Request): Promise<Response> {
    return sessionReauthenticate(request);
}
