import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { viteSingleFile } from 'vite-plugin-singlefile'

// Some phone browsers run old engines: a Mate 9's built-in one skips type="module" scripts without a
// word, which leaves a blank page. So the single script is a plain one at the end of the body.
function classicScript() {
  return {
    name: 'classic-script',
    enforce: 'post',
    generateBundle(_options, bundle) {
      const page = bundle['index.html']
      const match = page.source.match(/<script type="module"[^>]*>([\s\S]*?)<\/script>/)
      if (!match) throw new Error('no inlined module script found')
      page.source = page.source.replace(match[0], () => '').replace('</body>', () => `<script>${match[1]}</script>\n</body>`)
    }
  }
}

// One self-contained page, served by the publish service at /admin. It replaces publish/admin.html.
export default defineConfig({
  plugins: [react(), viteSingleFile(), classicScript()],
  build: { outDir: 'dist', chunkSizeWarningLimit: 4000, target: 'es2015', cssTarget: 'chrome61', rollupOptions: { output: { format: 'iife' } } },
  server: { proxy: { '/api': process.env.API_TARGET || 'http://127.0.0.1:8799' } }
})
