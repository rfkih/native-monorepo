/**
 * barbershopConfig — the barbershop vertical's wiring for the generic ServicePos surface
 * (POS-parity program Phase 2). Same wire contract as carwash except: apiBase, the primary catalog
 * resource is 'services' (not 'packages'), the location field is optional ("chair"), there is no
 * vehicle field, and staff attribution is REQUIRED (checkout is blocked without a barber).
 */

import type { VerticalPosConfig } from './config'

export const barbershopConfig: VerticalPosConfig = {
  vertical: 'barbershop',
  apiBase: '/api/v1/barbershop',
  i18nNs: 'barbershopPos',
  location: {
    fieldName: 'chair',
    labelKey: 'barbershopPos.chair',
    placeholderKey: 'barbershopPos.chairPlaceholder',
    required: false,
  },
  vehicleField: false,
  attribution: {
    enabled: true,
    required: true,
    labelKey: 'barbershopPos.barber',
    requiredHintKey: 'barbershopPos.barberRequiredHint',
  },
  packagesPath: 'services',
  primaryItemType: 'SERVICE',
  primaryItemLabels: {
    titleKey: 'barbershopPos.services',
    emptyKey: 'barbershopPos.emptyServices',
    selectLabelKey: 'barbershopPos.selectServiceLabel',
    selectedLabelKey: 'barbershopPos.serviceSelectedLabel',
    addLabelKey: 'barbershopPos.addService',
    editLabelKey: 'barbershopPos.editService',
    summaryEmptyKey: 'barbershopPos.summaryEmpty',
  },
  staffTitleKey: 'barbershopPos.staffTitle',
  staffLabels: {
    addLabelKey: 'barbershopPos.addBarber',
    emptyKey: 'barbershopPos.emptyBarbers',
    editLabelKey: 'barbershopPos.editBarber',
  },
}
