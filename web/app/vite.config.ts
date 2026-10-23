import { defineConfig } from 'vite';

export default defineConfig({
  base: './',
  build: {
    target: 'es2022',
    rollupOptions: { input: { main: 'index.html', compare: 'compare.html' } },
  },
  worker: { format: 'es' },
});
