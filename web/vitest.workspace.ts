import type { TestProjectInlineConfiguration } from "vitest/config";

const integrationTests = "src/**/__int__/**";

export const projects: TestProjectInlineConfiguration[] = [
    {
        extends: true,
        test: {
            name: "unit",
            environment: "node",
            include: ["src/**/*.test.ts"],
            exclude: [integrationTests],
            setupFiles: ["./src/test/setup-node.ts"],
        },
    },
    {
        extends: true,
        test: {
            name: "component",
            environment: "jsdom",
            include: ["src/**/*.test.tsx"],
            exclude: [integrationTests],
            setupFiles: ["./src/test/setup-node.ts", "./src/test/setup-msw.ts", "./src/test/setup-dom.ts"],
        },
    },
    {
        extends: true,
        test: {
            name: "integration",
            environment: "node",
            include: [`${integrationTests}/*.int.test.ts`],
            setupFiles: ["./src/test/setup-node.ts", "./src/test/setup-msw.ts"],
            pool: "forks",
            fileParallelism: false,
            hookTimeout: 60_000,
        },
    },
];
