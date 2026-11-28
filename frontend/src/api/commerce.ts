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

export interface BookingChecklistItem {
  bookingNo: string
  ownerId: string
  planId: string
  destination: string
  startDate: string
  endDate: string
  category: string
  offerId: string
  title: string
  subtitle: string
  providerCode: string
  amount: number
  currency: string
  status: 'PENDING' | 'CONFIRMED' | 'CANCELLED'
  attributionCode: string
  trackingUrl: string
  commissionRate: number
  estimatedCommission: number
  dataSource: string
  createdAt: string | null
  confirmedAt: string | null
}

export interface CommercialSummaryResponse {
  totalCount: number
  confirmedCount: number
  pendingCount: number
  cancelledCount: number
  confirmedGmv: number
  estimatedCommission: number
  currency: string
  dataSource: string
  bookings: BookingChecklistItem[]
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

// ─── 第三阶段：一键预订清单与 CPS 归因闭环 API ─────────────────────────────────
export function createBookingChecklist(payload: {
  destination: string
  planId?: string
  startDate?: string
  endDate?: string
  items: Array<{
    category: string
    offerId?: string
    title: string
    subtitle?: string
    providerCode?: string
    amount: number
  }>
}): Promise<BookingChecklistItem[]> {
  return request.post<any, BookingChecklistItem[]>('/commerce/bookings/batch', payload)
}

export function listBookingItems(destination?: string): Promise<BookingChecklistItem[]> {
  return request.get<any, BookingChecklistItem[]>('/commerce/bookings', {
    params: destination ? { destination } : {}
  })
}

export function confirmBookingItem(bookingNo: string): Promise<BookingChecklistItem> {
  return request.post<any, BookingChecklistItem>(`/commerce/bookings/${encodeURIComponent(bookingNo)}/confirm`)
}

export function cancelBookingItem(bookingNo: string): Promise<BookingChecklistItem> {
  return request.post<any, BookingChecklistItem>(`/commerce/bookings/${encodeURIComponent(bookingNo)}/cancel`)
}

export function getCommercialSummary(): Promise<CommercialSummaryResponse> {
  return request.get<any, CommercialSummaryResponse>('/commerce/summary')
}
