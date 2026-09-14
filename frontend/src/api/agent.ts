import request from './index'

function sseHeaders(): HeadersInit {
  const token = localStorage.getItem('roamly_token')
  return {
    Accept: 'text/event-stream',
    'Cache-Control': 'no-cache',
    ...(token ? { Authorization: `Bearer ${token}` } : {})
  }
}

export interface CreateSessionRequest {
  destination: string
  startDate?: string
  endDate?: string
  budgetLevel?: string
  travelType?: string
  travelers?: number
}

export interface SendMessageRequest {
  sessionId: string
  content: string
}

export interface SessionResponse {
  sessionId: string
  title: string
  status: string
  createdAt: string
}

export interface MessageResponse {
  messageId: string
  role: string
  content: string
  needsToolCall?: boolean
}

export interface TravelPlanRequest {
  destination: string
  days: number
  budget: number
  travelers?: number
  travelStyle?: string
  interests?: string[]
}

export interface TravelPlan {
  planId: string; destination: string; days: number; totalBudget: number; estimatedCost: number | null
  budgetStatus: string; overview: string; travelTips: string[]; packingList: string[]
  dayPlans: Array<{ dayNumber: number; date: string; theme: string; dayBudget: number; transportation: string; notes: string
    attractions: Array<{ name: string; description: string; duration: number; ticketPrice: number | null }>
    meals: Array<{ mealType: string; restaurantName: string; cuisine: string; avgPrice: number | null; reason: string }>
  }>
}

export function createSession(data: CreateSessionRequest) {
  return request.post<any, { code: number; data: SessionResponse }>('/agent/sessions', data)
}

export function sendMessage(data: SendMessageRequest) {
  return request.post<any, { code: number; data: MessageResponse }>('/agent/messages', data)
}

export function getMessages(sessionId: string) {
  return request.get<any, { code: number; data: MessageResponse[] }>(`/agent/sessions/${sessionId}/messages`)
}

export function getUserSessions(userId: number = 1) {
  return request.get<any, { code: number; data: SessionResponse[] }>('/agent/sessions', { params: { userId } })
}

export function deleteSession(sessionId: string) {
  return request.delete(`/agent/sessions/${sessionId}`)
}

export function generateTravelPlan(data: TravelPlanRequest) {
  return request.post<any, { code: number; data: TravelPlan }>('/travel-plans/generate', data)
}

// ─── 地点/餐厅图片 ───────────────────────────────────────────────────────────
export interface PoiImageResponse {
  name: string
  city: string
  imageUrls: string[]
}

/**
 * 按地点/餐厅名称获取高德官方图片（后端做 搜索→id→详情照片）
 * 返回响应体（axios 拦截器已解包 response.data）
 */
export function fetchPoiImages(name: string, city?: string): Promise<PoiImageResponse> {
  return request.get<any, PoiImageResponse>('/poi/image', { params: { name, city } })
}

// ─── SSE 流式订阅 ────────────────────────────────────────────────────────────

export interface StreamTokenEvent {
  name: 'token'
  data: { delta: string; full?: string }
}

export interface StreamToolCallEvent {
  name: 'tool_call'
  data: { name: string; args: Record<string, unknown> }
}

export interface StreamToolResultEvent {
  name: 'tool_result'
  data: { name: string; ok: boolean; summary?: string; error?: string }
}

export interface StreamDayPlanEvent {
  name: 'dayplan'
  data: {
    dayNumber: number
    theme: string
    date: string
    morning?: { plan: string; duration: string; tips?: string; budget: number | null }
    afternoon?: { plan: string; duration: string; tips?: string; budget: number | null }
    evening?: { plan: string; duration: string; tips?: string; budget: number | null }
    tips?: string
  }
}

export interface StreamDoneEvent {
  name: 'done'
  data: { planId: string }
}

export interface StreamErrorEvent {
  name: 'error'
  data: { code: string; message: string }
}

export type StreamEvent =
  | StreamTokenEvent
  | StreamToolCallEvent
  | StreamToolResultEvent
  | StreamDayPlanEvent
  | StreamDoneEvent
  | StreamErrorEvent

/**
 * SSE 流式订阅行程规划
 * 使用 fetch + ReadableStream 解析 SSE
 * 返回取消函数，调用后终止请求
 */
export function subscribePlanStream(
  planId: string,
  onEvent: (event: StreamEvent) => void
): () => void {
  const controller = new AbortController()

  fetch(`/api/travel-plans/${planId}/stream`, {
    method: 'GET',
    headers: sseHeaders(),
    signal: controller.signal
  })
    .then(async (response) => {
      console.log('[Agent SSE] response status:', response.status, 'content-type:', response.headers.get('content-type'))
      if (!response.ok || !response.body) {
        throw new Error(`HTTP ${response.status}`)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let currentEventName = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const rawLine of lines) {
          const line = rawLine.trim()
          if (!line) { currentEventName = ''; continue }
          if (line.startsWith('event:')) {
            currentEventName = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const rawData = line.slice(5).trim()
            if (!rawData) continue
            try {
              const parsed = JSON.parse(rawData)
              const eventName = currentEventName || parsed.event || parsed.type || 'unknown'
              const evtData = parsed.data !== undefined ? parsed.data : parsed
              currentEventName = ''
              onEvent({ name: eventName as StreamEvent['name'], data: evtData } as StreamEvent)
            } catch {
              // ignore parse error
            }
          }
        }
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        onEvent({ name: 'error', data: { code: 'FETCH_ERROR', message: err.message } } as StreamErrorEvent)
      }
    })

  return () => controller.abort()
}


// ─── A2A SSE 流式订阅（真实后端）──────────────────────────────────────────────
export interface A2AStreamEvent {
  event: string
  data: any
}

/**
 * 订阅 A2A 后端 SSE 流
 * 使用 GET /a2a/tasks/stream 并传递查询参数
 * 返回取消函数
 */
export function subscribeA2AStream(
  params: TravelPlanRequest,
  onEvent: (event: { name: string; data: any }) => void
): () => void {
  const controller = new AbortController()

  const queryParams = new URLSearchParams({
    destination: params.destination,
    days: String(params.days),
    budget: String(params.budget),
    travelers: String(params.travelers || 1),
    travelStyle: params.travelStyle || '深度体验',
    interests: (params.interests || []).join(',')
  })

  fetch(`/a2a/tasks/stream?${queryParams.toString()}`, {
    method: 'GET',
    headers: sseHeaders(),
    signal: controller.signal
  })
    .then(async (response) => {
      console.log('[Agent SSE] response status:', response.status, 'content-type:', response.headers.get('content-type'))
      if (!response.ok || !response.body) {
        throw new Error(`HTTP ${response.status}`)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let currentEventName = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const rawLine of lines) {
          const line = rawLine.trim()
          if (!line) { currentEventName = ''; continue }
          if (line.startsWith('event:')) {
            currentEventName = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const rawData = line.slice(5).trim()
            if (!rawData) continue
            try {
              const parsed = JSON.parse(rawData)
              // 后端 SSE 格式：外层 {"event":"xxx", "data": {...}}
              // 事件名优先取 SSE event 字段，其次取 JSON 内的 event 字段
              const evtName = currentEventName || parsed.event || 'unknown'
              // 数据优先取 data 字段（SSE payload），没有则用整个 parsed
              const evtData = parsed.data !== undefined ? parsed.data : parsed
              onEvent({ name: evtName, data: evtData })
              currentEventName = ''
            } catch {
              // ignore parse error
            }
          } else if (line === '') {
            currentEventName = ''
          }
        }
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        onEvent({ name: 'error', data: { code: 'FETCH_ERROR', message: err.message } })
      }
    })

  return () => controller.abort()
}

// ─── 问卷式 Agent（流式问答 → 调API → 缓存 → 计划） ─────────────────────────

export interface QuestionnaireQuestion {
  sessionId: string
  stepIndex: number
  totalSteps: number
  question: string
  type: string
  options?: string[]
}

export interface QuestionnaireEvent {
  name: string
  data: any
}

/**
 * 创建问卷会话
 */
export function startQuestionnaire(userId: string = 'user_001') {
  return request.post<any, QuestionnaireQuestion>('/agent/questionnaire/start', { userId })
}

/**
 * 提交一步回答，通过 fetch(SSE) 流式接收：
 * parsed / tool_call / tool_result / next_question / plan / error
 * 返回取消函数。
 *
 * @param onEvent 每个 SSE 事件的回调
 * @param onDone 流完全结束后的回调（所有事件处理完毕后调用一次）
 */
export function submitQuestionnaireAnswer(
  sessionId: string,
  step: number,
  answer: string,
  onEvent: (event: QuestionnaireEvent) => void,
  onDone?: () => void
): () => void {
  const controller = new AbortController()
  let finished = false
  const finishOnce = () => {
    if (finished) return
    finished = true
    onDone?.()
  }

  // 看门狗：即使 SSE 流既没收到结束也没报错（网络/连接卡死），也在超时后解锁 UI，
  // 避免 sending 一直被置为 true 导致输入框禁用、界面“卡住”。
  const watchdog = setTimeout(finishOnce, 20000)

  fetch(`/api/agent/questionnaire/${sessionId}/answer?step=${step}&answer=${encodeURIComponent(answer)}`, {
    method: 'POST',
    headers: sseHeaders(),
    signal: controller.signal
  })
    .then(async (response) => {
      console.log('[Agent SSE] response status:', response.status, 'content-type:', response.headers.get('content-type'))
      if (!response.ok || !response.body) {
        throw new Error(`HTTP ${response.status}`)
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let currentEventName = ''

      let chunkCount = 0
      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          console.log('[Agent SSE] stream ended, total chunks:', chunkCount)
          break
        }
        chunkCount++
        const text = decoder.decode(value, { stream: true })
        console.log('[Agent SSE] chunk #' + chunkCount + ' raw:', JSON.stringify(text))
        buffer += text
        // Handle both CRLF and LF line endings
        const lines = buffer.split(/\r?\n/)
        buffer = lines.pop() || ''
        for (const rawLine of lines) {
          const line = rawLine.trim()
          if (!line) {
            // Empty line = event boundary, reset for next event
            continue
          }
          // Match "event:" prefix (with or without space)
          if (line.startsWith('event:')) {
            currentEventName = line.slice(6).trim()
            console.log('[Agent SSE] >>> event:', currentEventName)
          } else if (line.startsWith('data:')) {
            const rawData = line.slice(5).trim()
            if (!rawData) continue
            try {
              const parsed = JSON.parse(rawData)
              const evtName = currentEventName || parsed.type || 'unknown'
              console.log('[Agent SSE] >>> dispatch:', evtName, parsed)
              onEvent({ name: evtName, data: parsed })
              currentEventName = ''
            } catch (parseErr) {
              console.warn('[Agent SSE] parse error:', rawData, parseErr)
            }
          } else {
            console.log('[Agent SSE] unknown line:', line)
          }
        }
      }
      // 流正常结束，通知调用方
      finishOnce()
    })
    .catch((err) => {
      console.error('[Agent SSE] fetch error:', err.name, err.message)
      if (err.name !== 'AbortError') {
        onEvent({ name: 'error', data: { message: err.message } })
      }
      finishOnce()
    })
    .finally(() => clearTimeout(watchdog))

  return () => controller.abort()
}
