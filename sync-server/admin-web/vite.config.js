import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { viteSingleFile } from 'vite-plugin-singlefile'

// One self-contained page, served by the publish service at /admin. It replaces publish/admin.html.
export default defineConfig({
  plugins: [react(), viteSingleFile()],
  build: { outDir: 'dist', chunkSizeWarningLimit: 4000 },
  server: { proxy: { '/api': process.env.API_TARGET || 'http://127.0.0.1:8799' } }
})
