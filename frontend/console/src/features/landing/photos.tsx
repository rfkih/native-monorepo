/**
 * Real photography for the public marketing surfaces.
 *
 * Every image is a licensed Unsplash photo (credits + license in src/assets/landing/SOURCES.md),
 * pre-sized to its slot and committed as a hashed Vite asset — nothing is fetched from a third
 * party at runtime. `<Photo>` is the single `<img>` wrapper so the hygiene rules (intrinsic
 * dimensions against CLS, async decode, lazy below the fold, priority hero) live in one place.
 */
import heroW768 from '@/assets/landing/hero-w768.webp'
import heroW1280 from '@/assets/landing/hero-w1280.webp'
import heroW1920 from '@/assets/landing/hero-w1920.webp'
import posW640 from '@/assets/landing/pos-w640.webp'
import posW960 from '@/assets/landing/pos-w960.webp'
import portraitW192 from '@/assets/landing/portrait-w192.webp'
import ctaW1280 from '@/assets/landing/cta-w1280.webp'

export interface PhotoSource {
  src: string
  srcSet?: string
  width: number
  height: number
}

/** Hero backdrop — a warung counter at night (the product's home turf). */
export const heroPhoto: PhotoSource = {
  src: heroW1920,
  srcSet: `${heroW768} 768w, ${heroW1280} 1280w, ${heroW1920} 1920w`,
  width: 1920,
  height: 1087,
}

/** POS feature row — a barista mid-shift behind the machine. */
export const posPhoto: PhotoSource = {
  src: posW960,
  srcSet: `${posW640} 640w, ${posW960} 960w`,
  width: 960,
  height: 720,
}

/** Testimonial avatar. */
export const portraitPhoto: PhotoSource = { src: portraitW192, width: 192, height: 192 }

/** CTA band texture — carwash detail under the brand overlay. */
export const ctaPhoto: PhotoSource = { src: ctaW1280, width: 1280, height: 640 }

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
