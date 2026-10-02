import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";
import { projects } from "./vitest.workspace.ts";

export default defineConfig({
    plugins: [react()],
    resolve: {
        tsconfigPaths: true,
        alias: {
            "server-only": fileURLToPath(new URL("./src/test/server-only.ts", import.meta.url)),
        },
    },
    test: {
        projects,
        passWithNoTests: true,
        coverage: {
            provider: "v8",
            include: ["src/**/*.{ts,tsx}"],
            exclude: ["src/app/**", "src/test/**", "src/**/generated/**", "src/**/*.test.{ts,tsx}"],
            reporter: ["text-summary", "html", "lcov"],
        },
    },
});
