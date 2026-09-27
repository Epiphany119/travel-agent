import request from './index'

export interface CommerceOffer {
  offer_id: string
  provider_code: string
  offer_type: string
  title: string
  destination: string
  redirect_url: string
  price: number
  currency: string
  commission_rate?: number
}

export function listCommerceOffers(planId: string, destination: string) {
  return request.get<any, CommerceOffer[]>('/commerce/offers', { params: { planId, destination } })
}

export function recordCommerceClick(planId: string, offerId: string) {
  return request.post<any, Record<string, any>>('/commerce/clicks', { planId, offerId })
}

export function createCommerceOrder(planId: string, offerId: string) {
  const key = `order-${planId}-${offerId}-${Date.now()}`
  return request.post<any, Record<string, any>>('/commerce/orders', { planId, offerId }, { headers: { 'Idempotency-Key': key } })
}

export function payCommerceOrder(orderNo: string) {
  return request.post<any, Record<string, any>>(`/commerce/orders/${encodeURIComponent(orderNo)}/pay`)
}
