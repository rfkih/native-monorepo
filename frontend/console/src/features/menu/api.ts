/**
 * Menu Management API — mutation hooks for the admin menu screen.
 *
 * Types (MenuItem, ModifierGroupResponse, ModifierOptionResponse, useMenu, useCategories) are
 * imported from features/pos/api.ts and re-exported so callers can import from one place.
 *
 * New queries/mutations added here:
 *   - useAdminModifierGroups  — GET /api/v1/menu/{itemId}/modifier-groups?adminView=true
 *   - useCreateMenuItem       — POST /api/v1/menu
 *   - use86Item / useUn86Item — PATCH /api/v1/menu/{itemId}/86|un-86
 *   - useCreateModifierGroup  — POST /api/v1/menu/{itemId}/modifier-groups
 *   - useCreateModifierOption — POST /api/v1/menu/{itemId}/modifier-groups/{groupId}/options
 *   - use86Option / useUn86Option
 *
 * Money rule (rule 8): all amounts are integer minor units on the wire.
 * Strings rule (rule 9): no hardcoded user-facing strings here.
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import type { CompanySession } from '@/lib/session'
import type {
  CategoryResponse,
  MenuItem,
  ModifierGroupResponse,
  ModifierOptionResponse,
} from '@/features/pos/api'

// Re-export shared types + the category query so MenuManagement.tsx imports from one place.
export type { CategoryResponse, MenuItem, ModifierGroupResponse, ModifierOptionResponse }
export { useCategories } from '@/features/pos/api'

// ---------------------------------------------------------------------------
// Menu categories — the picklist behind the item's category field.
// POST   /api/v1/menu/categories                    — create a category
// PATCH  /api/v1/menu/categories/{id}/deactivate    — hide a category
// (list is useCategories, GET /api/v1/menu/categories?businessId=…)
// ---------------------------------------------------------------------------

const CATEGORIES_KEY = (s: CompanySession) => ['menu-categories', s.companyId, s.businessId]

export function useCreateCategory(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ name, displayOrder }: { name: string; displayOrder: number }) =>
      apiFetch<CategoryResponse>('/api/v1/menu/categories', {
        method: 'POST',
        tenant: tenantOf(session),
        body: { businessId: session.businessId, name, displayOrder },
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: CATEGORIES_KEY(session) }),
  })
}

export function useDeactivateCategory(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch<CategoryResponse>(`/api/v1/menu/categories/${id}/deactivate`, {
        method: 'PATCH',
        tenant: tenantOf(session),
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: CATEGORIES_KEY(session) }),
  })
}

// ---------------------------------------------------------------------------
// Helper
// ---------------------------------------------------------------------------

function tenantOf(session: CompanySession) {
  return { companyId: session.companyId, actor: session.actor }
}

// ---------------------------------------------------------------------------
// Admin modifier groups — includes unavailable options (adminView=true)
// ---------------------------------------------------------------------------

export function useAdminModifierGroups(session: CompanySession, itemId: string | null) {
  return useQuery({
    queryKey: ['modifier-groups-admin', session.companyId, itemId],
    enabled: itemId != null,
    queryFn: () =>
      apiFetch<ModifierGroupResponse[]>(`/api/v1/menu/${itemId}/modifier-groups`, {
        tenant: tenantOf(session),
        query: { adminView: 'true' },
      }),
  })
}

// ---------------------------------------------------------------------------
// Create menu item — POST /api/v1/menu
// ---------------------------------------------------------------------------

export interface CreateMenuItemInput {
  name: string
  category: string
  priceMinor: number
  currency: string
  /** Optional photo stored as a data URL. */
  imageUrl?: string | null
}

export function useCreateMenuItem(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ name, category, priceMinor, currency, imageUrl }: CreateMenuItemInput) =>
      apiFetch<MenuItem>('/api/v1/menu', {
        method: 'POST',
        tenant: tenantOf(session),
        body: {
          businessId: session.businessId,
          name,
          category,
          priceMinor,
          currency,
          imageUrl: imageUrl ?? null,
        },
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Update menu item — PATCH /api/v1/menu/{itemId}
// All fields are optional; omitting a field leaves it unchanged on the server.
// Pass imageUrl: '' to clear the photo; pass a data URL to set/replace it.
// ---------------------------------------------------------------------------

export interface UpdateMenuItemInput {
  itemId: string
  name?: string
  category?: string
  priceMinor?: number
  /** Pass a data URL to set/replace, empty string to clear, undefined to leave unchanged. */
  imageUrl?: string
}

export function useUpdateMenuItem(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ itemId, name, category, priceMinor, imageUrl }: UpdateMenuItemInput) =>
      apiFetch<MenuItem>(`/api/v1/menu/${itemId}`, {
        method: 'PATCH',
        tenant: tenantOf(session),
        body: {
          ...(name !== undefined ? { name } : {}),
          ...(category !== undefined ? { category } : {}),
          ...(priceMinor !== undefined ? { priceMinor } : {}),
          ...(imageUrl !== undefined ? { imageUrl } : {}),
        },
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Delete menu item — DELETE /api/v1/menu/{itemId} → 204
// ---------------------------------------------------------------------------

export function useDeleteItem(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (itemId: string) =>
      apiFetch<void>(`/api/v1/menu/${itemId}`, {
        method: 'DELETE',
        tenant: tenantOf(session),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// 86 / un-86 item availability
// ---------------------------------------------------------------------------

export function use86Item(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (itemId: string) =>
      apiFetch<void>(`/api/v1/menu/${itemId}/86`, {
        method: 'PATCH',
        tenant: tenantOf(session),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

export function useUn86Item(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (itemId: string) =>
      apiFetch<void>(`/api/v1/menu/${itemId}/un-86`, {
        method: 'PATCH',
        tenant: tenantOf(session),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Create modifier group — POST /api/v1/menu/{itemId}/modifier-groups
// ---------------------------------------------------------------------------

export interface CreateModifierGroupInput {
  itemId: string
  name: string
  selectionType: 'SINGLE' | 'MULTI'
  required: boolean
  minSelect: number
  maxSelect: number
  displayOrder: number
}

export function useCreateModifierGroup(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      itemId,
      name,
      selectionType,
      required,
      minSelect,
      maxSelect,
      displayOrder,
    }: CreateModifierGroupInput) =>
      apiFetch<ModifierGroupResponse>(
        `/api/v1/menu/${itemId}/modifier-groups`,
        {
          method: 'POST',
          tenant: tenantOf(session),
          body: {
            businessId: session.businessId,
            name,
            selectionType,
            required,
            minSelect,
            maxSelect,
            displayOrder,
          },
        },
      ),
    onSuccess: (_data, vars) => {
      void qc.invalidateQueries({
        queryKey: ['modifier-groups-admin', session.companyId, vars.itemId],
      })
      // Also invalidate the POS menu so the cashier sees updated groups.
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Create modifier option — POST /api/v1/menu/{itemId}/modifier-groups/{groupId}/options
// ---------------------------------------------------------------------------

export interface CreateModifierOptionInput {
  itemId: string
  groupId: string
  name: string
  priceDeltaMinor: number
  displayOrder: number
}

export function useCreateModifierOption(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      itemId,
      groupId,
      name,
      priceDeltaMinor,
      displayOrder,
    }: CreateModifierOptionInput) =>
      apiFetch<ModifierOptionResponse>(
        `/api/v1/menu/${itemId}/modifier-groups/${groupId}/options`,
        {
          method: 'POST',
          tenant: tenantOf(session),
          body: {
            businessId: session.businessId,
            name,
            priceDeltaMinor,
            displayOrder,
          },
        },
      ),
    onSuccess: (_data, vars) => {
      void qc.invalidateQueries({
        queryKey: ['modifier-groups-admin', session.companyId, vars.itemId],
      })
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Update modifier group — PATCH /api/v1/menu/{itemId}/modifier-groups/{groupId}
// ---------------------------------------------------------------------------

export interface UpdateModifierGroupInput {
  itemId: string
  groupId: string
  name: string
  selectionType: 'SINGLE' | 'MULTI'
  required: boolean
  minSelect: number
  maxSelect: number
  displayOrder: number
}

export function useUpdateModifierGroup(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      itemId,
      groupId,
      name,
      selectionType,
      required,
      minSelect,
      maxSelect,
      displayOrder,
    }: UpdateModifierGroupInput) =>
      apiFetch<ModifierGroupResponse>(
        `/api/v1/menu/${itemId}/modifier-groups/${groupId}`,
        {
          method: 'PATCH',
          tenant: tenantOf(session),
          body: { name, selectionType, required, minSelect, maxSelect, displayOrder },
        },
      ),
    onSuccess: (_data, vars) => {
      void qc.invalidateQueries({
        queryKey: ['modifier-groups-admin', session.companyId, vars.itemId],
      })
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Delete modifier group — DELETE /api/v1/menu/{itemId}/modifier-groups/{groupId} → 204
// ---------------------------------------------------------------------------

export interface DeleteModifierGroupInput {
  itemId: string
  groupId: string
}

export function useDeleteModifierGroup(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ itemId, groupId }: DeleteModifierGroupInput) =>
      apiFetch<void>(`/api/v1/menu/${itemId}/modifier-groups/${groupId}`, {
        method: 'DELETE',
        tenant: tenantOf(session),
      }),
    onSuccess: (_data, vars) => {
      void qc.invalidateQueries({
        queryKey: ['modifier-groups-admin', session.companyId, vars.itemId],
      })
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Update modifier option — PATCH /api/v1/menu/{itemId}/modifier-groups/{groupId}/options/{optionId}
// ---------------------------------------------------------------------------

export interface UpdateModifierOptionInput {
  itemId: string
  groupId: string
  optionId: string
  name: string
  priceDeltaMinor: number
  displayOrder: number
}

export function useUpdateModifierOption(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      itemId,
      groupId,
      optionId,
      name,
      priceDeltaMinor,
      displayOrder,
    }: UpdateModifierOptionInput) =>
      apiFetch<ModifierOptionResponse>(
        `/api/v1/menu/${itemId}/modifier-groups/${groupId}/options/${optionId}`,
        {
          method: 'PATCH',
          tenant: tenantOf(session),
          body: { name, priceDeltaMinor, displayOrder },
        },
      ),
    onSuccess: (_data, vars) => {
      void qc.invalidateQueries({
        queryKey: ['modifier-groups-admin', session.companyId, vars.itemId],
      })
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// Delete modifier option — DELETE /api/v1/menu/{itemId}/modifier-groups/{groupId}/options/{optionId} → 204
// ---------------------------------------------------------------------------

export interface DeleteModifierOptionInput {
  itemId: string
  groupId: string
  optionId: string
}

export function useDeleteModifierOption(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ itemId, groupId, optionId }: DeleteModifierOptionInput) =>
      apiFetch<void>(
        `/api/v1/menu/${itemId}/modifier-groups/${groupId}/options/${optionId}`,
        {
          method: 'DELETE',
          tenant: tenantOf(session),
        },
      ),
    onSuccess: (_data, vars) => {
      void qc.invalidateQueries({
        queryKey: ['modifier-groups-admin', session.companyId, vars.itemId],
      })
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

// ---------------------------------------------------------------------------
// 86 / un-86 modifier option availability
// ---------------------------------------------------------------------------

export interface ToggleOptionInput {
  itemId: string
  groupId: string
  optionId: string
}

export function use86Option(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ itemId, groupId, optionId }: ToggleOptionInput) =>
      apiFetch<void>(
        `/api/v1/menu/${itemId}/modifier-groups/${groupId}/options/${optionId}/86`,
        {
          method: 'PATCH',
          tenant: tenantOf(session),
        },
      ),
    onSuccess: (_data, vars) => {
      void qc.invalidateQueries({
        queryKey: ['modifier-groups-admin', session.companyId, vars.itemId],
      })
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}

export function useUn86Option(session: CompanySession) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ itemId, groupId, optionId }: ToggleOptionInput) =>
      apiFetch<void>(
        `/api/v1/menu/${itemId}/modifier-groups/${groupId}/options/${optionId}/un-86`,
        {
          method: 'PATCH',
          tenant: tenantOf(session),
        },
      ),
    onSuccess: (_data, vars) => {
      void qc.invalidateQueries({
        queryKey: ['modifier-groups-admin', session.companyId, vars.itemId],
      })
      void qc.invalidateQueries({ queryKey: ['menu', session.companyId, session.businessId] })
    },
  })
}
