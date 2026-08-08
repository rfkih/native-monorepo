import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import './i18n'
import { bootstrapPwa } from '@/lib/pwa'
import { queryClient } from '@/lib/queryClient'
import { AuthProvider } from '@/lib/auth'
import { SessionProvider } from '@/lib/SessionProvider'
import { PrinterProvider } from '@/lib/escpos/usePrinter'
import { OperatorSessionProvider } from '@/features/operator/OperatorSessionProvider'
import { App } from '@/app/App'

bootstrapPwa()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <SessionProvider>
          {/* ADR 0049 P3b — app-wide so the signed-in operator survives OutletGate's remount and
              any route change within the same device session (see the provider's own doc). */}
          <OperatorSessionProvider>
            <PrinterProvider>
              <BrowserRouter>
                <App />
              </BrowserRouter>
            </PrinterProvider>
          </OperatorSessionProvider>
        </SessionProvider>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
)
