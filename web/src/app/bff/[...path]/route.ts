import { bffProxy } from "@/composition/server";

export const dynamic = "force-dynamic";

async function handle(request: Request, { params }: RouteContext<"/bff/[...path]">): Promise<Response> {
    return bffProxy(request, (await params).path);
}

export { handle as DELETE, handle as GET, handle as PATCH, handle as POST, handle as PUT };
