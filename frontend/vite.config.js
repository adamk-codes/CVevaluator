import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The dev server proxies /api to Spring Boot on 8080.
//
// This is why there is no CORS configuration anywhere in the backend, and that
// is deliberate. To the browser every request is same-origin against
// localhost:5173, so no preflight is ever issued and no `Access-Control-*`
// header is needed. The alternative - a WebMvcConfigurer adding allowed origins
// - means shipping a permanent security-relevant config change to solve a
// problem that only exists while developing.
//
// It follows that `npm run build` output must be served from the same origin as
// the API (or behind a reverse proxy that joins them). If the built frontend is
// ever hosted separately, CORS becomes a real backend decision at that point
// rather than a default nobody chose.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
