import type { NextConfig } from "next";

const nextConfig: NextConfig = {
    reactStrictMode: true,
    poweredByHeader: false,
    output: "standalone",
    serverExternalPackages: ["redis"],
    typedRoutes: true,
    reactCompiler: true,
    agentRules: false,
    images: {
        remotePatterns: [
            { protocol: "https", hostname: "github.com", pathname: "/*.png" },
            { protocol: "https", hostname: "avatars.githubusercontent.com" },
        ],
    },
    headers: () =>
        Promise.resolve([
            {
                source: "/reset-password",
                headers: [{ key: "Referrer-Policy", value: "no-referrer" }],
            },
            {
                source: "/invites/:token",
                headers: [{ key: "Referrer-Policy", value: "no-referrer" }],
            },
            {
                source: "/github/callback",
                headers: [{ key: "Referrer-Policy", value: "no-referrer" }],
            },
        ]),
};

export default nextConfig;
