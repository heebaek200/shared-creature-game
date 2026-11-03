import { defineConfig } from 'vite';

/** Backend와 Browser를 같은 Origin처럼 사용하는 개발 Proxy입니다. */
export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/oauth2': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
});
