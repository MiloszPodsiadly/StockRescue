import type { components, operations } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type Product = components['schemas']['ProductResponse']
export type CreateProduct = components['schemas']['CreateProductRequest']
export type GtinReview = components['schemas']['GtinReviewResponse']
export type ArchiveProduct = components['schemas']['ArchiveProductRequest']
export type CorrectProductCategory = components['schemas']['CorrectProductCategoryRequest']
export type ProductCategory = Product['category']
export type ProductState = NonNullable<operations['listProducts']['parameters']['query']>['state']

export function isValidGtin(value: string) {
  if (!/^(?:[0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14})$/.test(value)) return false
  let sum = 0
  let weight = 3
  for (let index = value.length - 2; index >= 0; index--) {
    sum += Number(value[index]) * weight
    weight = weight === 3 ? 1 : 3
  }
  return (10 - sum % 10) % 10 === Number(value[value.length - 1])
}

export async function listProducts(organizationId: string, state: ProductState, q: string, offset: number, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/products', { params: { path: { organizationId }, query: { state, q, offset, limit: 25 } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function getProduct(organizationId: string, productId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/products/{productId}', { params: { path: { organizationId, productId } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function reviewProductGtin(organizationId: string, gtin: string, offset: number, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/products/gtin-review', {
    params: { path: { organizationId }, query: { gtin, offset, limit: 25 } }, signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function createProduct(organizationId: string, body: CreateProduct, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/products', { params: { path: { organizationId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function archiveProduct(organizationId: string, productId: string, body: ArchiveProduct, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/products/{productId}/archive', { params: { path: { organizationId, productId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function correctProductCategory(organizationId: string, productId: string, body: CorrectProductCategory, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/products/{productId}/category-corrections', { params: { path: { organizationId, productId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}
