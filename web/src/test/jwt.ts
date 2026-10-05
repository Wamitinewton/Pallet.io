const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");

export interface FakeJwtClaims {
    readonly sub: string;
    readonly email?: string;
    readonly sid?: string;
    readonly [claim: string]: unknown;
}

/** An unsigned JWT-shaped string: enough for code that reads claims without verifying them. */
export function fakeJwt(claims: FakeJwtClaims): string {
    return `${encode({ alg: "none", typ: "JWT" })}.${encode(claims)}.signature`;
}
