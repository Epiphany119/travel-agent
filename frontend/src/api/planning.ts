import request from './index'

export interface BudgetCategoryItem {
  code: string
  label: string
  amount: number
  ratio: number
  priceSource: string
  note: string
}

export interface BudgetRecommendResponse {
  destination: string
  departureCity: string
  cityTier: string
  tierLabel: string
  days: number
  travelers: number
  travelStyle: string
  seasonFactor: number
  minBudget: number
  recommendedBudget: number
  maxBudget: number
  perPersonBudget: number
  perDayBudget: number
  budgetHealth: 'AMPLE' | 'BALANCED' | 'TIGHT' | 'INSUFFICIENT'
  advice: string
  priceNote: string
  categories: BudgetCategoryItem[]
  dataSource: string
}

export interface DetailedBillResponse {
  destination: string
  userBudget: number
  totalEstimatedCost: number
  deltaFromBudget: number
  perPersonCost: number
  perDayCost: number
  utilizationRate: number
  status: 'UNDER_BUDGET' | 'ON_TRACK' | 'OVER_BUDGET'
  confirmedActivityCount: number
  pendingActivityCount: number
  categories: BudgetCategoryItem[]
  dataSource: string
}

export interface TransportSegment {
  fromName: string
  toName: string
  distanceKm: number
  durationMinutes: number
  recommendedMode: string
  estimatedFare: number
  tip: string
  dataSource: string
}

export interface DayTransportPlan {
  day: number
  totalDistanceKm: number
  totalDurationMinutes: number
  totalTransitCost: number
  routeWarning: string | null
  segments: TransportSegment[]
}

export interface TransportEstimateResponse {
  destination: string
  provider: string
  dataSource: string
  notice: string
  days: DayTransportPlan[]
}

export interface TransportOffer {
  offerId: string
  category: string
  title: string
  carrier: string
  scheduleSummary: string
  unitPrice: number
  totalPrice: number
  tag: string
  bookingStatus: string
  dataSource: string
}

export interface HotelOffer {
  offerId: string
  name: string
  area: string
  roomType: string
  nights: number
  rooms: number
  pricePerNight: number
  totalPrice: number
  rating: number
  cancelPolicy: string
  bookingStatus: string
  dataSource: string
}

export interface BookingPlaceholderResponse {
  departureCity: string
  destination: string
  transportOptions: TransportOffer[]
  hotelOptions: HotelOffer[]
  notice: string
  dataSource: string
}

export function recommendBudget(params: {
  destination: string
  departureCity?: string
  days: number
  travelers: number
  travelStyle?: string
  budget?: number
  travelMonth?: number
}): Promise<BudgetRecommendResponse> {
  return request.post<any, BudgetRecommendResponse>('/planning/budget/recommend', params)
}

export function calculateDetailedBill(params: {
  destination: string
  departureCity?: string
  days: number
  travelers: number
  travelStyle?: string
  userBudget: number
  activities: Array<{ type?: string; name?: string; cost?: number | null }>
}): Promise<DetailedBillResponse> {
  return request.post<any, DetailedBillResponse>('/planning/bill/calculate', params)
}

export function estimateTransport(params: {
  destination: string
  days: Array<{
    day: number
    stops: Array<{ name: string; location?: string; type?: string }>
  }>
}): Promise<TransportEstimateResponse> {
  return request.post<any, TransportEstimateResponse>('/planning/transport/estimate', params)
}

export function fetchBookingPlaceholders(params: {
  departureCity?: string
  destination: string
  days: number
  travelers: number
  travelStyle?: string
  budget: number
}): Promise<BookingPlaceholderResponse> {
  return request.post<any, BookingPlaceholderResponse>('/planning/booking/placeholders', params)
}
