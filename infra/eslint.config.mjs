// eslint.config.mjs
//
// Lint rules for the CDK app. `npm run lint` runs them and fails on any warning, locally and in CI.
//
// - typescript-eslint, strict and type-aware: unsafe `any`, floating promises, needless conditions.
// - eslint-plugin-jsdoc: every exported construct, function, interface and type has a TSDoc comment
//   (CONTRIBUTING.md, "Documentation standards").
//
// typescript-eslint needs the TypeScript compiler API, which TypeScript 7 does not provide yet. That is why this
// package uses TypeScript 6.0, like web/.

import js from '@eslint/js';
import jsdoc from 'eslint-plugin-jsdoc';
import { defineConfig, globalIgnores } from 'eslint/config';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default defineConfig([
  globalIgnores(['cdk.out', 'coverage', '*.js', '*.mjs']),
  {
    files: ['**/*.ts'],
    extends: [
      js.configs.recommended,
      tseslint.configs.strictTypeChecked,
      tseslint.configs.stylisticTypeChecked,
      jsdoc.configs['flat/recommended-typescript-error'],
    ],
    languageOptions: {
      globals: globals.node,
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
      // TypeScript already states what a function returns; @returns is written where it adds meaning.
      'jsdoc/require-returns': 'off',
      // Constructor parameters of a construct (scope, id, props) are the CDK convention, not worth a @param each.
      'jsdoc/require-param': ['error', { checkConstructors: false }],
      // TSDoc style: one blank line between the description and the first tag.
      'jsdoc/tag-lines': ['error', 'never', { startLines: 1 }],
      // TSDoc tags that plain JSDoc does not know.
      'jsdoc/check-tag-names': ['error', { definedTags: ['remarks', 'defaultValue', 'packageDocumentation'] }],
    },
  },
]);
