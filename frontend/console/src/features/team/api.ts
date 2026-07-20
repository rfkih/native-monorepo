import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'

/** A company teammate as returned by GET /api/v1/users. */
export interface TeamMember {
  id: string
  username: string
  email: string
  roles: string[]
  enabled: boolean
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
