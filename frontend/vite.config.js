import { defineConfig, loadEnv } from 'vite';
import { fileURLToPath } from 'node:url';

const rootEnvDir = fileURLToPath(new URL('..', import.meta.url));

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, rootEnvDir, '');
  const ngrokDomainUri = env.NGROK_DOMAIN_URI?.trim();
  const ngrokHost = ngrokDomainUri
      ? new URL(ngrokDomainUri).hostname
      : null;

  return {
    server: {
      port: 5173,
      ...(ngrokHost ? { allowedHosts: [ngrokHost] } : {}),
      proxy: {
        '/api': 'http://localhost:8080',
        '/oauth2': 'http://localhost:8080',
        '/ws': {
          target: 'ws://localhost:8080',
          ws: true,
        },
      },
    },
  };
});