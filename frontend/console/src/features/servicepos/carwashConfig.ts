/** carwashConfig — the carwash vertical's wiring for the generic ServicePos surface. */

import type { VerticalPosConfig } from './config'

export const carwashConfig: VerticalPosConfig = {
  vertical: 'carwash',
  apiBase: '/api/v1/carwash',
  i18nNs: 'carwashPos',
  location: {
    fieldName: 'bay',
    labelKey: 'carwashPos.bay',
    placeholderKey: 'carwashPos.bayPlaceholder',
    required: true,
  },
  vehicleField: true,
  attribution: { enabled: true, required: false, labelKey: 'carwashPos.washer' },
  packagesPath: 'packages',
  primaryItemType: 'PACKAGE',
  primaryItemLabels: {
    titleKey: 'carwashPos.packages',
    emptyKey: 'carwashPos.emptyPackages',
    selectLabelKey: 'carwashPos.selectPackageLabel',
    selectedLabelKey: 'carwashPos.packageSelectedLabel',
    addLabelKey: 'serviceCatalog.addPackage',
    editLabelKey: 'serviceCatalog.editPackage',
    summaryEmptyKey: 'servicePos.summary.empty',
  },
  staffTitleKey: 'serviceCatalog.washersTitle',
  staffLabels: {
    addLabelKey: 'serviceCatalog.addWasher',
    emptyKey: 'serviceCatalog.emptyWashers',
    editLabelKey: 'serviceCatalog.editWasher',
  },
}
