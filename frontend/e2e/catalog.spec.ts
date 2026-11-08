import { expect, test } from '@playwright/test'
import type { Browser, BrowserContext, Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { basename, dirname, resolve } from 'node:path'
import type { components } from '../src/api/schema'
import { submitCapturedWhenAdmitted } from './support/admission'

type Product = components['schemas']['ProductResponse']
type Lot = components['schemas']['LotResponse']
type Store = components['schemas']['StoreResponse']
type Zone = components['schemas']['ZoneResponse']
type Sources = components['schemas']['SourceTracePage']
type Member = components['schemas']['MemberResponse']

test('owner preserves original labels and independent sources, reviews safety evidence and recovers a lost acknowledgement across a restart', async ({ browser }) => {
  test.setTimeout(150000)
  const owner = await account(browser)
  const organization = await write<{ id: string }>(owner.context, '/api/v1/organizations', { requestId: randomUUID(), name: `Catalog cooperative ${randomUUID().slice(0, 8)}` }, 201)
  const base = `/api/v1/organizations/${organization.id}`
  const store = await write<Store>(owner.context, `${base}/stores`, { requestId: randomUUID(), code: 'NORTH', name: 'Catalog North store', zoneId: 'Europe/Warsaw' }, 201)
  const zone = await write<Zone>(owner.context, `${base}/stores/${store.id}/storage-zones`, { name: 'Ambient room', temperatureClass: 'AMBIENT' }, 201)
  const page = await owner.context.newPage()
  await page.setViewportSize({ width: 390, height: 844 })
  const suffix = randomUUID().slice(0, 8).toUpperCase()
  const productName = `Packaged catalog bread ${suffix}`
  const futureDay = new Date(Date.now() + 45 * 86400000).toISOString().slice(0, 10)
  try {
    await page.goto(`/organizations/${organization.id}/catalog`)
    await expect(page.getByRole('heading', { name: 'Catalog Explorer' })).toBeVisible()
    await page.getByRole('button', { name: 'Create product', exact: true }).click()
    const productForm = page.getByRole('form', { name: 'Create product', exact: true })
    await productForm.getByLabel('SKU', { exact: true }).fill(`BREAD-${suffix}`)
    await productForm.getByLabel('Product name', { exact: true }).fill(productName)
    await productForm.getByLabel('GTIN (optional)').fill('036000291452')
    await productForm.getByLabel('Product category').selectOption('BAKERY')
    await productForm.getByLabel('Declared temperature class').selectOption('AMBIENT')
    let rejectedCsrf = false
    const productRequests: object[] = []
    await page.route('**/api/v1/auth/csrf', async route => {
      if (!rejectedCsrf) {
        rejectedCsrf = true
        await route.fulfill({ status: 429, contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank', title: 'Too many attempts', status: 429, code: 'AUTH_RATE_LIMITED' }) })
      } else await route.continue()
    })
    await page.route(`**${base}/products`, async route => {
      if (route.request().method() === 'POST') productRequests.push(route.request().postDataJSON() as object)
      await route.continue()
    })
    await submitCapturedWhenAdmitted(page, productForm, 'Save product', page.getByRole('heading', { name: productName, exact: true }))
    expect(rejectedCsrf).toBe(true)
    expect(productRequests).toHaveLength(1)
    await page.unroute('**/api/v1/auth/csrf')
    await page.unroute(`**${base}/products`)
    await expect(page.getByRole('heading', { name: productName, exact: true })).toBeVisible()
    const productPath = new URL(page.url()).pathname
    const productId = productPath.split('/').at(-1)!
    await page.getByRole('button', { name: 'Confirm product classification', exact: true }).click()
    const classificationForm = page.getByRole('form', { name: 'Confirm product safety attributes', exact: true })
    await classificationForm.getByLabel('Product category from the reviewed source').selectOption('BAKERY')
    await classificationForm.getByLabel('Declared temperature class from the reviewed source').selectOption('AMBIENT')
    await classificationForm.getByLabel('Product profile from the reviewed source').selectOption('PACKAGED_SHELF_STABLE_DRY_BAKERY')
    await classificationForm.getByLabel('Source type').selectOption('ORIGINAL_PACKAGE')
    await classificationForm.getByLabel('Source reference').fill('package:shelf-stable-storage-label')
    await classificationForm.getByLabel('Reason for this confirmation').fill('Reviewed the original package storage and product description')
    await classificationForm.getByLabel(/I inspected the cited source/).check()
    await submitCapturedWhenAdmitted(page, classificationForm, 'Save product evidence', page.getByText('The cited source was recorded against the current product version.', { exact: false }))
    const recordedAttributes = await (await owner.context.request.get(`${base}/products/${productId}/classification-evidence/operator-confirmations`)).json() as components['schemas']['OperatorClassificationConfirmationResponse']
    expect(recordedAttributes.status).toBe('CURRENT_OPERATOR_CONFIRMED')
    expect(recordedAttributes.confirmation).toMatchObject({ productVersion: 1, confirmedCategory: 'BAKERY', confirmedTemperatureClass: 'AMBIENT', productProfile: 'PACKAGED_SHELF_STABLE_DRY_BAKERY', evidenceReference: 'package:shelf-stable-storage-label' })
    await page.getByRole('button', { name: 'Register lot', exact: true }).click()
    await registerLotForm(page, `ORIGINAL-${suffix}`, futureDay, 'USE_BY', 'DAY', true)
    await expect(page.getByRole('heading', { name: `ORIGINAL-${suffix}`, exact: true })).toBeVisible()
    const mainLotPath = new URL(page.url()).pathname
    const lotId = mainLotPath.split('/').at(-1)!
    const originalMarking = page.getByRole('region', { name: 'Original date marking', exact: true })
    await expect(originalMarking.getByText(futureDay, { exact: true })).toBeVisible()
    await page.getByLabel('Store context', { exact: true }).selectOption(store.id)
    await expect(page.getByRole('heading', { name: `Store context · ${store.name}`, exact: true })).toBeVisible()
    await sourceForm(page, 'First independent supplier', 'shared-supplier-reference', 'document-one')
    const sourcesPath = `${base}/lots/${lotId}/source-evidence`
    const requests: object[] = []
    let dropped = false
    await page.route(`**${sourcesPath}`, async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      requests.push(route.request().postDataJSON() as object)
      if (!dropped) { dropped = true; const saved = await route.fetch(); expect(saved.status()).toBe(201); await route.abort('connectionreset') }
      else await route.continue()
    })
    const observation = page.getByRole('form', { name: 'Record source observation', exact: true })
    await fillSource(observation, 'Second independent supplier', 'shared-supplier-reference', 'document-two')
    await observation.getByRole('button', { name: 'Save source observation' }).click()
    await expect(observation.getByRole('button', { name: 'Retry same request' })).toBeVisible()
    await expect(page.getByLabel('Store context', { exact: true })).toBeDisabled()
    await observation.getByRole('button', { name: 'Retry same request' }).click()
    await expect(page.getByRole('heading', { name: 'Second independent supplier', exact: true })).toBeVisible()
    await page.unroute(`**${sourcesPath}`)
    expect(requests).toHaveLength(2); expect(requests[0]).toEqual(requests[1])
    const sources = await (await owner.context.request.get(sourcesPath)).json() as Sources
    expect(sources.items).toHaveLength(2); expect(new Set(sources.items.map(item => item.id)).size).toBe(2)
    expect(sources.items.every(item => item.supplierReference === 'shared-supplier-reference' && item.storeId === store.id)).toBe(true)

    await page.getByRole('button', { name: 'Create storage location', exact: true }).click()
    const locationForm = page.getByRole('form', { name: 'Create storage location', exact: true })
    await locationForm.getByLabel('Location code', { exact: true }).fill('A-01')
    await locationForm.getByLabel('Location name', { exact: true }).fill('Picking shelf')
    await locationForm.getByLabel('Location zone', { exact: true }).selectOption(zone.id)
    await submitCapturedWhenAdmitted(page, locationForm, 'Save storage location', page.getByRole('heading', { name: 'A-01 · Picking shelf', exact: true }))
    await expect(page.getByRole('heading', { name: 'A-01 · Picking shelf', exact: true })).toBeVisible()
    const locationsPath = `${base}/stores/${store.id}/storage-locations`
    const locations = await (await owner.context.request.get(locationsPath)).json() as components['schemas']['StorageLocationPage']
    const location = locations.items[0]
    await page.getByLabel('Storage evidence context').selectOption('LOCATION')
    await page.getByLabel('Assessment location', { exact: true }).selectOption(location.id)
    await page.getByRole('button', { name: 'Assess current evidence' }).click()
    const result = page.getByRole('region', { name: 'Assessment result', exact: true })
    await expect(result.getByRole('heading', { name: 'Requires review', exact: true })).toBeVisible()
    await expect(result).toContainText('Recall status is unknown.')
    await expect(result).toContainText('Damage status is unknown.')

    await page.getByRole('button', { name: 'Record safety notice', exact: true }).click()
    const initialSafety = page.getByRole('form', { name: 'Record safety status evidence', exact: true })
    await initialSafety.getByLabel('Recall evidence', { exact: true }).selectOption('NOT_REPORTED')
    await initialSafety.getByLabel('Damage evidence', { exact: true }).selectOption('NOT_REPORTED')
    await initialSafety.getByLabel('Safety notice source', { exact: true }).fill('Supplier safety statement')
    await initialSafety.getByLabel('Safety notice reference', { exact: true }).fill('supplier-safety-statement')
    await initialSafety.getByLabel('Reason for safety evidence').fill('Reviewed the supplier statement for this lot')
    await initialSafety.getByLabel('I reviewed the notice and confirm this safety evidence. Recall and damage blocks remain recorded.').check()
    await submitCapturedWhenAdmitted(page, initialSafety, 'Save safety status evidence', page.getByRole('region', { name: 'Lot evidence history' }).getByText(/Safety status evidence/))
    await page.getByRole('button', { name: 'Assess current evidence' }).click()
    await expect(result.getByRole('heading', { name: 'Passed limited checks', exact: true })).toBeVisible()
    await expect(result).toContainText('does not authorize sale, donation, transfer or stock movement')
    const eligible = await (await owner.context.request.get(`${base}/lots/${lotId}/eligibility?storeId=${store.id}&locationId=${location.id}&operation=SALE`)).json() as components['schemas']['EligibilityResponse']
    expect(eligible.decision).toBe('ALLOWED')
    expect(eligible.reasonCodes).toEqual([])
    expect(eligible.rulesetId).toBe('food-safety-bakery-ambient-dry-packaged-v2')
    expect(eligible.assessmentNotice).toBe('Passed the limited StockRescue checks on the available evidence at this time. This point-in-time assessment does not authorize sale, donation, transfer or stock movement.')
    expect(eligible.evidence.classification).toBe('OPERATOR_CONFIRMED')

    await page.goto(productPath)
    await page.getByRole('button', { name: 'Confirm product classification', exact: true }).click()
    const temperatureCorrection = page.getByRole('form', { name: 'Confirm product safety attributes', exact: true })
    await temperatureCorrection.getByLabel('Product category from the reviewed source').selectOption('BAKERY')
    await temperatureCorrection.getByLabel('Declared temperature class from the reviewed source').selectOption('CHILLED')
    await temperatureCorrection.getByLabel('Product profile from the reviewed source').selectOption('PACKAGED_SHELF_STABLE_DRY_BAKERY')
    await temperatureCorrection.getByLabel('Source type').selectOption('ORIGINAL_PACKAGE')
    await temperatureCorrection.getByLabel('Source reference').fill('package:corrected-chilled-storage-label')
    await temperatureCorrection.getByLabel('Reason for this confirmation').fill('Corrected the declared storage requirement from the original package')
    await temperatureCorrection.getByLabel(/I inspected the cited source/).check()
    await submitCapturedWhenAdmitted(page, temperatureCorrection, 'Save product evidence', page.getByText('package:corrected-chilled-storage-label', { exact: true }))
    const correctedProductEvidence = await (await owner.context.request.get(`${base}/products/${productId}/classification-evidence/operator-confirmations`)).json() as components['schemas']['OperatorClassificationConfirmationResponse']
    expect(correctedProductEvidence.product.version).toBe(2)
    expect(correctedProductEvidence.confirmation).toMatchObject({ originalTemperatureClass: 'AMBIENT', confirmedTemperatureClass: 'CHILLED', productVersion: 2 })

    await page.goto(mainLotPath)
    await page.getByLabel('Store context', { exact: true }).selectOption(store.id)
    await page.getByLabel('Storage evidence context').selectOption('LOCATION')
    await page.getByLabel('Assessment location', { exact: true }).selectOption(location.id)
    await page.getByRole('button', { name: 'Assess current evidence' }).click()
    await expect(result.getByRole('heading', { name: 'Denied', exact: true })).toBeVisible()
    await expect(result.getByRole('listitem').filter({ hasText: 'The product and storage zone declare different temperature classes.' })).toBeVisible()
    const noLongerEligible = await (await owner.context.request.get(`${base}/lots/${lotId}/eligibility?storeId=${store.id}&locationId=${location.id}&operation=SALE`)).json() as components['schemas']['EligibilityResponse']
    expect(noLongerEligible.decision).toBe('DENIED')
    expect(noLongerEligible.reasonCodes).toContain('TEMPERATURE_CLASS_MISMATCH')
    expect(noLongerEligible.productVersion).toBe(2)

    await page.getByRole('button', { name: 'Review metadata correction' }).click()
    const correction = page.getByRole('form', { name: 'Review lot correction', exact: true })
    const partialLabel = ` ${futureDay.slice(0, 7)} `
    await correction.getByLabel('Original date label', { exact: true }).fill(partialLabel)
    await correction.getByLabel('Date marking type').selectOption('BEST_BEFORE_END')
    await correction.getByLabel('Date precision').selectOption('MONTH')
    await correction.getByLabel('Date label reference', { exact: true }).fill('replacement-photo')
    await correction.getByLabel('Reason for lot correction').fill('Reviewed replacement label with month precision')
    await correction.getByLabel('I checked the manufacturer identity against this source. This does not approve food safety.').check()
    await correction.getByLabel('I reviewed the replacement evidence and confirm this lot correction.').check()
    await correction.getByRole('button', { name: 'Save reviewed correction' }).click()
    await expect(correction).toHaveCount(0)
    await expect(originalMarking.getByText('MONTH', { exact: true })).toBeVisible()
    expect(await originalMarking.getByText(futureDay.slice(0, 7), { exact: true }).textContent()).toBe(partialLabel)
    await expect(originalMarking.getByText('No verified normalization')).toBeVisible()
    await expect(result).toContainText('no day has been inferred')
    await page.getByText('Version 2 · Metadata correction', { exact: true }).click()
    await expect(page.getByRole('region', { name: 'Lot evidence history' })).toContainText('replacement-photo')

    await page.getByRole('button', { name: 'Record safety notice', exact: true }).click()
    const notice = page.getByRole('form', { name: 'Record safety status evidence', exact: true })
    await notice.getByLabel('Recall evidence', { exact: true }).selectOption('RECALLED')
    await notice.getByLabel('Damage evidence', { exact: true }).selectOption('NOT_REPORTED')
    await notice.getByLabel('Safety notice source', { exact: true }).fill('Manufacturer recall notice')
    await notice.getByLabel('Safety notice reference', { exact: true }).fill('recall-original-notice')
    await notice.getByLabel('Reason for safety evidence').fill('Reviewed manufacturer recall communication')
    await notice.getByLabel('I reviewed the notice and confirm this safety evidence. Recall and damage blocks remain recorded.').check()
    await notice.getByRole('button', { name: 'Save safety status evidence' }).click()
    await expect(notice).toHaveCount(0)
    await expect(result.getByRole('heading', { name: 'Denied', exact: true })).toBeVisible()
    await expect(result.getByRole('listitem').first()).toHaveText('A recall is recorded for this lot.')
    const archivePath = `${base}/lots/${lotId}/archive`
    const archiveRequests: object[] = []
    let lostArchiveAcknowledgement = false
    await page.route(`**${archivePath}`, async route => {
      archiveRequests.push(route.request().postDataJSON() as object)
      if (!lostArchiveAcknowledgement) {
        lostArchiveAcknowledgement = true
        const response = await route.fetch()
        expect(response.status()).toBe(201)
        await route.abort('connectionreset')
      } else await route.continue()
    })
    await page.getByRole('button', { name: 'Archive manufacturer lot', exact: true }).click()
    const lotArchive = page.getByRole('form', { name: 'Confirm manufacturer lot archival', exact: true })
    await lotArchive.getByLabel('Reason for manufacturer lot archival').fill('Supplier discontinued this lot identity after review')
    await lotArchive.getByLabel('I reviewed the impact and confirm archival of this manufacturer lot identity.').check()
    await lotArchive.getByRole('button', { name: 'Confirm lot archival' }).click()
    await expect(lotArchive.getByRole('button', { name: 'Retry same request' })).toBeVisible()
    await expect(lotArchive.getByLabel('Reason for manufacturer lot archival')).toBeDisabled()
    await lotArchive.getByRole('button', { name: 'Retry same request' }).click()
    await expect(page.getByRole('status', { name: 'Archived manufacturer lot' })).toContainText('Its identity and evidence history remain available')
    await page.unroute(`**${archivePath}`)
    expect(archiveRequests).toHaveLength(2)
    expect(archiveRequests[0]).toEqual(archiveRequests[1])
    const archivedLot = await (await owner.context.request.get(`${base}/lots/${lotId}`)).json() as Lot
    expect(archivedLot.active).toBe(false)
    expect(archivedLot.version).toBe(4)
    expect(archivedLot.recallState).toBe('RECALLED')
    expect(archivedLot).not.toHaveProperty('quantity')
    expect(archivedLot).not.toHaveProperty('receivedQuantity')
    expect(await (await owner.context.request.get(sourcesPath)).json()).toEqual(sources)
    const archivedHistory = await (await owner.context.request.get(`${base}/lots/${lotId}/corrections`)).json() as components['schemas']['LotCorrectionPage']
    expect(archivedHistory.items).toHaveLength(3)
    const rejectedSource = await write<components['schemas']['ApiProblem']>(owner.context, sourcesPath,
      { requestId: randomUUID(), storeId: store.id, provenance: { source: 'Supplier statement', reference: 'after-archive' } }, 409)
    expect(rejectedSource.code).toBe('CATALOG_INACTIVE')
    const rejectedSafety = await write<components['schemas']['ApiProblem']>(owner.context, `${base}/lots/${lotId}/safety-status-evidence`,
      { requestId: randomUUID(), expectedVersion: 4, reason: 'Attempt to replace retained recall evidence', recallState: 'UNKNOWN', damageState: 'UNKNOWN', provenance: { source: 'Replacement notice', reference: 'after-archive' } }, 409)
    expect(rejectedSafety.code).toBe('CATALOG_INACTIVE')
    await expect(page.getByRole('button', { name: 'Review metadata correction' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Record safety notice' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Archive manufacturer lot' })).toHaveCount(0)
    await expect(page.getByRole('form', { name: 'Record source observation' })).toHaveCount(0)
    await expect(page.getByText('Choose an assigned store to record source evidence and assess its storage context.')).toHaveCount(0)
    await expect(page.getByLabel(/quantity|received stock/i)).toHaveCount(0)
    await expect(result.getByRole('listitem').filter({ hasText: 'The lot is archived.' })).toHaveCount(1)
    await expect(page.getByRole('region', { name: 'Lot evidence history' })).toContainText('recall-original-notice')
    await accessibility(page)
    await result.screenshot({ path: '../.tools/screenshots/catalog-assessment-mobile.png' })

    await page.goto(productPath)
    await page.getByRole('button', { name: 'Register lot', exact: true }).click()
    await registerLotForm(page, `EXPIRED-${suffix}`, '2020-01-01', 'USE_BY', 'DAY', false)
    await expect(page.getByRole('heading', { name: `EXPIRED-${suffix}`, exact: true })).toBeVisible()
    await page.getByLabel('Store context', { exact: true }).selectOption(store.id)
    await page.getByRole('button', { name: 'Assess current evidence' }).click()
    await expect(page.getByRole('region', { name: 'Assessment result' })).toContainText('The use-by label day has passed')
    await page.goto(productPath)
    await page.getByRole('button', { name: 'Archive product', exact: true }).click()
    const archive = page.getByRole('form', { name: 'Confirm product archival', exact: true })
    await expect(page.getByRole('button', { name: 'Archive product', exact: true })).toBeDisabled()
    await expect(page.getByRole('button', { name: 'Register lot', exact: true })).toBeDisabled()
    await archive.getByRole('button', { name: 'Cancel editing', exact: true }).click()
    await expect(archive).toHaveCount(0)
    await page.getByRole('button', { name: 'Archive product', exact: true }).click()
    await archive.getByLabel('Reason for product archival').fill('Product discontinued after evidence review')
    await archive.getByLabel('I reviewed this product and confirm archival.').check()
    await archive.getByRole('button', { name: 'Confirm product archival', exact: true }).click()
    await expect(archive).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Register lot', exact: true })).toHaveCount(0)
    await page.getByRole('navigation', { name: 'Workspace navigation' }).getByRole('link', { name: 'Catalog', exact: true }).click()
    await page.getByLabel('Product status').selectOption('ARCHIVED')
    await page.getByLabel('Search SKU, name or GTIN').fill('036000291452')
    await page.getByRole('button', { name: 'Search catalog' }).click()
    await expect(page.getByRole('link', { name: productName, exact: true })).toBeVisible()
    await accessibility(page)
    await page.screenshot({ path: '../.tools/screenshots/catalog-mobile.png', fullPage: true })
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.screenshot({ path: '../.tools/screenshots/catalog-desktop.png', fullPage: true })
    const beforeLot = await (await owner.context.request.get(`${base}/lots/${lotId}`)).json() as Lot
    const beforeProduct = await (await owner.context.request.get(`${base}/products/${productId}`)).json() as Product
    await restartIsolated(owner.context, `${base}/lots/${lotId}`)
    expect(await (await owner.context.request.get(`${base}/lots/${lotId}`)).json()).toEqual(beforeLot)
    expect(await (await owner.context.request.get(`${base}/products/${productId}`)).json()).toEqual(beforeProduct)
    expect(await (await owner.context.request.get(sourcesPath)).json()).toEqual(sources)
    expect(await (await owner.context.request.get(`${locationsPath}/${location.id}`)).json()).toEqual(location)
    await page.goto(mainLotPath)
    await expect(page.getByRole('heading', { name: `ORIGINAL-${suffix}`, exact: true })).toBeVisible()
    await expect(page.getByRole('region', { name: 'Original date marking' })).toContainText('MONTH')
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
  } finally { await owner.context.close() }
})

test('owner reviews exact duplicate GTIN matches before product creation', async ({ browser }) => {
  test.setTimeout(60000)
  const owner = await account(browser)
  const organization = await write<{ id: string }>(owner.context, '/api/v1/organizations', { requestId: randomUUID(), name: `GTIN review ${randomUUID().slice(0, 8)}` }, 201)
  const base = `/api/v1/organizations/${organization.id}`
  const page = await owner.context.newPage()
  const requests: { requestId: string, gtinReviewAcknowledged?: boolean, gtinReviewRevision?: string }[] = []
  let loseNextAcknowledgement = false
  await page.route(`**${base}/products`, async route => {
    if (route.request().method() !== 'POST') { await route.continue(); return }
    requests.push(route.request().postDataJSON() as { requestId: string, gtinReviewAcknowledged?: boolean, gtinReviewRevision?: string })
    if (loseNextAcknowledgement) {
      loseNextAcknowledgement = false
      const response = await route.fetch()
      expect(response.status()).toBe(201)
      await route.abort('connectionreset')
      return
    }
    await route.continue()
  })
  let secondTab: Page | null = null
  try {
    await page.goto(`/organizations/${organization.id}/catalog`)
    await page.getByRole('button', { name: 'Create product', exact: true }).click()
    const first = page.getByRole('form', { name: 'Create product', exact: true })
    await first.getByLabel('SKU', { exact: true }).fill('GTIN-FIRST')
    await first.getByLabel('Product name', { exact: true }).fill('First catalog product')
    await first.getByLabel('GTIN (optional)').fill('036000291452')
    await first.getByLabel('Declared temperature class').selectOption('AMBIENT')
    await expect(first.getByText('No active or archived catalog products currently use this GTIN.')).toBeVisible()
    await submitCapturedWhenAdmitted(page, first, 'Save product', page.getByRole('heading', { name: 'First catalog product', exact: true }))

    let rejectGtinReview = true
    await page.route(`**${base}/products/gtin-review**`, async route => {
      if (!rejectGtinReview) { await route.continue(); return }
      await route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank', title: 'Catalog unavailable', status: 503, code: 'SERVICE_UNAVAILABLE' }) })
    })
    await page.goto(`/organizations/${organization.id}/catalog`)
    await page.getByRole('button', { name: 'Create product', exact: true }).click()
    const second = page.getByRole('form', { name: 'Create product', exact: true })
    await second.getByLabel('SKU', { exact: true }).fill('GTIN-SECOND')
    await second.getByLabel('Product name', { exact: true }).fill('Second catalog product')
    await second.getByLabel('GTIN (optional)').fill('036000291452')
    await second.getByLabel('Declared temperature class').selectOption('AMBIENT')
    const warning = second.getByRole('alert', { name: 'Potential GTIN matches', exact: true })
    const retryReview = second.getByRole('button', { name: 'Retry GTIN check', exact: true })
    await expect(retryReview).toBeVisible({ timeout: 20000 })
    await expect(second.getByRole('button', { name: 'Save product', exact: true })).toBeDisabled()
    rejectGtinReview = false
    await retryReview.click()
    await expect(warning.getByText('GTIN-FIRST', { exact: true })).toBeVisible()
    await expect(warning.getByText('First catalog product', { exact: true })).toBeVisible()
    await expect(warning).toContainText('Active product')
    await expect(warning).toContainText('GTIN: 036000291452')
    await expect(second.getByRole('button', { name: 'Save product', exact: true })).toBeDisabled()
    await page.unroute(`**${base}/products/gtin-review**`)

    const uniqueGtin = '000000000000'
    await second.getByLabel('GTIN (optional)').fill(uniqueGtin)
    await expect(warning).toHaveCount(0)
    await expect(second.getByText('No active or archived catalog products currently use this GTIN.')).toBeVisible()
    await second.getByLabel('GTIN (optional)').fill('036000291452')
    await expect(warning.getByText('GTIN-FIRST', { exact: true })).toBeVisible()
    const acknowledge = second.getByLabel('I reviewed the current matches and still want to create a separate product with this GTIN.', { exact: true })
    await expect(acknowledge).not.toBeChecked()

    // A second tab changes the complete match set after this form's review snapshot.
    secondTab = await owner.context.newPage()
    await secondTab.goto(`/organizations/${organization.id}/catalog`)
    await secondTab.getByRole('button', { name: 'Create product', exact: true }).click()
    const concurrent = secondTab.getByRole('form', { name: 'Create product', exact: true })
    await concurrent.getByLabel('SKU', { exact: true }).fill('GTIN-THIRD')
    await concurrent.getByLabel('Product name', { exact: true }).fill('Third catalog product')
    await concurrent.getByLabel('GTIN (optional)').fill('036000291452')
    await concurrent.getByLabel('Declared temperature class').selectOption('AMBIENT')
    const concurrentWarning = concurrent.getByRole('alert', { name: 'Potential GTIN matches', exact: true })
    await expect(concurrentWarning.getByText('GTIN-FIRST', { exact: true })).toBeVisible()
    const concurrentConsent = concurrent.getByLabel('I reviewed the current matches and still want to create a separate product with this GTIN.', { exact: true })
    await concurrentConsent.focus()
    await secondTab.keyboard.press('Space')
    await expect(concurrentConsent).toBeChecked()
    await submitCapturedWhenAdmitted(secondTab, concurrent, 'Save product', secondTab.getByRole('heading', { name: 'Third catalog product', exact: true }))

    await acknowledge.check()
    await second.getByRole('button', { name: 'Save product', exact: true }).click()
    await expect(second.getByText('Review the current GTIN matches; submit again to create a new command with the fresh review.', { exact: false })).toBeVisible()
    await expect(warning.getByText('GTIN-THIRD', { exact: true })).toBeVisible()
    await expect(acknowledge).not.toBeChecked()
    await expect(second.getByRole('button', { name: 'Save product', exact: true })).toBeDisabled()

    await acknowledge.check()
    loseNextAcknowledgement = true
    await second.getByRole('button', { name: 'Save product', exact: true }).click()
    await expect(second.getByRole('button', { name: 'Retry same request' })).toBeVisible()
    await second.getByRole('button', { name: 'Retry same request' }).click()
    await expect(page.getByRole('heading', { name: 'Second catalog product', exact: true })).toBeVisible()

    expect(requests).toHaveLength(4)
    expect(requests[0].gtinReviewAcknowledged).toBeUndefined()
    expect(requests[1].gtinReviewAcknowledged).toBe(true)
    expect(requests[1].gtinReviewRevision).toMatch(/^[0-9a-f]{64}$/)
    expect(requests[2].gtinReviewAcknowledged).toBe(true)
    expect(requests[2].gtinReviewRevision).not.toBe(requests[1].gtinReviewRevision)
    expect(requests[2].requestId).not.toBe(requests[1].requestId)
    expect(requests[3]).toEqual(requests[2])
  } finally { await secondTab?.close(); await owner.context.close() }
})

test('catalog respects assigned source visibility, revocation, foreign links and organization switching', async ({ browser }) => {
  test.setTimeout(90000)
  const owner = await account(browser)
  const staff = await account(browser)
  try {
    const a = await write<{ id: string }>(owner.context, '/api/v1/organizations', { requestId: randomUUID(), name: 'Source cooperative A' }, 201)
    const b = await write<{ id: string }>(staff.context, '/api/v1/organizations', { requestId: randomUUID(), name: 'Source cooperative B' }, 201)
    const base = `/api/v1/organizations/${a.id}`
    const north = await write<Store>(owner.context, `${base}/stores`, { requestId: randomUUID(), code: 'NORTH', name: 'Private north store', zoneId: 'UTC' }, 201)
    const south = await write<Store>(owner.context, `${base}/stores`, { requestId: randomUUID(), code: 'SOUTH', name: 'Private south store', zoneId: 'UTC' }, 201)
    const product = await write<Product>(owner.context, `${base}/products`, { requestId: randomUUID(), sku: 'PRIVATE-A', name: 'Private A product', baseUnit: 'EA', category: 'UNCLASSIFIED', temperatureClass: 'AMBIENT' }, 201)
    const lot = await write<Lot>(owner.context, `${base}/lots`, { requestId: randomUUID(), productId: product.id, lotCode: 'PRIVATE-A-LOT', identityState: 'CANDIDATE', manufacturerVerified: false,
      manufacturerProvenance: { source: 'Supplier claim' }, dateMarking: { type: 'UNKNOWN', precision: 'UNKNOWN' } }, 201)
    const sourcePath = `${base}/lots/${lot.id}/source-evidence`
    await write(owner.context, sourcePath, { requestId: randomUUID(), storeId: north.id, supplierName: 'Visible north supplier', provenance: { source: 'North document', reference: 'NORTH-PRIVATE-REF' } }, 201)
    await write(owner.context, sourcePath, { requestId: randomUUID(), storeId: south.id, supplierName: 'Hidden south supplier', provenance: { source: 'South document', reference: 'SOUTH-PRIVATE-REF' } }, 201)
    await write(staff.context, `/api/v1/organizations/${b.id}/products`, { requestId: randomUUID(), sku: 'PRIVATE-B', name: 'Private B product', baseUnit: 'EA', category: 'UNCLASSIFIED', temperatureClass: 'AMBIENT' }, 201)
    const page = await staff.context.newPage()
    const lotPath = `/organizations/${a.id}/catalog/lots/${lot.id}`
    await page.goto(lotPath)
    await expect(page.getByRole('heading', { name: 'Organization unavailable', exact: true })).toBeVisible()
    await expect(page.getByText('PRIVATE-A-LOT', { exact: true })).toHaveCount(0)
    expect((await staff.context.request.get(sourcePath)).status()).toBe(404)
    const invite = await write<{ token: string }>(owner.context, `${base}/invitations`, { email: staff.email, reason: 'Reviewed catalog staff assignment', storeGrants: [{ storeId: north.id, role: 'INVENTORY_OPERATOR' }] }, 201)
    await write(staff.context, '/api/v1/invitations/accept', { token: invite.token }, 202)
    const members = await (await owner.context.request.get(`${base}/members`)).json() as { items: Member[] }
    const pending = members.items.find(member => member.userId === staff.id)!
    await write(owner.context, `${base}/members/${pending.id}/approve`, { version: pending.version, verifiedUserId: staff.id, reason: 'Verified intended account against independent contact' }, 200)
    await page.goto(lotPath)
    await expect(page.getByRole('heading', { name: 'PRIVATE-A-LOT', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Visible north supplier', exact: true })).toBeVisible()
    await expect(page.getByText('SOUTH-PRIVATE-REF', { exact: false })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Review metadata correction' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Record policy review', exact: true })).toHaveCount(0)
    await page.getByLabel('Store context', { exact: true }).selectOption(north.id)
    await expect(page.getByLabel('Store context', { exact: true }).getByRole('option', { name: /Private south store/ })).toHaveCount(0)
    await sourceForm(page, 'Operator source observation', 'operator-reference', 'operator-document')
    const currentMembers = await (await owner.context.request.get(`${base}/members`)).json() as { items: Member[] }
    const active = currentMembers.items.find(member => member.userId === staff.id)!
    const grant = active.grants.find(item => item.storeId === north.id)!
    await write(owner.context, `${base}/members/${active.id}/store-grants/${north.id}`, { memberVersion: active.version, grantVersion: grant.version, reason: 'Store assignment ended' }, 200, 'DELETE')
    await page.getByRole('button', { name: 'Reload lot evidence' }).click()
    await expect(page.getByRole('heading', { name: 'Visible north supplier', exact: true })).toHaveCount(0)
    await expect(page.getByRole('form', { name: 'Record source observation' })).toHaveCount(0)
    await page.getByLabel('Organization', { exact: true }).selectOption(b.id)
    await page.getByRole('navigation', { name: 'Workspace navigation' }).getByRole('link', { name: 'Catalog', exact: true }).click()
    await expect(page.getByRole('link', { name: 'Private B product', exact: true })).toBeVisible()
    await expect(page.getByText('Private A product', { exact: true })).toHaveCount(0)
    await expect(page.getByText('NORTH-PRIVATE-REF', { exact: false })).toHaveCount(0)
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
  } finally { await owner.context.close(); await staff.context.close() }
})

test('assigned store manager registers unverified lot metadata without recording received stock', async ({ browser }) => {
  test.setTimeout(60000)
  const owner = await account(browser)
  const manager = await account(browser)
  const page = await manager.context.newPage()
  const suffix = randomUUID().slice(0, 8).toUpperCase()
  const originalDate = '2027-08-19'
  try {
    const organization = await write<{ id: string }>(owner.context, '/api/v1/organizations', { requestId: randomUUID(), name: `Lot intake cooperative ${suffix}` }, 201)
    const base = `/api/v1/organizations/${organization.id}`
    const store = await write<Store>(owner.context, `${base}/stores`, { requestId: randomUUID(), code: 'NORTH', name: 'North receiving store', zoneId: 'Europe/Warsaw' }, 201)
    const product = await write<Product>(owner.context, `${base}/products`, { requestId: randomUUID(), sku: `INTAKE-${suffix}`, name: `Intake product ${suffix}`, baseUnit: 'EA', category: 'UNCLASSIFIED', temperatureClass: 'AMBIENT' }, 201)
    const invitation = await write<{ token: string }>(owner.context, `${base}/invitations`, {
      email: manager.email, reason: 'Assigned store lot intake', storeGrants: [{ storeId: store.id, role: 'STORE_MANAGER' }],
    }, 201)
    await write(manager.context, '/api/v1/invitations/accept', { token: invitation.token }, 202)
    const members = await (await owner.context.request.get(`${base}/members`)).json() as { items: Member[] }
    const pending = members.items.find(member => member.userId === manager.id)!
    await write(owner.context, `${base}/members/${pending.id}/approve`, { version: pending.version, verifiedUserId: manager.id, reason: 'Verified intended store manager account' }, 200)
    const unauthorizedConfirmation = await write<components['schemas']['ApiProblem']>(manager.context,
      `${base}/products/${product.id}/classification-evidence/operator-confirmations`, {
        requestId: randomUUID(), expectedProductVersion: 0, category: 'BAKERY', temperatureClass: 'AMBIENT',
        productProfile: 'PACKAGED_SHELF_STABLE_DRY_BAKERY', sourceType: 'ORIGINAL_PACKAGE',
        evidenceReference: 'package:manager-not-authorized', reason: 'Store Manager cannot correct organization-wide product attributes',
      }, 403)
    expect(unauthorizedConfirmation.code).toBe('ACCESS_DENIED')

    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`/organizations/${organization.id}/catalog/products/${product.id}`)
    await page.getByRole('button', { name: 'Register candidate lot', exact: true }).click()
    await page.getByLabel('Store context', { exact: true }).selectOption(store.id)
    const form = page.getByRole('form', { name: 'Register candidate lot', exact: true })
    await expect(form.getByText(/Manufacturer identity UNVERIFIED/)).toBeVisible()
    await expect(form.getByText(/does not record goods received or create stock quantity/)).toBeVisible()
    await expect(form.getByLabel('Manufacturer identity status')).toHaveCount(0)
    await expect(form.getByLabel('Manufacturer identity reference')).toHaveCount(0)
    await form.getByLabel('Manufacturer lot code', { exact: true }).fill(`CANDIDATE-${suffix}`)
    await form.getByLabel('Manufacturer evidence source', { exact: true }).fill('Supplier carton label')
    await form.getByLabel('Manufacturer evidence reference', { exact: true }).fill('carton-photo-1')
    await form.getByLabel('Original date label', { exact: true }).fill(originalDate)
    await form.getByLabel('Date marking type').selectOption('USE_BY')
    await form.getByLabel('Date precision').selectOption('DAY')
    await form.getByLabel('Date label source', { exact: true }).fill('Carton photograph')
    await form.getByLabel('Date label reference', { exact: true }).fill('carton-photo-1')
    await form.getByLabel('I recorded this manufacturer identity as unverified metadata, not received stock.').check()
    const registrations: Record<string, unknown>[] = []
    await page.route(`**${base}/lots`, async route => {
      if (route.request().method() === 'POST') registrations.push(route.request().postDataJSON() as Record<string, unknown>)
      await route.continue()
    })
    await form.getByRole('button', { name: 'Save candidate lot metadata' }).click()
    await expect(page.getByRole('heading', { name: `CANDIDATE-${suffix}`, exact: true })).toBeVisible()
    expect(registrations).toHaveLength(1)
    expect(registrations[0]).toMatchObject({ storeId: store.id, identityState: 'CANDIDATE', manufacturerVerified: false, lotCode: `CANDIDATE-${suffix}` })
    expect(registrations[0].manufacturerIdentityKey).toBeUndefined()
    await expect(page.getByRole('region', { name: 'Original date marking', exact: true }).getByText(originalDate, { exact: true })).toBeVisible()
    await expect(page.getByLabel(/quantity|received stock/i)).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  } finally { await page.close(); await owner.context.close(); await manager.context.close() }
})

async function registerLotForm(page: Page, code: string, label: string, type: string, precision: string, confirmed: boolean) {
  const form = page.getByRole('form', { name: 'Register manufacturer lot', exact: true })
  await form.getByLabel('Manufacturer lot code', { exact: true }).fill(code)
  await form.getByLabel('Manufacturer evidence source', { exact: true }).fill('Manufacturer original document')
  await form.getByLabel('Manufacturer evidence reference', { exact: true }).fill('manufacturer-document')
  if (confirmed) {
    await form.getByLabel('Manufacturer identity status').selectOption('CONFIRMED')
    await form.getByLabel('Manufacturer identity reference', { exact: true }).fill('registry:catalog-maker')
    await form.getByLabel('I checked the manufacturer identity against this source. This does not approve food safety.').check()
  }
  await form.getByLabel('Original date label', { exact: true }).fill(label)
  await form.getByLabel('Date marking type').selectOption(type)
  await form.getByLabel('Date precision').selectOption(precision)
  await form.getByLabel('Date label source', { exact: true }).fill('Manufacturer label photograph')
  await form.getByLabel('Date label reference', { exact: true }).fill('original-photo')
  await submitCapturedWhenAdmitted(page, form, 'Save manufacturer lot', page.getByRole('heading', { name: code, exact: true }))
}
async function fillSource(form: ReturnType<Page['getByRole']>, supplier: string, supplierReference: string, reference: string) {
  await expect(form.getByLabel('Observation source', { exact: true })).toBeEditable()
  await form.getByLabel('Supplier name (optional)').fill(supplier)
  await form.getByLabel('Supplier reference (optional)').fill(supplierReference)
  await form.getByLabel('Observation source', { exact: true }).fill('Supplier original document')
  await form.getByLabel('Observation document reference').fill(reference)
}
async function sourceForm(page: Page, supplier: string, supplierReference: string, reference: string) {
  const form = page.getByRole('form', { name: 'Record source observation', exact: true })
  await fillSource(form, supplier, supplierReference, reference)
  await submitCapturedWhenAdmitted(page, form, 'Save source observation', page.getByRole('heading', { name: supplier, exact: true }))
  await expect(page.getByRole('heading', { name: supplier, exact: true })).toBeVisible()
  await expect(form.getByLabel('Observation source', { exact: true })).toHaveValue('')
}
async function accessibility(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('link', { name: 'Skip to content', exact: true }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('main')).toBeFocused()
}
async function account(browser: Browser) {
  const context = await browser.newContext()
  const email = `catalog-${randomUUID()}@example.test`
  const credentials = { email, password: 'real-catalog-owner-passphrase' }
  await write(context, '/api/v1/auth/sign-up', credentials, 202)
  const actor = await write<{ id: string }>(context, '/api/v1/auth/sign-in', credentials, 200)
  return { context, email, id: actor.id }
}
async function admitted<T>(operation: () => Promise<{ status(): number, json(): Promise<T> }>, expected: number): Promise<T> {
  let data: T | undefined
  await expect.poll(async () => { const response = await operation(); if (response.status() === 429) return false; expect(response.status()).toBe(expected); data = await response.json(); return true }, { timeout: 12000, intervals: [1100, 2100, 4100] }).toBe(true)
  return data!
}
async function write<T = unknown>(context: BrowserContext, path: string, data: unknown, expected: number, method = 'POST'): Promise<T> {
  const csrf = await admitted(() => context.request.get('/api/v1/auth/csrf'), 200) as { token: string }
  return admitted(() => context.request.fetch(path, { method, headers: { 'X-CSRF-TOKEN': csrf.token }, data }), expected)
}
async function restartIsolated(context: BrowserContext, probe: string) {
  const root = resolve(process.cwd(), '..')
  const project = process.env.STOCKRESCUE_TEST_PROJECT ?? ''
  const envFile = process.env.STOCKRESCUE_TEST_ENV_FILE ?? ''
  expect(/^stockrescue-check-[a-f0-9]{16}$/.test(project)).toBe(true)
  expect(dirname(resolve(envFile)) === resolve(root, '.tools') && basename(envFile) === `${project}.env`).toBe(true)
  const docker = promisify(execFile)
  const args = ['compose', '--env-file', envFile, '-p', project]
  await docker('docker', [...args, 'restart', 'postgres'], { cwd: root, timeout: 90000 })
  await docker('docker', [...args, 'up', '-d', '--wait', '--wait-timeout', '90', 'postgres'], { cwd: root, timeout: 100000 })
  await docker('docker', [...args, 'up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '90', 'backend'], { cwd: root, timeout: 100000 })
  await expect.poll(async () => { try { return (await context.request.get(probe, { timeout: 5000 })).status() } catch { return 0 } }, { timeout: 30000, intervals: [500, 1000, 2000] }).toBe(200)
}
