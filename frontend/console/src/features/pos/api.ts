import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'

export interface MenuItem {
  id: string
  businessId: string
  name: string
  category: string
  priceMinor: number
  currency: string
  active: boolean
}

export interface OrderLineInput {
  menuItemId: string
  qty: number
}

export interface OrderResponse {
  orderId: string
  businessId: string
  totalMinor: number
  currency: string
  saleId: string
  lines: {
    menuItemId: string
    name: string
    unitPriceMinor: number
    qty: number
    lineTotalMinor: number
  }[]
}

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

export function useMenu(session: CompanySession) {
  return useQuery({
    queryKey: ['menu', session.companyId, session.businessId],
    queryFn: () =>
      apiFetch<MenuItem[]>('/api/v1/menu', {
        tenant: tenantOf(session),
        query: { businessId: session.businessId },
      }),
  })
}

export function useCheckout(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (lines: OrderLineInput[]) =>
      apiFetch<OrderResponse>('/api/v1/orders', {
        method: 'POST',
        tenant: tenantOf(session),
        body: { businessId: session.businessId, idempotencyKey: crypto.randomUUID(), lines },
      }),
    onSuccess: () => {
      // The order recorded a Sale → the consolidated dashboard revenue changes.
      void qc.invalidateQueries({ queryKey: ['pnl'] })
    },
  })
}

/**
 * A handful of sample dishes so a fresh business has a menu to ring up (dev convenience).
 *
 * Amounts are integer MINOR units in each currency, matching libs/money: IDR has ZERO minor digits
 * (so `idr` is whole rupiah — 35_000 = Rp 35.000), USD has two (so `usd` is cents — 250 = $2.50).
 */
const SAMPLE_MENU: { name: string; category: string; idr: number; usd: number }[] = [
  { name: 'Nasi Goreng', category: 'mains', idr: 35_000, usd: 250 },
  { name: 'Mie Goreng', category: 'mains', idr: 32_000, usd: 230 },
  { name: 'Sate Ayam', category: 'mains', idr: 40_000, usd: 290 },
  { name: 'Gado-Gado', category: 'mains', idr: 30_000, usd: 220 },
  { name: 'Es Teh Manis', category: 'drinks', idr: 8_000, usd: 60 },
  { name: 'Kopi Tubruk', category: 'drinks', idr: 15_000, usd: 110 },
  { name: 'Pisang Goreng', category: 'desserts', idr: 18_000, usd: 130 },
]

export function useSeedMenu(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      for (const item of SAMPLE_MENU) {
        await apiFetch('/api/v1/menu', {
          method: 'POST',
          tenant: tenantOf(session),
          body: {
            businessId: session.businessId,
            name: item.name,
            category: item.category,
            priceMinor: session.baseCurrency === 'IDR' ? item.idr : item.usd,
            currency: session.baseCurrency,
          },
        })
      }
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['menu'] }),
  })
}
