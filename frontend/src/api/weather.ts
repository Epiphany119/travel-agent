/** 免费天气方案：Open-Meteo（无需 API key，浏览器可直接跨域调用） */

export interface DayWeather {
  date: string
  text: string
  icon: string
  tempMin: number | null
  tempMax: number | null
  temp: number | null
}

export interface ItineraryWeather {
  city: string
  success: boolean
  icon: string
  text: string
  tempMin: number | null
  tempMax: number | null
  forecast: DayWeather[]
}

/** WMO weather code -> 中文文案 + 图标 */
const WMO: Record<number, { text: string; icon: string }> = {
  0: { text: '晴', icon: '☀️' },
  1: { text: '晴间多云', icon: '🌤️' },
  2: { text: '多云', icon: '⛅' },
  3: { text: '阴', icon: '☁️' },
  45: { text: '雾', icon: '🌫️' },
  48: { text: '雾凇', icon: '🌫️' },
  51: { text: '小毛毛雨', icon: '🌦️' },
  53: { text: '毛毛雨', icon: '🌦️' },
  55: { text: '大毛毛雨', icon: '🌧️' },
  56: { text: '冻毛毛雨', icon: '🌧️' },
  57: { text: '强冻毛毛雨', icon: '🌧️' },
  61: { text: '小雨', icon: '🌦️' },
  63: { text: '中雨', icon: '🌧️' },
  65: { text: '大雨', icon: '🌧️' },
  66: { text: '冻雨', icon: '🌧️' },
  67: { text: '强冻雨', icon: '🌧️' },
  71: { text: '小雪', icon: '🌨️' },
  73: { text: '中雪', icon: '❄️' },
  75: { text: '大雪', icon: '❄️' },
  77: { text: '米雪', icon: '❄️' },
  80: { text: '阵雨', icon: '🌦️' },
  81: { text: '强阵雨', icon: '🌧️' },
  82: { text: '暴雨', icon: '⛈️' },
  85: { text: '阵雪', icon: '🌨️' },
  86: { text: '强阵雪', icon: '❄️' },
  95: { text: '雷阵雨', icon: '⛈️' },
  96: { text: '雷暴伴冰雹', icon: '⛈️' },
  99: { text: '强雷暴伴冰雹', icon: '⛈️' },
}

function describe(code: any) {
  return WMO[Number(code)] || { text: '天气变化', icon: '🌤️' }
}

function localDate(d: Date) {
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10)
}

function addDays(dateStr: string, days: number) {
  const d = new Date(dateStr + 'T00:00:00')
  d.setDate(d.getDate() + days)
  return localDate(d)
}

async function getJson(url: string, timeoutMs = 8000) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const res = await fetch(url, { signal: controller.signal })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return await res.json()
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 按目的地和行程日期查天气。优先用行程首日日期对齐预报，查不到就用今天起 7 天。
 */
export async function fetchItineraryWeather(
  destination: string,
  startDate?: string,
  days = 7,
): Promise<ItineraryWeather | null> {
  if (!destination) return null
  try {
    const geoUrl = `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(destination)}&count=1&language=zh&format=json`
    const geo = await getJson(geoUrl)
    const place = geo?.results?.[0]
    if (!place) return null

    const start = startDate && /^\d{4}-\d{2}-\d{2}$/.test(startDate)
      ? startDate
      : localDate(new Date())
    const end = addDays(start, Math.max(1, days) - 1)
    const forecastUrl = `https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}` +
      `&daily=weather_code,temperature_2m_max,temperature_2m_min` +
      `&timezone=${encodeURIComponent(place.timezone || 'auto')}` +
      `&start_date=${start}&end_date=${end}`
    const data = await getJson(forecastUrl)
    const daily = data?.daily
    if (!daily?.time?.length) return null

    const forecast: DayWeather[] = daily.time.map((date: string, i: number) => {
      const desc = describe(daily.weather_code?.[i])
      const tempMax = daily.temperature_2m_max?.[i] ?? null
      const tempMin = daily.temperature_2m_min?.[i] ?? null
      return {
        date,
        text: desc.text,
        icon: desc.icon,
        tempMax: tempMax == null ? null : Math.round(tempMax),
        tempMin: tempMin == null ? null : Math.round(tempMin),
        temp: tempMax == null ? null : Math.round(tempMax),
      }
    })
    const first = forecast[0]
    return {
      city: place.name || destination,
      success: true,
      icon: first.icon,
      text: first.text,
      tempMin: first.tempMin,
      tempMax: first.tempMax,
      forecast,
    }
  } catch (e) {
    console.warn('[weather] Open-Meteo 查询失败', e)
    return null
  }
}
