/**
 * Client-side CSV download for a loaded statement/report — the export path the statements never
 * had (audit finding 16). Quotes every cell; Excel/Sheets open it directly.
 *
 * Lives outside the statements feature because AR/AP aging, the tax report, and budgets export
 * through it too — and `parts.tsx` must only export components (react-refresh).
 */
export function downloadCsv(filename: string, rows: (string | number)[][]) {
  const csv = rows
    .map((row) => row.map((cell) => `"${String(cell).replaceAll('"', '""')}"`).join(','))
    .join('\r\n')
  // U+FEFF = UTF-8 BOM, so Excel detects the encoding instead of assuming ANSI.
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
