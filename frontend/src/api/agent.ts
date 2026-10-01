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
// ─── A2A SSE 流式订阅（真实后端）──────────────────────────────────────────────
export interface A2AStreamEvent {
  event: string
  data: any
}

/** Creates one idempotent task, then reconnects to its event stream without re-running the model. */
export function subscribeA2AStream(
  params: TravelPlanRequest,
  onEvent: (event: { name: string; data: any }) => void
): () => void {
  const controller = new AbortController()
  let lastEventId = '0'
  let terminal = false

  const reportError = (message: string, code = 'FETCH_ERROR') => {
    onEvent({ name: 'error', data: { code, message } })
  }
  const waitBeforeReconnect = (milliseconds: number) => new Promise<void>((resolve) => {
    const onAbort = () => {
      window.clearTimeout(timer)
      resolve()
    }
    const timer = window.setTimeout(() => {
      controller.signal.removeEventListener('abort', onAbort)
      resolve()
    }, milliseconds)
    controller.signal.addEventListener('abort', onAbort, { once: true })
  })
  const newIdempotencyKey = () => {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
    return 'a2a-' + Date.now() + '-' + Math.random().toString(36).slice(2)
  }

  const createTask = async (): Promise<string | null> => {
    const key = newIdempotencyKey()
    for (let attempt = 0; attempt < 3 && !controller.signal.aborted; attempt++) {
      try {
        const headers = new Headers(sseHeaders())
        headers.set('Accept', 'application/json')
        headers.set('Content-Type', 'application/json')
        headers.set('Idempotency-Key', key)
        const response = await fetch('/a2a/tasks', {
          method: 'POST',
          headers,
          body: JSON.stringify(params),
          signal: controller.signal
        })
        if (!response.ok) {
          if (response.status >= 500 && attempt < 2) {
            await waitBeforeReconnect(300 * (attempt + 1))
            continue
          }
          const body = await response.text().catch(() => '')
          reportError(body || ('创建规划任务失败（HTTP ' + response.status + '）'), 'HTTP_' + response.status)
          return null
        }
        const result = await response.json()
        if (!result.taskId) {
          reportError('服务端未返回规划任务 ID')
          return null
        }
        return String(result.taskId)
      } catch (error: any) {
        if (controller.signal.aborted) return null
        if (attempt === 2) {
          reportError(error?.message || '创建规划任务失败')
          return null
        }
        await waitBeforeReconnect(300 * (attempt + 1))
      }
    }
    return null
  }

  const consumeStream = async (response: Response): Promise<boolean> => {
    if (!response.body) throw new Error('SSE 响应没有消息流')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let eventName = ''
    let eventId: string | null = null
    let dataLines: string[] = []

    const dispatch = () => {
      if (eventId !== null && /^\d+$/.test(eventId)) lastEventId = eventId
      if (dataLines.length > 0) {
        try {
          const parsed = JSON.parse(dataLines.join('\n'))
          const name = eventName || parsed.event || 'unknown'
          const data = parsed.data !== undefined ? parsed.data : parsed
          onEvent({ name, data })
          if (name === 'task_done' || name === 'error'
            || (name === 'task_update' && data?.status === 'CANCELLED')) {
            terminal = true
          }
        } catch {
          // Ignore malformed event payloads; later events can still be consumed.
        }
      }
      eventName = ''
      eventId = null
      dataLines = []
    }

    while (!controller.signal.aborted) {
      const { done, value } = await reader.read()
      if (done) {
        if (buffer.length > 0) {
          const line = buffer.endsWith('\r') ? buffer.slice(0, -1) : buffer
          if (line.startsWith('id:')) eventId = line.slice(3).trim()
          else if (line.startsWith('event:')) eventName = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
        }
        dispatch()
        return terminal
      }
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop() || ''
      for (const line of lines) {
        if (line === '') {
          dispatch()
        } else if (line.startsWith(':')) {
          continue
        } else if (line.startsWith('id:')) {
          eventId = line.slice(3).trim()
        } else if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).replace(/^ /, ''))
        }
      }
    }
    return terminal
  }

  const connectWithReconnect = async (taskId: string) => {
    let attempt = 0
    while (!controller.signal.aborted && !terminal) {
      try {
        const headers = new Headers(sseHeaders())
        if (lastEventId !== '0') headers.set('Last-Event-ID', lastEventId)
        const response = await fetch('/a2a/tasks/' + encodeURIComponent(taskId) + '/stream', {
          method: 'GET',
          headers,
          signal: controller.signal
        })
        if (!response.ok) {
          const body = await response.text().catch(() => '')
          reportError(body || ('连接规划任务失败（HTTP ' + response.status + '）'), 'HTTP_' + response.status)
          return
        }
        terminal = await consumeStream(response)
        if (terminal || controller.signal.aborted) return
      } catch (error: any) {
        if (controller.signal.aborted) return
      }

      if (attempt >= 7) {
        reportError('规划结果连接中断，任务 ID: ' + taskId)
        return
      }
      await waitBeforeReconnect(Math.min(500 * (2 ** attempt), 5000))
      attempt++
    }
  }

  void (async () => {
    const taskId = await createTask()
    if (taskId && !controller.signal.aborted) await connectWithReconnect(taskId)
  })()

  return () => controller.abort()
}


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
