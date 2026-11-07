import type { components, operations } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type Product = components['schemas']['ProductResponse']
export type CreateProduct = components['schemas']['CreateProductRequest']
export type ArchiveProduct = components['schemas']['ArchiveProductRequest']
export type CorrectProductCategory = components['schemas']['CorrectProductCategoryRequest']
export type ProductCategory = Product['category']
export type ProductState = NonNullable<operations['listProducts']['parameters']['query']>['state']

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
