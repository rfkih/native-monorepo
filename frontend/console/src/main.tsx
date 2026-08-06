import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import './i18n'
import { queryClient } from '@/lib/queryClient'
import { AuthProvider } from '@/lib/auth'
import { SessionProvider } from '@/lib/SessionProvider'
import { PrinterProvider } from '@/lib/escpos/usePrinter'
import { App } from '@/app/App'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <SessionProvider>
          <PrinterProvider>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </PrinterProvider>
        </SessionProvider>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
)
