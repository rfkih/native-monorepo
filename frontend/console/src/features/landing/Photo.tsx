import type { PhotoSource } from './photos'

/**
 * The single `<img>` wrapper for the marketing photos (photos.ts), so the hygiene rules
 * (intrinsic dimensions against CLS, async decode, lazy below the fold, priority hero) live in
 * one place.
 */
export function Photo({
  photo,
  alt,
  className,
  sizes,
  priority = false,
}: {
  photo: PhotoSource
  /** Empty string marks the image decorative (hidden from assistive tech). */
  alt: string
  className?: string
  /** Required when the source has a srcSet, so the browser picks the right width. */
  sizes?: string
  /** Above-the-fold images only: eager load + high fetch priority. */
  priority?: boolean
}) {
  return (
    <img
      src={photo.src}
      srcSet={photo.srcSet}
      sizes={photo.srcSet ? sizes : undefined}
      width={photo.width}
      height={photo.height}
      alt={alt}
      aria-hidden={alt === '' || undefined}
      decoding="async"
      loading={priority ? 'eager' : 'lazy'}
      fetchPriority={priority ? 'high' : 'auto'}
      className={className}
    />
  )
}
