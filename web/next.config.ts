import type { NextConfig } from "next";

const nextConfig: NextConfig = {
    reactStrictMode: true,
    poweredByHeader: false,
    output: "standalone",
    typedRoutes: true,
    reactCompiler: true,
    agentRules: false,
};

export default nextConfig;
