/**
 * attachmentImage.ts — client-side prep for a bill attachment (ADR 0063).
 *
 * A PHOTO is downscaled + recompressed to a compact JPEG File so the upload is small and fast (phone
 * cameras produce 5–12 MB originals) while staying readable for a receipt; a PDF (or any non-image)
 * is passed through untouched. Returns a `File` ready for a multipart POST. Canvas-only, no libs
 * (mirrors features/menu/image.ts, but higher-res for legibility and returning a File, not a data
 * URL). The server re-validates magic bytes + the 5 MiB cap regardless — this is a UX/perf pass.
 */

const MAX_DIMENSION = 1600 // long edge — keeps receipt text legible while shrinking a big photo
const JPEG_QUALITY = 0.85
const MAX_BYTES = 5 * 1024 * 1024 // mirrors the server cap (BillAttachment.MAX_BYTES)

/** Prepare a picked file for upload: compress images, pass PDFs through. Throws an i18n message key. */
export async function prepareAttachment(file: File): Promise<File> {
  if (file.type === 'application/pdf' || !file.type.startsWith('image/')) {
    if (file.size > MAX_BYTES) throw new Error('bills.attach.tooLarge')
    return file
  }
  const compressed = await compressImage(file)
  if (compressed.size > MAX_BYTES) throw new Error('bills.attach.tooLarge')
  return compressed
}

async function compressImage(file: File): Promise<File> {
  const img = await loadImage(await readAsDataUrl(file))
  const scale = Math.min(1, MAX_DIMENSION / Math.max(img.naturalWidth, img.naturalHeight))
  const width = Math.max(1, Math.round(img.naturalWidth * scale))
  const height = Math.max(1, Math.round(img.naturalHeight * scale))

  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('bills.attach.imageError')
  ctx.drawImage(img, 0, 0, width, height)

  const blob = await new Promise<Blob | null>((resolve) =>
    canvas.toBlob(resolve, 'image/jpeg', JPEG_QUALITY),
  )
  if (!blob) throw new Error('bills.attach.imageError')
  const base = file.name.replace(/\.[^.]+$/, '') || 'photo'
  return new File([blob], `${base}.jpg`, { type: 'image/jpeg' })
}

function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(new Error('bills.attach.imageError'))
    reader.readAsDataURL(file)
  })
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('bills.attach.imageError'))
    img.src = src
  })
}
