/** carwashConfig — the carwash vertical's wiring for the generic ServicePos surface. */

import type { VerticalPosConfig } from './config'

export const carwashConfig: VerticalPosConfig = {
  vertical: 'carwash',
  apiBase: '/api/v1/carwash',
  i18nNs: 'carwashPos',
  location: { labelKey: 'carwashPos.bay', required: true },
  vehicleField: true,
  attribution: { enabled: true, required: false, labelKey: 'carwashPos.washer' },
}
