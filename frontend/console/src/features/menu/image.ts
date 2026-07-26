/**
 * image.ts — client-side image resize helper for menu item photos.
 *
 * Reads a File, downscales it to a max of 512 px on the long edge, and
 * returns a JPEG data URL (quality 0.7).  Rejects non-image files and results
 * larger than 1.5 MB (after compression).
 *
 * No external libraries — only Canvas API (universally supported in every
 * modern browser that runs Vite + React apps).
 */

const MAX_DIMENSION = 512
const JPEG_QUALITY = 0.7
/** 1.5 MB in bytes — data-URL is base-64, so multiply char count × 0.75 */
const MAX_DATA_URL_BYTES = 1.5 * 1024 * 1024

/**
 * Downscale `file` to a compact JPEG data URL.
 *
 * @throws {Error} with an i18n-friendly message code if the file is not an
 *   image or if the compressed result is still too large.
 */
export async function resizeImageFile(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) {
    throw new Error('menu.image.notAnImage')
  }

  const dataUrl = await readFileAsDataURL(file)

  const img = await loadImage(dataUrl)

  // Compute scale factor — keep aspect ratio, cap long edge at MAX_DIMENSION.
  const scale = Math.min(1, MAX_DIMENSION / Math.max(img.naturalWidth, img.naturalHeight))
  const width = Math.round(img.naturalWidth * scale)
  const height = Math.round(img.naturalHeight * scale)

  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height

  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('menu.image.canvasUnavailable')

  ctx.drawImage(img, 0, 0, width, height)

  const resultUrl = canvas.toDataURL('image/jpeg', JPEG_QUALITY)

  // data URLs are ASCII; each character is one byte in base-64 — the actual
  // binary payload is ≈ 75 % of the string length.
  const estimatedBytes = resultUrl.length * 0.75
  if (estimatedBytes > MAX_DATA_URL_BYTES) {
    throw new Error('menu.image.tooLarge')
  }

  return resultUrl
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

function readFileAsDataURL(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(new Error('menu.image.readError'))
    reader.readAsDataURL(file)
  })
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('menu.image.loadError'))
    img.src = src
  })
}
