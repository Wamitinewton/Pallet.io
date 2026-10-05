import { describe, expect, it } from "vitest";
import { BodyTooLargeError, readBoundedText } from "./bounded-body";

const streamOf = (...chunks: string[]) =>
    new ReadableStream<Uint8Array>({
        start(controller) {
            for (const chunk of chunks) controller.enqueue(new TextEncoder().encode(chunk));
            controller.close();
        },
    });

const post = (body: BodyInit | null, headers: Record<string, string> = {}) =>
    new Request("http://localhost/api", { method: "POST", body, headers, duplex: "half" } as RequestInit);

describe("readBoundedText", () => {
    it("reads a body within the limit, across chunks", async () => {
        await expect(readBoundedText(post(streamOf("héllo ", "wörld")), 64)).resolves.toBe("héllo wörld");
    });

    it("reads an absent body as empty", async () => {
        await expect(readBoundedText(post(null), 8)).resolves.toBe("");
    });

    it("refuses by declared length before reading", async () => {
        await expect(readBoundedText(post("x", { "Content-Length": "9" }), 8)).rejects.toBeInstanceOf(
            BodyTooLargeError,
        );
    });

    it("refuses a stream that grows past the limit", async () => {
        await expect(readBoundedText(post(streamOf("12345", "6789")), 8)).rejects.toBeInstanceOf(BodyTooLargeError);
    });
});
