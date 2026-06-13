/** @type {import('eslint').Linter.Config} */
const config = {
  env: {
    browser: true,
    es2020: true,
    node: true,
  },
  extends: ['eslint:recommended', 'plugin:@typescript-eslint/recommended'],
  globals: {
    cordova: 'readonly',
  },
  parser: '@typescript-eslint/parser',
  parserOptions: {
    project: './tsconfig.lint.json',
    tsconfigRootDir: __dirname,
    sourceType: 'module',
  },
  plugins: ['@typescript-eslint'],
  ignorePatterns: ['www/**', 'test/**', 'angular/**'],
  rules: {
    'no-prototype-builtins': 'off',
  },
};

module.exports = config;
