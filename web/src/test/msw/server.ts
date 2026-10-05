import { setupServer } from "msw/node";

export const TEST_ORIGIN = "http://localhost";
export const TEST_GATEWAY_URL = "http://gateway.test";

export const server = setupServer();

export function bffUrl(path: string): string {
    return `${TEST_ORIGIN}/bff${path}`;
}

export function gatewayUrl(path: string): string {
    return `${TEST_GATEWAY_URL}/api/v1${path}`;
}

export function sessionApiUrl(path: string): string {
    return `${TEST_ORIGIN}/api/session${path}`;
}
