import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import './styles.css'

/**
 * `retry: false` is deliberate.
 *
 * TanStack Query retries a failed query three times by default, which is right
 * for flaky networks and wrong here: nearly every error this app sees is an
 * authored 4xx from GlobalExceptionHandler - a 404 for an unknown job, a 400
 * for a rejected requirements list. Retrying those three times delays a message
 * the user should see immediately and triples the log noise for a request that
 * was always going to fail the same way.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
      staleTime: 5000,
    },
  },
})

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
