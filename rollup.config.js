import { nodeResolve } from '@rollup/plugin-node-resolve';
import typescript from '@rollup/plugin-typescript';

/** @type {import('rollup').RollupOptions} */
const options = {
  input: [
    './src-typescript/BarcodeScanner.contract.ts',
    './src-typescript/BarcodeScanner.plugin.ts',
  ],
  output: {
    dir: './www/',
    format: 'cjs',
    sourcemap: 'inline',
  },
  external: ['cordova'],
  plugins: [
    typescript({
      tsconfig: './tsconfig.dist.json',
      include: ['src-typescript/**/*.ts'],
    }),
    nodeResolve({
      browser: true,
      extensions: ['.mjs', '.js', '.json', '.node', '.ts'],
    }),
  ],
};

export default options;
