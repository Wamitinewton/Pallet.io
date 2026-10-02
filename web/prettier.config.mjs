/** @type {import("prettier").Config} */
const config = {
    tabWidth: 4,
    printWidth: 120,
    plugins: ["prettier-plugin-organize-imports"],
    overrides: [
        {
            files: ["*.json", "*.yml", "*.yaml"],
            options: { tabWidth: 2 },
        },
    ],
};

export default config;
