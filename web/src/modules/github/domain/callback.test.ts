import { describe, expect, it } from "vitest";
import { callbackQueryFrom, classifyCallback, type CallbackQuery } from "./callback";

const grant = { code: "a1b2c3d4e5", state: "v1.eyJ0eXAiOiJzdGF0ZSJ9.c2ln" };

describe("classifyCallback", () => {
    it("reads code and state alone as an authorization", () => {
        expect(classifyCallback({ ...grant })).toEqual({ kind: "authorization", grant });
    });

    it("reads an installation id with code and state as a fresh install", () => {
        expect(classifyCallback({ installation_id: "41000001", setup_action: "install", ...grant })).toEqual({
            kind: "freshInstall",
            installationId: 41000001,
            grant,
        });
    });

    it("reads an installation id without code or state as an install that still needs a session", () => {
        expect(classifyCallback({ installation_id: "41000001", setup_action: "update" })).toEqual({
            kind: "installWithoutAuthorization",
            installationId: 41000001,
        });
    });

    it("reads setup_action=request as an install waiting on GitHub's admin, whatever else came with it", () => {
        expect(classifyCallback({ setup_action: "request" })).toEqual({ kind: "installRequested" });
        expect(classifyCallback({ setup_action: "request", ...grant })).toEqual({ kind: "installRequested" });
    });

    it("reads access_denied as cancelled", () => {
        expect(classifyCallback({ error: "access_denied", state: grant.state })).toEqual({ kind: "cancelled" });
    });

    it.each<[string, CallbackQuery]>([
        ["nothing at all", {}],
        ["another GitHub error", { error: "redirect_uri_mismatch", ...grant }],
        ["a setup action GitHub never sends", { setup_action: "delete", installation_id: "41000001", ...grant }],
        ["code without state", { code: grant.code }],
        ["state without code", { state: grant.state }],
        ["an installation id with only a code", { installation_id: "41000001", code: grant.code }],
        ["an empty code", { code: "", state: grant.state }],
        ["a code with a space", { code: "a b", state: grant.state }],
        ["a code with a control character", { code: "a\u0000b", state: grant.state }],
        ["an oversized state", { code: grant.code, state: "s".repeat(1025) }],
    ])("reads %s as invalid", (_, query) => {
        expect(classifyCallback(query)).toEqual({ kind: "invalid" });
    });

    it.each(["0", "-1", "1.5", "1e3", "0x10", " 41000001", "41000001abc", "", "9007199254740993"])(
        "never accepts %j as an installation id",
        (installationId) => {
            expect(classifyCallback({ installation_id: installationId, ...grant })).toEqual({ kind: "invalid" });
        },
    );
});

describe("callbackQueryFrom", () => {
    it("keeps only GitHub's parameters", () => {
        expect(
            callbackQueryFrom(new URLSearchParams("code=c&state=s&installation_id=1&setup_action=install&next=/x")),
        ).toEqual({ code: "c", state: "s", installation_id: "1", setup_action: "install" });
    });
});
