/**
 * frontend/self-order — the anonymous, diner-facing counterpart of the POS (Phase 6, ADR 0029).
 *
 * A tiny, standalone Vite + React + TypeScript + Tailwind app — deliberately NOT sharing a package
 * with the console (kept minimal per the task: no router, no state library, no PWA). A diner scans
 * a table/kiosk QR code printed from the console's SelfOrderQr panel; the URL carries the token as
 * `?t=`, read once here and kept in memory only (see api.ts) — never persisted, since the token
 * itself is the only credential and a QR code is trivially re-scannable if ever needed again.
 */
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
