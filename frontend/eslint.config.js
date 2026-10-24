import js from "@eslint/js";
import tseslint from "typescript-eslint";
import hooks from "eslint-plugin-react-hooks";
import globals from "globals";
const ban = (patterns) => [
  "error",
  {
    patterns: patterns.map((group) => ({
      group: [group],
      message: "Respect feature public entry points and shared boundaries.",
    })),
  },
];
export default tseslint.config(
  {
    ignores: [
      "dist/**",
      "node_modules/**",
      "dev-public/**",
      "playwright-report/**",
      "test-results/**",
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  { languageOptions: { globals: { ...globals.browser, ...globals.node } } },
  {
    files: ["src/**/*.{ts,tsx}"],
    plugins: { "react-hooks": hooks },
    rules: { ...hooks.configs.recommended.rules },
  },
  {
    files: ["src/shared/**/*.{ts,tsx}"],
    rules: { "no-restricted-imports": ban(["**/features/**", "**/app/**"]) },
  },
  {
    files: ["src/features/documents/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-imports": ban([
        "**/batches/*",
        "**/batches/**",
        "**/app/**",
      ]),
    },
  },
  {
    files: ["src/features/batches/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-imports": ban([
        "**/documents/*",
        "**/documents/**",
        "**/app/**",
      ]),
    },
  },
  {
    files: ["src/app/**/*.{ts,tsx}"],
    rules: { "no-restricted-imports": ban(["**/features/*/*"]) },
  },
);
