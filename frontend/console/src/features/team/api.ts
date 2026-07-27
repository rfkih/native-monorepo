import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'

/** A single outlet assignment row returned by GET /api/v1/users/{userId}/outlets and GET /api/v1/users/me/outlets. */
export interface UserOutlet {
  orgUnitId: string
  name: string
}

/** A company teammate as returned by GET /api/v1/users. */
export interface TeamMember {
  id: string
  username: string
  email: string
  roles: string[]
  enabled: boolean
  /** Number of outlets explicitly assigned to this user; 0 means unrestricted ("all outlets"). */
  outletCount: number
}

/** POST /api/v1/users response — contains a one-time temporaryPassword. */
export interface InviteResponse {
  id: string
  email: string
  role: string
  temporaryPassword: string
}

/** POST /api/v1/users body. */
export interface InviteMemberBody {
  email: string
  role: string
}

/** PATCH /api/v1/users/{id} body. */
export interface UpdateMemberBody {
  role?: string
  enabled?: boolean
}

/** GET /api/v1/users — flat list of company teammates. */
export function useTeam(params: { companyId: string; actor: string; enabled: boolean }) {
  const { companyId, actor, enabled } = params
  return useQuery({
    enabled,
    queryKey: ['team', companyId],
    queryFn: () =>
      apiFetch<TeamMember[]>('/api/v1/users', {
        tenant: { companyId, actor },
      }),
  })
}

/** POST /api/v1/users — invite a new teammate; response includes a one-time temp password. */
export function useInviteMember(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: InviteMemberBody) =>
      apiFetch<InviteResponse>('/api/v1/users', {
        method: 'POST',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['team', companyId] })
    },
  })
}

/** PATCH /api/v1/users/{id} — change role or toggle enabled. */
export function useUpdateMember(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateMemberBody }) =>
      apiFetch<TeamMember>(`/api/v1/users/${id}`, {
        method: 'PATCH',
        tenant: { companyId, actor },
        body,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['team', companyId] })
    },
  })
}

/** DELETE /api/v1/users/{id} — deactivate a teammate (returns 204). */
export function useDeactivateMember(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch<null>(`/api/v1/users/${id}`, {
        method: 'DELETE',
        tenant: { companyId, actor },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['team', companyId] })
    },
  })
}

/**
 * GET /api/v1/users/{userId}/outlets — the explicit outlet assignments for a specific user.
 * Owner/manager-gated. An empty list means the user is unrestricted ("all outlets").
 */
export function useUserOutlets(params: {
  userId: string
  companyId: string
  actor: string
  enabled: boolean
}) {
  const { userId, companyId, actor, enabled } = params
  return useQuery<UserOutlet[]>({
    enabled: enabled && !!userId,
    queryKey: ['userOutlets', companyId, userId],
    queryFn: async () => {
      const result = await apiFetch<UserOutlet[]>(`/api/v1/users/${userId}/outlets`, {
        tenant: { companyId, actor },
      })
      return result ?? []
    },
  })
}

/**
 * PUT /api/v1/users/{userId}/outlets — replace the outlet assignment set for a user.
 * Sending an empty array restores "unrestricted" (all outlets).
 * Invalidates both the team list and the specific user's outlet query.
 */
export function useSetUserOutlets(params: { companyId: string; actor: string }) {
  const { companyId, actor } = params
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ userId, orgUnitIds }: { userId: string; orgUnitIds: string[] }) =>
      apiFetch<UserOutlet[]>(`/api/v1/users/${userId}/outlets`, {
        method: 'PUT',
        tenant: { companyId, actor },
        body: { orgUnitIds },
      }),
    onSuccess: (_data, { userId }) => {
      void queryClient.invalidateQueries({ queryKey: ['team', companyId] })
      void queryClient.invalidateQueries({ queryKey: ['userOutlets', companyId, userId] })
    },
  })
}
