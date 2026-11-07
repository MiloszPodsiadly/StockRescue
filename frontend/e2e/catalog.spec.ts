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
    await productForm.getByLabel('I confirm this descriptive category; if the category is unknown, I will keep it unclassified.').check()
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
    await expect(result).toContainText('No policy review is recorded for this category and temperature scope.')

    await page.getByRole('button', { name: 'Record policy review', exact: true }).click()
    const policyForm = page.getByRole('form', { name: 'Record policy review evidence', exact: true })
    await policyForm.getByLabel('Requested partial-date rule').selectOption('END_OF_LABELED_PERIOD')
    await policyForm.getByLabel('Policy review source', { exact: true }).fill('Independent review dossier')
    await policyForm.getByLabel('Policy review document reference').fill('dossier-awaiting-verification')
    await policyForm.getByLabel('Reason for policy review').fill('Record the received class-specific review evidence')
    await policyForm.getByLabel('I confirm this records review evidence, not independent policy approval.').check()
    await submitCapturedWhenAdmitted(page, policyForm, 'Save policy review', page.getByRole('heading', { name: 'Policy version 1', exact: true }))
    await expect(page.getByRole('heading', { name: 'Policy version 1', exact: true })).toBeVisible()
    await expect(result).toContainText('Independent approval for this category and temperature scope has not been verified.')
    await expect(result.getByRole('heading', { name: 'Requires review' })).toBeVisible()

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
    await page.getByText('Version 1 · Metadata correction', { exact: true }).click()
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
