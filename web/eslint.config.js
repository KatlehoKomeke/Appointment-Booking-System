// eslint.config.js
//
// Lint rules for the web app. `npm run lint` runs them and fails on any warning, locally and in CI.
//
// - typescript-eslint, strict and type-aware: unsafe `any`, floating promises, needless conditions.
// - eslint-plugin-jsdoc: every exported component, hook, function and type has a TSDoc comment
//   (CONTRIBUTING.md, "Documentation standards").
// - eslint-plugin-jsx-a11y-x, strict: accessibility mistakes in JSX are errors. It is the es-tooling fork of
//   eslint-plugin-jsx-a11y, which does not yet support ESLint 10 (jsx-eslint/eslint-plugin-jsx-a11y#1075).
// - react-hooks: the rules of hooks. react-refresh: files that export components keep hot reload working.
// - eslint-config-prettier, last: formatting is Prettier's job, so no ESLint rule may disagree with it.

import js from '@eslint/js';
import prettier from 'eslint-config-prettier';
import jsdoc from 'eslint-plugin-jsdoc';
import jsxA11y from 'eslint-plugin-jsx-a11y-x';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import { defineConfig, globalIgnores } from 'eslint/config';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default defineConfig([
  globalIgnores(['dist', 'coverage']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.strictTypeChecked,
      tseslint.configs.stylisticTypeChecked,
      reactHooks.configs.flat['recommended-latest'],
      reactRefresh.configs.vite,
      jsxA11y.configs.strict,
      jsdoc.configs['flat/recommended-typescript-error'],
    ],
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // Exported code must be documented; internal helpers may rely on their names.
      'jsdoc/require-jsdoc': [
        'error',
        {
          publicOnly: true,
          require: {
            ArrowFunctionExpression: true,
            ClassDeclaration: true,
            FunctionDeclaration: true,
            FunctionExpression: true,
          },
          contexts: ['TSInterfaceDeclaration', 'TSTypeAliasDeclaration', 'TSEnumDeclaration'],
        },
      ],
      // Props are documented on their interface, so a destructured props object needs no @param of its own.
      'jsdoc/require-param': ['error', { checkDestructuredRoots: false }],
      // TypeScript already states what a function returns; @returns is written where it adds meaning.
      'jsdoc/require-returns': 'off',
      // TSDoc style: one blank line between the description and the first tag.
      'jsdoc/tag-lines': ['error', 'never', { startLines: 1 }],
      // TSDoc tags that plain JSDoc does not know.
      'jsdoc/check-tag-names': [
        'error',
        { definedTags: ['remarks', 'defaultValue', 'packageDocumentation'] },
      ],
    },
  },
  {
    // Configuration files run in Node, not the browser.
    files: ['*.config.{js,ts}'],
    languageOptions: { globals: globals.node },
  },
  prettier,
]);
