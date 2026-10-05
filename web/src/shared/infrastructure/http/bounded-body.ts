export class BodyTooLargeError extends Error {
    override readonly name = "BodyTooLargeError";

    constructor(readonly maxBytes: number) {
        super(`Request body exceeds ${String(maxBytes)} bytes`);
    }
}

/** The body as text, read without ever buffering more than `maxBytes`, whatever `Content-Length` claims. */
export async function readBoundedText(request: Request, maxBytes: number): Promise<string> {
    if (Number(request.headers.get("Content-Length") ?? "0") > maxBytes) throw new BodyTooLargeError(maxBytes);
    if (request.body === null) return "";

    const reader = request.body.getReader();
    const chunks: Uint8Array[] = [];
    let total = 0;
    for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        total += value.byteLength;
        if (total > maxBytes) {
            await reader.cancel();
            throw new BodyTooLargeError(maxBytes);
        }
        chunks.push(value);
    }

    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
        bytes.set(chunk, offset);
        offset += chunk.byteLength;
    }
    return new TextDecoder().decode(bytes);
}
