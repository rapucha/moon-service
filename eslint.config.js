import js from "@eslint/js";
import globals from "globals";

export default [
  {
    ignores: [
      "backend/target/**",
      "docs/generated/**",
      "node_modules/**",
      "playwright-report/**",
      "test-results/**"
    ]
  },
  js.configs.recommended,
  {
    files: ["frontend/src/*.js", "frontend/generated/*.js"],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: "module",
      globals: globals.browser
    },
    rules: {
      "no-control-regex": "off",
      "no-unused-vars": ["error", {
        argsIgnorePattern: "^_",
        caughtErrors: "none"
      }]
    }
  },
  {
    files: ["tests/ui/**/*.js", "scripts/*.mjs", "playwright.config.js", "eslint.config.js"],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: "module",
      globals: {
        ...globals.browser,
        ...globals.node
      }
    },
    rules: {
      "no-unused-vars": ["error", {
        argsIgnorePattern: "^_",
        caughtErrors: "none"
      }]
    }
  }
];
