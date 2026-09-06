import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Renderer bundle only. The main and preload processes are compiled by `tsconfig.main.json`, because
 * they run in Node rather than the browser and must not be bundled with the renderer's globals.
 *
 * `base: './'` matters: the packaged app loads the renderer from a file:// URL, where absolute asset
 * paths resolve against the filesystem root and silently 404.
 */
export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    outDir: 'dist/renderer',
    emptyOutDir: true,
  },
});
