import js from "@eslint/js";
import nextVitals from "eslint-config-next/core-web-vitals";
import prettier from "eslint-config-prettier/flat";
import boundaries from "eslint-plugin-boundaries";
import jsxA11y from "eslint-plugin-jsx-a11y";
import { defineConfig, globalIgnores } from "eslint/config";
import tseslint from "typescript-eslint";

const elements = [
    { type: "integration-test", pattern: "src/**/__int__", partialMatch: false },
    { type: "app", pattern: "src/app", partialMatch: false },
    { type: "composition", pattern: "src/composition", partialMatch: false },
    { type: "module-domain", pattern: "src/modules/*/domain", partialMatch: false, capture: ["module"] },
    { type: "module-application", pattern: "src/modules/*/application", partialMatch: false, capture: ["module"] },
    {
        type: "module-infrastructure",
        pattern: "src/modules/*/infrastructure",
        partialMatch: false,
        capture: ["module"],
    },
    { type: "module-presentation", pattern: "src/modules/*/presentation", partialMatch: false, capture: ["module"] },
    { type: "module-api", pattern: "src/modules/*", partialMatch: false, capture: ["module"] },
    { type: "shared-domain", pattern: "src/shared/domain", partialMatch: false },
    { type: "shared-application", pattern: "src/shared/application", partialMatch: false },
    { type: "shared-infrastructure", pattern: "src/shared/infrastructure", partialMatch: false },
    { type: "shared-presentation", pattern: "src/shared/presentation", partialMatch: false },
];

const files = [{ category: "module-entry", pattern: "src/modules/*/index.ts" }];

const any = (type) => ({ element: { type } });
const own = (type) => ({ element: { type, captured: { module: "{{from.element.captured.module}}" } } });
const moduleApi = { element: { type: "module-api" }, file: { categories: "module-entry" } };

const allowed = {
    "integration-test": [
        any("integration-test"),
        any("app"),
        any("composition"),
        moduleApi,
        any("module-domain"),
        any("module-application"),
        any("module-infrastructure"),
        any("shared-domain"),
        any("shared-application"),
        any("shared-infrastructure"),
    ],
    app: [any("app"), any("composition"), moduleApi, any("shared-presentation")],
    composition: [
        any("composition"),
        moduleApi,
        any("module-domain"),
        any("module-application"),
        any("module-infrastructure"),
        any("module-presentation"),
        any("shared-domain"),
        any("shared-application"),
        any("shared-infrastructure"),
        any("shared-presentation"),
    ],
    "module-api": [own("module-domain"), own("module-application"), own("module-presentation")],
    "module-domain": [own("module-domain"), any("shared-domain")],
    "module-application": [
        own("module-application"),
        own("module-domain"),
        any("shared-application"),
        any("shared-domain"),
    ],
    "module-infrastructure": [
        own("module-infrastructure"),
        own("module-application"),
        own("module-domain"),
        any("shared-infrastructure"),
        any("shared-application"),
        any("shared-domain"),
    ],
    "module-presentation": [
        own("module-presentation"),
        own("module-application"),
        own("module-domain"),
        moduleApi,
        any("shared-presentation"),
        any("shared-domain"),
    ],
    "shared-domain": [any("shared-domain")],
    "shared-application": [any("shared-application"), any("shared-domain")],
    "shared-infrastructure": [any("shared-infrastructure"), any("shared-application"), any("shared-domain")],
    "shared-presentation": [any("shared-presentation"), any("shared-domain")],
};

const generatedApiTypes = {
    group: ["**/generated", "**/generated/**"],
    message: "Generated API types stay in infrastructure/. Map them to domain types there.",
};

const frameworkImports = {
    group: ["react", "react/**", "react-dom", "react-dom/**", "next", "next/**", "server-only", "client-only"],
    message: "domain/ and application/ are framework-free. Move this to presentation/ or infrastructure/.",
};

const globalFetch = ["window", "globalThis", "self"].map((object) => ({
    object,
    property: "fetch",
    message: "Use an API client from shared/infrastructure.",
}));

export default defineConfig([
    globalIgnores([
        ".next/**",
        "out/**",
        "build/**",
        "coverage/**",
        "playwright-report/**",
        "test-results/**",
        "design/**",
        "next-env.d.ts",
        "src/**/generated/**",
    ]),

    js.configs.recommended,
    ...nextVitals,
    tseslint.configs.strictTypeChecked,
    tseslint.configs.stylisticTypeChecked,
    {
        languageOptions: {
            parserOptions: {
                projectService: true,
                tsconfigRootDir: import.meta.dirname,
            },
        },
    },
    {
        files: ["**/*.{js,mjs,cjs}"],
        extends: [tseslint.configs.disableTypeChecked],
    },
    {
        files: ["**/*.{jsx,tsx}"],
        rules: jsxA11y.flatConfigs.strict.rules,
    },
    {
        rules: { "no-console": "error" },
    },
    {
        files: ["scripts/**"],
        rules: { "no-console": "off" },
    },
    {
        files: ["**/*.{ts,tsx,mts}"],
        rules: {
            "@typescript-eslint/no-floating-promises": "error",
            "@typescript-eslint/switch-exhaustiveness-check": [
                "error",
                { considerDefaultExhaustiveForUnions: true, requireDefaultForNonUnion: true },
            ],
            "@typescript-eslint/consistent-type-imports": "error",
            "@typescript-eslint/no-import-type-side-effects": "error",
            "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
        },
    },

    {
        files: ["src/**/*.{ts,tsx}"],
        plugins: { boundaries },
        settings: {
            "import/resolver": {
                typescript: { alwaysTryTypes: true, project: "./tsconfig.json" },
            },
            "boundaries/include": ["src/**/*"],
            "boundaries/elements": elements,
            "boundaries/files": files,
        },
        rules: {
            "boundaries/dependencies": [
                "error",
                {
                    default: "disallow",
                    message:
                        "{{from.element.types.[0]}} must not import {{to.element.types.[0]}} ({{dependency.source}}). " +
                        "Another module is reachable only through its index.ts.",
                    policies: Object.entries(allowed).map(([type, to]) => ({
                        from: { element: { type } },
                        allow: { to },
                    })),
                },
            ],
            "import/no-cycle": ["error", { ignoreExternal: true }],
        },
    },
    {
        files: ["src/**/*.{ts,tsx}"],
        ignores: ["src/shared/infrastructure/**"],
        rules: {
            "no-restricted-globals": [
                "error",
                { name: "fetch", message: "Use an API client from shared/infrastructure." },
            ],
            "no-restricted-properties": ["error", ...globalFetch],
        },
    },
    {
        files: ["src/**/*.{ts,tsx}"],
        ignores: ["src/**/infrastructure/**", "src/test/**"],
        rules: {
            "no-restricted-imports": ["error", { patterns: [generatedApiTypes] }],
        },
    },
    {
        files: [
            "src/modules/*/domain/**",
            "src/modules/*/application/**",
            "src/shared/domain/**",
            "src/shared/application/**",
        ],
        rules: {
            "no-restricted-imports": ["error", { patterns: [frameworkImports, generatedApiTypes] }],
            "no-restricted-properties": [
                "error",
                ...globalFetch,
                { object: "Date", property: "now", message: "Use the injected Clock." },
            ],
        },
    },

    prettier,
]);
