import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      // Dev: the API gateway is the single edge that routes /api/v1/** to the services.
      // In production the console is served behind that same gateway, so paths are identical.
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
