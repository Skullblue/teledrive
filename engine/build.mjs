import { build } from 'esbuild';
import { polyfillNode } from 'esbuild-plugin-polyfill-node';
import path from 'node:path';
const empty = path.resolve('src/empty.js');
await build({
  entryPoints: ['src/engine.js'], bundle: true, minify: true, format: 'iife', platform: 'browser', target: ['chrome90'],
  outfile: 'dist/engine.bundle.js', logLevel: 'info',
  define: { global: 'globalThis', 'process.env.NODE_DEBUG': 'false' },
  alias: { crypto: path.resolve('node_modules/telegram/crypto/crypto.js'), fs: empty, 'graceful-fs': empty, 'node-localstorage': empty },
  plugins: [polyfillNode({ polyfills: { fs: false, crypto: false } })],
});
