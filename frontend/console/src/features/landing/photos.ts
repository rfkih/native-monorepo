/**
 * Real photography for the public marketing surfaces.
 *
 * Every image is a licensed Unsplash photo (credits + license in src/assets/landing/SOURCES.md),
 * pre-sized to its slot and committed as a hashed Vite asset — nothing is fetched from a third
 * party at runtime. Render through `<Photo>` (Photo.tsx) — kept in its own file so this data
 * module and that component file each satisfy react-refresh/only-export-components.
 */
import heroW768 from '@/assets/landing/hero-w768.webp'
import heroW1280 from '@/assets/landing/hero-w1280.webp'
import heroW1920 from '@/assets/landing/hero-w1920.webp'
import posW640 from '@/assets/landing/pos-w640.webp'
import posW960 from '@/assets/landing/pos-w960.webp'
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

/** CTA band texture — carwash detail under the brand overlay. */
export const ctaPhoto: PhotoSource = { src: ctaW1280, width: 1280, height: 640 }
