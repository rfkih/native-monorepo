/**
 * useOffline.ts — the single hook POS surfaces + OfflineBanner/SyncCenter read to know: are we
 * offline right now, and what does the queue look like. Combines `navigator.onLine` +
 * online/offline events with the queue's useSyncExternalStore snapshot (queue.ts).
 */
import { useEffect, useState, useSyncExternalStore } from 'react'
import { getSnapshot, subscribe } from './queue'
import { startAutoSync } from './sync'

export interface OfflineState {
  offline: boolean
  queuedCount: number
  syncingCount: number
  mismatchCount: number
  rejectedCount: number
  syncedCount: number
}

function subscribeOnlineStatus(callback: () => void): () => void {
  window.addEventListener('online', callback)
  window.addEventListener('offline', callback)
  return () => {
    window.removeEventListener('online', callback)
    window.removeEventListener('offline', callback)
  }
}

function useOnlineStatus(): boolean {
  // navigator.onLine can't be read during SSR — this app is client-only (Vite SPA), so a plain
  // lazy initializer is safe.
  const [online, setOnline] = useState(() => (typeof navigator === 'undefined' ? true : navigator.onLine))

  useEffect(() => subscribeOnlineStatus(() => setOnline(navigator.onLine)), [])

  return online
}

export function useOffline(): OfflineState {
  const online = useOnlineStatus()
  const rows = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)

  // Installs the replay engine's online-event/interval triggers exactly once, however many POS
  // screens/banners mount this hook (startAutoSync is itself idempotent).
  useEffect(() => {
    startAutoSync()
  }, [])

  let queuedCount = 0
  let syncingCount = 0
  let mismatchCount = 0
  let rejectedCount = 0
  let syncedCount = 0
  for (const row of rows) {
    switch (row.status) {
      case 'QUEUED':
        queuedCount++
        break
      case 'SYNCING':
        syncingCount++
        break
      case 'SYNCED_WITH_MISMATCH':
        mismatchCount++
        break
      case 'REJECTED':
        rejectedCount++
        break
      case 'SYNCED':
        syncedCount++
        break
    }
  }

  return { offline: !online, queuedCount, syncingCount, mismatchCount, rejectedCount, syncedCount }
}
