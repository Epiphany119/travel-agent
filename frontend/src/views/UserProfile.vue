<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPreferences, savePreferences, uploadAvatar, getAvatar, updateNickname, listPublicNotes, type UserPreference, type SocialNote } from '@/api/user'
import { getCommercialSummary, confirmBookingItem, cancelBookingItem, type CommercialSummaryResponse } from '@/api/commerce'
import { logout as apiLogout, sendEmailCode, bindEmail as apiBindEmail, unbindEmail as apiUnbindEmail } from '@/api/auth'
import { applySystemPalette as applyPalette, parseSystemPalette } from '@/utils/theme'

const router = useRouter()
const userStore = useUserStore()
const saving = ref(false)
const loaded = ref(false)
const rightView = ref<'posts' | 'orders' | 'prefs'>('posts')
const commercialSummary = ref<CommercialSummaryResponse | null>(null)
const ordersLoading = ref(false)
const rightPanelCollapsed = ref(false)
const ownPosts = ref<SocialNote[]>([])
const postsLoading = ref(false)
const postImageErrors = reactive<Record<string, boolean>>({})
const paletteOpen = ref(false)
const systemPalette = reactive({
  fg: '#1D2B27', bg: '#F7F3EA', accent: '#164E42', highlight: '#F27A4F'
})
const palettePresets = [
  { name: 'Roamly', fg: '#1D2B27', bg: '#F7F3EA', accent: '#164E42', highlight: '#F27A4F' },
  { name: '品牌米白', fg: '#143C35', bg: '#FBF8F1', accent: '#1B5B4E', highlight: '#F4774B' },
  { name: '海岸', fg: '#17324D', bg: '#EEF7FA', accent: '#147D92', highlight: '#3BA8B3' },
  { name: '日落', fg: '#43251C', bg: '#FFF5ED', accent: '#C85A36', highlight: '#E87A45' },
  { name: '墨绿', fg: '#E8F1EC', bg: '#10241E', accent: '#4FBE91', highlight: '#F2A36B' }
]

function applySystemPalette() {
  applyPalette(systemPalette)
}

async function saveSystemPalette() {
  saving.value = true
  try {
    await savePreferences({
      ...form,
      systemThemeJson: JSON.stringify(systemPalette),
      userId: localStorage.getItem('roamly_user_id') || 'user_001'
    })
    form.systemThemeJson = JSON.stringify(systemPalette)
    applySystemPalette()
    paletteOpen.value = false
    ElMessage.success('系统主题已保存')
  } catch (e) {
    console.error(e)
    ElMessage.error('系统主题保存失败')
  } finally { saving.value = false }
}

function closePalette() {
  // Discard unsaved edits and restore the last database-backed palette.
  loadSystemPalette()
  paletteOpen.value = false
}

function loadSystemPalette() {
  try {
    Object.assign(systemPalette, parseSystemPalette(form.systemThemeJson))
  } catch {}
  applySystemPalette()
}

// 头像
const avatar = ref<string>('')
const uploadPreview = ref<string | null>(null)
const selectedFile = ref<File | null>(null)
const isUploading = ref(false)
const uploadRef = ref<HTMLInputElement | null>(null)
const dragOver = ref(false)

// 绑定邮箱相关
const bindingFormOpen = ref(false)
const bindingEmail = ref('')
const bindingCode = ref('')
const bindingLoading = ref(false)
const bindingSending = ref(false)
const bindingCountdown = ref(0)
let bindingTimer: ReturnType<typeof setInterval> | undefined

// 解绑相关
const unbindingLoading = ref(false)

// 个人资料 + 偏好
const form = reactive<UserPreference>({
  name: '', email: '', phone: '',
  favoriteDestinations: '', defaultDepartureCity: '',
  defaultDays: 5, defaultBudget: 8000, defaultTravelers: 2,
  preferredSeason: '不限', preferredTripType: '深度体验', budgetLevel: '舒适',
  travelStyle: '', interests: '', attractionTypes: '',
  preferFreeAttractions: false,
  dietaryRequirements: '', preferredCuisines: '', spicyLevel: 1,
  accommodationType: '', hotelStarMin: 3,
  transportationPreference: '',
  notifyBeforeTripDays: 3, notifyWeatherAlert: true, notifyPriceChange: true
})

const interestOptions = ['美食','人文','自然','摄影','购物','夜生活','历史','艺术','冒险','户外','亲子','养生','音乐','建筑']
const styleOptions = ['轻松漫游','深度人文','美食优先','亲子友好','户外探险','奢华度假','都市探索','自然风光']
const attractionOptions = ['自然','人文','历史遗迹','博物馆','主题公园','古镇','海滩','雪山','动物园','水族馆']
const dietOptions = ['无特殊要求','清真','素食','纯素','无麸质','忌辣','忌海鲜','儿童餐']
const cuisineOptions = ['川菜','粤菜','湘菜','江浙菜','本帮菜','日料','韩餐','东南亚菜','西餐','火锅','烧烤','家常菜']
const accommodationOptions = ['酒店','民宿','青旅','度假村','精品酒店','公寓']
const transportOptions = ['高铁','自驾','飞机','地铁','公交','骑行','徒步','包车']

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function toList(v: unknown): string[] {
  if (!v) return []
  const s = String(v).trim()
  if (!s) return []
  if (s.startsWith('[')) { try { return JSON.parse(s) } catch { return [] } }
  return s.split(/[,，]/).map(x => x.trim()).filter(Boolean)
}

const interests = computed({ get: () => toList(form.interests), set: (v) => { form.interests = v.join(',') } })
const attractionTypes = computed({ get: () => toList(form.attractionTypes), set: (v) => { form.attractionTypes = v.join(',') } })
const dietaryRequirements = computed({ get: () => toList(form.dietaryRequirements), set: (v) => { form.dietaryRequirements = v.join(',') } })
const preferredCuisines = computed({ get: () => toList(form.preferredCuisines), set: (v) => { form.preferredCuisines = v.join(',') } })
const accommodationType = computed({ get: () => toList(form.accommodationType), set: (v) => { form.accommodationType = v.join(',') } })
const transportationPreference = computed({ get: () => toList(form.transportationPreference), set: (v) => { form.transportationPreference = v.join(',') } })

function resolveUrl(url: string) {
  const raw = String(url || '').trim()
  if (!raw) return ''
  if (/^(https?:|data:|blob:)/i.test(raw)) return raw
  const base = String(import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
  return `${base}${raw.startsWith('/') ? raw : `/${raw}`}`
}

function postKey(note: SocialNote) {
  return String(note.id ?? `${note.user_id || note.userId || 'post'}-${note.title}`)
}

function extractFirstImage(content: unknown) {
  const source = String(content || '')
  const html = source.match(/<img[^>]+src=["']([^"']+)["']/i)?.[1]
  if (html) return html
  return source.match(/!\[[^\]]*\]\(([^)\s]+)(?:\s+[^)]*)?\)/i)?.[1] || ''
}

function postImage(note: SocialNote) {
  if (postImageErrors[postKey(note)]) return ''
  const value = note.coverUrl || note.cover_url || extractFirstImage(note.content) || (note as SocialNote & { image?: string }).image || ''
  return resolveUrl(value)
}

function handlePostImageError(note: SocialNote) {
  postImageErrors[postKey(note)] = true
}

function postTags(note: SocialNote) {
  if (Array.isArray(note.tags)) return note.tags.map(tag => String(tag).trim()).filter(Boolean)
  const raw = String(note.tags || '').trim()
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    if (Array.isArray(parsed)) return parsed.map(tag => String(tag).trim()).filter(Boolean)
  } catch { /* 兼容旧数据的逗号分隔标签 */ }
  return raw.split(/[,，]/).map(tag => tag.trim()).filter(Boolean)
}

function postAuthor(note: SocialNote) {
  const currentUserId = localStorage.getItem('roamly_user_id') || 'user_001'
  const noteUserId = note.user_id || note.userId || ''
  if (String(noteUserId) === currentUserId && form.name) return form.name
  return note.author || note.authorName || note.author_name || '旅行者'
}

function postAvatar(note: SocialNote) {
  const currentUserId = localStorage.getItem('roamly_user_id') || 'user_001'
  const noteUserId = note.user_id || note.userId || ''
  const value = String(noteUserId) === currentUserId
    ? avatar.value || note.authorAvatar || note.author_avatar || ''
    : note.authorAvatar || note.author_avatar || ''
  return resolveUrl(value)
}

function postLikes(note: SocialNote) {
  return note.likeCount ?? note.like_count ?? 0
}

function openOwnPost(note: SocialNote) {
  if (!note.id) return
  router.push({ path: '/card-detail', query: { noteId: String(note.id) } })
}

async function loadOwnPosts() {
  postsLoading.value = true
  try { ownPosts.value = (await listPublicNotes(0, 50, undefined, undefined, localStorage.getItem('roamly_user_id') || 'user_001')).data || [] }
  catch { ownPosts.value = [] }
  finally { postsLoading.value = false }
}

async function load() {
  try {
    const prefRes = await getPreferences()
    const p = prefRes.data || {}
    Object.assign(form, p)
    loadSystemPalette()
    // 关键：从后端合并的 auth_account 数据中获取邮箱
    // 后端 getPreferences 接口现在会返回 email 字段（来自 auth_account 表）
    if (form.email) {
      console.log('加载到绑定邮箱:', form.email)
    } else {
      console.log('当前用户未绑定邮箱')
    }
    if (form.name) {
      userStore.setNickname(form.name)
    } else if (p.username) {
      // 如果 nickname 为空，使用 auth_account 的 username
      userStore.setNickname(p.username as string)
      form.name = p.username as string
    }
  } catch (e) {
    console.error('加载偏好失败', e)
  }

  try {
    const avatarRes = await getAvatar()
    avatar.value = resolveUrl(avatarRes.data?.avatar || '')
    if (avatar.value) userStore.setAvatar(avatar.value)
  } catch (e) {
    console.error('加载头像失败', e)
  }

  loaded.value = true
}

// 头像处理
function openPicker() { uploadRef.value?.click() }
function handleDragOver(e: DragEvent) { e.preventDefault(); dragOver.value = true }
function handleDragLeave() { dragOver.value = false }
function handleDrop(e: DragEvent) { e.preventDefault(); dragOver.value = false; const f = e.dataTransfer?.files[0]; if (f) pickFile(f) }
function handleChange(e: Event) { const f = (e.target as HTMLInputElement).files?.[0]; if (f) pickFile(f) }
function pickFile(file: File) {
  if (!file.type.startsWith('image/')) { ElMessage.warning('请选择图片'); return }
  if (file.size > 5 * 1024 * 1024) { ElMessage.warning('图片不能超过 5MB'); return }
  selectedFile.value = file
  const reader = new FileReader()
  reader.onload = (ev) => { uploadPreview.value = ev.target?.result as string }
  reader.readAsDataURL(file)
}
async function confirmAvatar() {
  if (!selectedFile.value) { ElMessage.warning('请先选择图片'); return }
  isUploading.value = true
  try {
    const res = await uploadAvatar(selectedFile.value)
    const url = res.data?.avatar
    avatar.value = resolveUrl(url || uploadPreview.value || '')
    userStore.setAvatar(avatar.value)
    uploadPreview.value = null; selectedFile.value = null
    ElMessage.success('头像已更新')
  } catch (e) { console.error(e); ElMessage.error('头像上传失败') }
  finally { isUploading.value = false }
}

// 保存资料
async function save() {
  saving.value = true
  try {
    await savePreferences({ ...form, systemThemeJson: JSON.stringify(systemPalette), userId: localStorage.getItem('roamly_user_id') || 'user_001' })
    if (form.name) { await updateNickname(form.name); userStore.setNickname(form.name) }
    if (avatar.value) userStore.setAvatar(avatar.value)
    await userStore.fetchProfile()
    ElMessage.success('资料已保存')
  } catch (e) { console.error(e); ElMessage.error('保存失败') }
  finally { saving.value = false }
}

// 打开绑定表单
function openBindForm() {
  bindingFormOpen.value = true
  bindingEmail.value = form.email || ''
  bindingCode.value = ''
}

// 关闭绑定表单
function closeBindForm() {
  bindingFormOpen.value = false
  bindingEmail.value = ''
  bindingCode.value = ''
  if (bindingTimer) clearInterval(bindingTimer)
  bindingCountdown.value = 0
}

// 绑定邮箱 - 发送验证码
async function handleBindingSendCode() {
  if (!bindingEmail.value || !EMAIL_REGEX.test(bindingEmail.value)) {
    ElMessage.warning('请输入正确的邮箱地址')
    return
  }
  if (bindingCountdown.value > 0) return

  bindingSending.value = true
  try {
    await sendEmailCode(bindingEmail.value.trim())
    ElMessage.success('验证码已发送，请查收邮件')
    bindingCountdown.value = 60
    bindingTimer = setInterval(() => {
      bindingCountdown.value--
      if (bindingCountdown.value <= 0) {
        if (bindingTimer) clearInterval(bindingTimer)
        bindingCountdown.value = 0
      }
    }, 1000)
  } catch (err: any) {
    const msg = err?.response?.data?.message || '验证码发送失败，请稍后重试'
    ElMessage.error(msg)
  } finally {
    bindingSending.value = false
  }
}

// 绑定邮箱 - 提交
async function handleBindEmail() {
  if (!bindingEmail.value || !EMAIL_REGEX.test(bindingEmail.value)) {
    ElMessage.warning('请输入正确的邮箱地址')
    return
  }
  if (!bindingCode.value || bindingCode.value.length !== 6) {
    ElMessage.warning('请输入 6 位验证码')
    return
  }

  bindingLoading.value = true
  try {
    await apiBindEmail({ email: bindingEmail.value.trim(), code: bindingCode.value.trim() })
    form.email = bindingEmail.value.trim()
    ElMessage.success('邮箱绑定成功')
    closeBindForm()
  } catch (err: any) {
    const msg = err?.response?.data?.message
      || err?.message
      || '绑定失败，请检查邮箱和验证码是否正确'
    ElMessage.error(msg)
  } finally {
    bindingLoading.value = false
  }
}

// 解绑邮箱
async function handleUnbindEmail() {
  if (!form.email) return

  try {
    await ElMessageBox.confirm(
      `确定要解绑邮箱 ${form.email} 吗？解绑后将无法使用邮箱验证码登录。`,
      '解绑邮箱',
      { confirmButtonText: '解绑', cancelButtonText: '取消', type: 'warning' }
    )
    unbindingLoading.value = true
    try {
      await apiUnbindEmail()
      form.email = ''
      ElMessage.success('邮箱已解绑')
    } catch (err: any) {
      const msg = err?.response?.data?.message || '解绑失败，请稍后重试'
      ElMessage.error(msg)
    } finally {
      unbindingLoading.value = false
    }
  } catch { /* 用户取消 */ }
}

// 退出登录
async function handleLogout() {
  try {
    await ElMessageBox.confirm(
      '确定要退出登录吗？',
      '退出登录',
      { confirmButtonText: '退出', cancelButtonText: '取消', type: 'warning' }
    )
    try { await apiLogout() } catch { /* 忽略 */ }
    localStorage.removeItem('roamly_token')
    localStorage.removeItem('roamly_user_id')
    localStorage.removeItem('roamly_username')
    userStore.setAvatar('')
    userStore.setNickname('旅人')
    ElMessage.success('已退出登录')
    router.replace('/auth')
  } catch { /* 用户取消 */ }
}

async function loadCommercialSummary() {
  ordersLoading.value = true
  try {
    commercialSummary.value = await getCommercialSummary()
  } catch {
    commercialSummary.value = null
  } finally {
    ordersLoading.value = false
  }
}

async function handleConfirmOrder(bookingNo: string) {
  try {
    await confirmBookingItem(bookingNo)
    ElMessage.success('已模拟确认预订，CPS 归因数据已刷新')
    await loadCommercialSummary()
  } catch {
    ElMessage.error('确认失败，请稍后重试')
  }
}

async function handleCancelOrder(bookingNo: string) {
  try {
    await cancelBookingItem(bookingNo)
    ElMessage.info('已取消该预订占位')
    await loadCommercialSummary()
  } catch {
    ElMessage.error('取消失败，请稍后重试')
  }
}

function bookingCategoryLabel(category: string) {
  switch (category) {
    case 'HOTEL': return '酒店住宿'
    case 'FLIGHT': return '往返机票'
    case 'HIGH_SPEED_RAIL': return '高铁动车'
    case 'TICKET': return '景点门票'
    default: return category || '旅行服务'
  }
}

function bookingStatusLabel(status: string) {
  switch (status) {
    case 'CONFIRMED': return '已确认'
    case 'CANCELLED': return '已取消'
    default: return '待确认'
  }
}

onMounted(() => { loadSystemPalette(); load(); loadOwnPosts(); loadCommercialSummary() })
</script>

<template>
  <main class="shell">
    <section class="profile-head">
      <p class="eyebrow">YOUR PROFILE</p>
      <h1>个人主页</h1>
      <p class="sub">管理头像、个人信息与旅行偏好，让 Roamly 更懂你。</p>
    </section>

    <div class="layout" v-loading="!loaded">
      <!-- 左：资料卡片固定在原位，不再参与右侧内容的收起/展开 -->
      <aside class="profile-card">
        <div class="card-hero">
          <div class="avatar-ring">
            <div
              class="avatar-uploader" :class="{ 'drag-over': dragOver }"
              @dragover="handleDragOver" @dragleave="handleDragLeave" @drop="handleDrop"
              @click="openPicker"
            >
              <img v-if="avatar" :src="avatar" alt="头像" />
              <span v-else class="ph">{{ (form.name || '旅').slice(0, 1) }}</span>
              <div class="mask"><span>更换头像</span></div>
              <input ref="uploadRef" type="file" accept="image/*" style="display:none" @change="handleChange" />
            </div>
          </div>

          <div v-if="uploadPreview" class="preview-box">
            <img :src="uploadPreview" alt="预览" />
            <div class="row-btn">
              <el-button size="small" @click="uploadPreview = null; selectedFile = null">取消</el-button>
              <el-button size="small" type="primary" :loading="isUploading" @click="confirmAvatar">确认</el-button>
            </div>
          </div>

          <h2>{{ form.name || '旅人' }}</h2>
          <div class="contact-info">
            <p class="email">
              <span v-if="form.email">{{ form.email }}</span>
              <span v-else class="email-unbound">未绑定邮箱</span>
            </p>
            <p v-if="form.phone" class="phone">📱 {{ form.phone }}</p>
          </div>

          <div class="stat-row">
            <div class="stat">
              <b>{{ form.defaultDays || 0 }}</b>
              <span>默认天数</span>
            </div>
            <div class="stat">
              <b>¥{{ (form.defaultBudget || 0).toLocaleString() }}</b>
              <span>默认预算</span>
            </div>
            <div class="stat">
              <b>{{ form.defaultTravelers || 0 }}</b>
              <span>同行人数</span>
            </div>
          </div>
        </div>

        <div class="card-body">
        <div class="card-form">
          <h5 class="sec-label">基本信息</h5>
          <el-form label-position="top" class="basic">
            <el-form-item label="昵称"><el-input v-model="form.name" placeholder="你的昵称" /></el-form-item>
            <el-form-item label="手机号"><el-input v-model="form.phone" placeholder="选填" /></el-form-item>
          </el-form>
        </div>

        <!-- 账号安全区域 -->
        <div class="card-security">
          <h5 class="sec-label">账号安全</h5>

          <!-- 邮箱绑定状态 -->
          <div class="bind-status">
            <div class="bind-info">
              <span class="bind-label">邮箱</span>
              <span v-if="form.email" class="bind-value bound">{{ form.email }}</span>
              <span v-else class="bind-value unbound">未绑定</span>
            </div>
          </div>

          <!-- 操作按钮行 -->
          <div class="bind-actions-row">
            <button
              v-if="!form.email"
              class="action-btn primary"
              @click="openBindForm"
              type="button"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z"/><polyline points="22,6 12,13 2,6"/></svg>
              绑定邮箱
            </button>
            <template v-else>
              <button class="action-btn" @click="openBindForm" type="button">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                更换邮箱
              </button>
              <button
                class="action-btn danger"
                @click="handleUnbindEmail"
                :disabled="unbindingLoading"
                type="button"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6L6 18M6 6l12 12"/></svg>
                {{ unbindingLoading ? '解绑中' : '解绑' }}
              </button>
            </template>
          </div>

          <!-- 绑定邮箱表单 -->
          <div v-if="bindingFormOpen" class="bind-form">
            <div class="bind-form-header">
              <span class="bind-form-title">{{ form.email ? '更换邮箱' : '绑定新邮箱' }}</span>
              <button class="bind-form-close" @click="closeBindForm" type="button">✕</button>
            </div>
            <input
              v-model="bindingEmail"
              type="email"
              placeholder="输入邮箱地址"
              class="form-input-sm"
            />
            <div class="bind-code-row">
              <input
                v-model="bindingCode"
                type="text"
                maxlength="6"
                placeholder="6 位验证码"
                class="form-input-sm code"
              />
              <button
                class="code-btn-sm"
                :disabled="bindingCountdown > 0 || bindingSending"
                @click="handleBindingSendCode"
                type="button"
              >
                {{ bindingSending ? '发送中' : bindingCountdown > 0 ? `${bindingCountdown}s` : '获取验证码' }}
              </button>
            </div>
            <button
              class="bind-submit"
              :disabled="bindingLoading"
              @click="handleBindEmail"
              type="button"
            >
              {{ bindingLoading ? '提交中…' : '确认' }}
            </button>
            <p class="bind-hint">
              绑定后可用邮箱验证码快速登录
            </p>
          </div>

          <!-- 退出登录 -->
          <button class="logout-btn" @click="handleLogout" type="button">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="15" height="15">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
              <polyline points="16 17 21 12 16 7"/>
              <line x1="21" y1="12" x2="9" y2="12"/>
            </svg>
            退出登录
          </button>
        </div>

        <!-- 保存按钮：卡片底部固定 -->
        <div class="card-footer">
          <button class="save-btn" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存资料' }}
          </button>
        </div>
        </div>
      </aside>

      <!-- 右：内容面板。左侧固定，右侧可以独立切换和收起。 -->
      <div class="right-panel-column" :class="{ 'is-collapsed': rightPanelCollapsed }">
        <div class="right-panel-toolbar">
          <div class="right-panel-tabs" role="tablist" aria-label="个人主页内容">
            <button class="right-panel-tab" :class="{ active: rightView === 'posts' }" type="button" role="tab" :aria-selected="rightView === 'posts'" @click="rightView = 'posts'; rightPanelCollapsed = false">
              <span class="tab-mark">◈</span> 我的公开帖子 <small>{{ ownPosts.length }}</small>
            </button>
            <button class="right-panel-tab" :class="{ active: rightView === 'orders' }" type="button" role="tab" :aria-selected="rightView === 'orders'" @click="rightView = 'orders'; rightPanelCollapsed = false; loadCommercialSummary()">
              <span class="tab-mark">🛒</span> 订单与分佣 <small>{{ commercialSummary?.totalCount || 0 }}</small>
            </button>
            <button class="right-panel-tab" :class="{ active: rightView === 'prefs' }" type="button" role="tab" :aria-selected="rightView === 'prefs'" @click="rightView = 'prefs'; rightPanelCollapsed = false">
              <span class="tab-mark">⚙</span> 旅行偏好
            </button>
          </div>
          <button class="right-panel-collapse" type="button" :aria-expanded="!rightPanelCollapsed" @click="rightPanelCollapsed = !rightPanelCollapsed">
            <span>{{ rightPanelCollapsed ? '展开面板' : '收起面板' }}</span><b>{{ rightPanelCollapsed ? '↘' : '↗' }}</b>
          </button>
        </div>

        <section v-if="rightPanelCollapsed" class="right-panel-collapsed-card">
          <div>
            <p class="eyebrow">RIGHT PANEL</p>
            <h3>{{ rightView === 'posts' ? '我的公开帖子' : rightView === 'orders' ? '订单与 CPS 分佣看板' : '旅行偏好' }}</h3>
            <p>面板已收起，左侧资料卡和当前页面位置保持不变。</p>
          </div>
          <button type="button" @click="rightPanelCollapsed = false">展开内容 <span>→</span></button>
        </section>

        <!-- 公开帖子：列表本身拥有明确的内部滚动边界 -->
        <section v-else-if="rightView === 'posts'" class="panel posts-panel">
          <div class="posts-panel-head">
            <div><p class="eyebrow">MY CIRCLE</p><h3>我的公开帖子</h3><p>只有已发布到圈子的笔记会出现在这里。</p></div>
            <button type="button" @click="router.push('/publish')">＋ 发布笔记</button>
          </div>
          <div v-loading="postsLoading" class="profile-post-grid">
            <article v-for="post in ownPosts" :key="post.id" class="profile-post" tabindex="0" @click="openOwnPost(post)" @keyup.enter="openOwnPost(post)">
              <div class="profile-post-cover">
                <img v-if="postImage(post)" :src="postImage(post)" :alt="post.destination || post.title" loading="lazy" @error="handlePostImageError(post)" />
                <span v-else class="profile-post-cover-placeholder">{{ post.destination || 'ROAMLY' }}</span>
                <button class="profile-post-save" type="button" title="打开笔记" @click.stop="openOwnPost(post)">☆</button>
                <div class="profile-post-open">查看并编辑 →</div>
              </div>
              <div class="profile-post-body">
                <div class="profile-post-author">
                  <span class="post-avatar"><img v-if="postAvatar(post)" :src="postAvatar(post)" alt="" /><span v-else>{{ postAuthor(post).slice(0, 1) }}</span></span>
                  <span>{{ postAuthor(post) }}</span><span class="post-dot">·</span><span>{{ post.destination || '旅行笔记' }}</span>
                </div>
                <h4>{{ post.title || '未命名旅行笔记' }}</h4>
                <div class="profile-post-tags">
                  <span v-for="tag in postTags(post).slice(0, 3)" :key="tag">{{ tag }}</span>
                </div>
                <div class="profile-post-footer">
                  <span>♡ {{ postLikes(post) }}</span>
                  <span>◌ 可编辑笔记</span>
                </div>
              </div>
            </article>
            <p v-if="!postsLoading && !ownPosts.length" class="profile-post-empty">还没有公开帖子，发布你的第一段旅程吧。</p>
          </div>
        </section>

        <!-- 第三阶段：我的订单与 CPS 分佣看板 -->
        <section v-else-if="rightView === 'orders'" class="panel orders-panel">
          <div class="posts-panel-head">
            <div>
              <p class="eyebrow">COMMERCE &amp; CPS</p>
              <h3>我的预订清单与 CPS 分佣看板</h3>
              <p>展示由行程规划一键生成的机酒占位预订及 CPS 归因预估佣金（占位演示，未对接真实外部平台）。</p>
            </div>
            <button type="button" @click="router.push('/chat')">＋ 去规划新行程</button>
          </div>

          <div class="orders-kpi-grid">
            <div class="orders-kpi">
              <span>预订总数</span>
              <b>{{ commercialSummary?.totalCount || 0 }}</b>
              <small>待确认 {{ commercialSummary?.pendingCount || 0 }} · 已取消 {{ commercialSummary?.cancelledCount || 0 }}</small>
            </div>
            <div class="orders-kpi">
              <span>已确认履约</span>
              <b>{{ commercialSummary?.confirmedCount || 0 }}</b>
              <small>已计入履约闭环</small>
            </div>
            <div class="orders-kpi">
              <span>已确认 GMV</span>
              <b>¥{{ (commercialSummary?.confirmedGmv || 0).toLocaleString() }}</b>
              <small>占位模拟成交额</small>
            </div>
            <div class="orders-kpi orders-kpi--accent">
              <span>预估 CPS 佣金</span>
              <b>¥{{ (commercialSummary?.estimatedCommission || 0).toLocaleString() }}</b>
              <small>酒店 6% · 门票 5% · 机票 1.2%</small>
            </div>
          </div>

          <div v-loading="ordersLoading" class="orders-list">
            <article v-for="item in (commercialSummary?.bookings || [])" :key="item.bookingNo" class="order-card">
              <div class="order-card__top">
                <div class="order-card__tags">
                  <span class="order-cat">{{ bookingCategoryLabel(item.category) }}</span>
                  <span class="order-dest">{{ item.destination }}</span>
                  <span v-if="item.startDate" class="order-date">{{ item.startDate }} ~ {{ item.endDate || item.startDate }}</span>
                </div>
                <span class="order-status" :class="'st--' + item.status.toLowerCase()">{{ bookingStatusLabel(item.status) }}</span>
              </div>
              <h4>{{ item.title }}</h4>
              <p class="order-sub">{{ item.subtitle || item.providerCode }}</p>
              <div class="order-meta">
                <span>订单号 {{ item.bookingNo }}</span>
                <span>归因码 {{ item.attributionCode }}</span>
                <span>费率 {{ (item.commissionRate * 100).toFixed(1) }}% · 预估佣金 ¥{{ item.estimatedCommission }}</span>
              </div>
              <div class="order-card__foot">
                <strong>¥{{ item.amount.toLocaleString() }}</strong>
                <div class="order-actions">
                  <button v-if="item.status === 'PENDING'" type="button" class="order-btn order-btn--primary" @click="handleConfirmOrder(item.bookingNo)">模拟确认</button>
                  <button v-if="item.status !== 'CANCELLED'" type="button" class="order-btn" @click="handleCancelOrder(item.bookingNo)">取消</button>
                </div>
              </div>
            </article>
            <p v-if="!ordersLoading && !(commercialSummary?.bookings?.length)" class="profile-post-empty">
              还没有预订清单记录，前往「Agent 旅行规划」点击「一键加入预订清单」即可体验完整商业闭环。
            </p>
          </div>
        </section>

        <!-- 旅行偏好 -->
        <section v-else class="panel prefs">
        <section class="palette-panel">
          <button class="palette-trigger" type="button" @click="paletteOpen ? closePalette() : paletteOpen = true">🎨 系统调色板 <span>{{ paletteOpen ? '收起' : '编辑' }}</span></button>
          <div v-if="paletteOpen" class="palette-popover">
            <div class="palette-popover-head"><div><h3>系统调色板</h3><p class="palette-hint">调整会即时预览，保存后写入数据库。</p></div><button type="button" class="palette-close" @click="closePalette">×</button></div>
            <div class="palette-fields">
              <label>前景色<div class="palette-input"><input type="color" v-model="systemPalette.fg" @input="applySystemPalette" /><input v-model="systemPalette.fg" maxlength="7" @input="applySystemPalette" /></div></label>
              <label>背景色<div class="palette-input"><input type="color" v-model="systemPalette.bg" @input="applySystemPalette" /><input v-model="systemPalette.bg" maxlength="7" @input="applySystemPalette" /></div></label>
              <label>强调色<div class="palette-input"><input type="color" v-model="systemPalette.accent" @input="applySystemPalette" /><input v-model="systemPalette.accent" maxlength="7" @input="applySystemPalette" /></div></label>
              <label>暖色点缀<div class="palette-input"><input type="color" v-model="systemPalette.highlight" @input="applySystemPalette" /><input v-model="systemPalette.highlight" maxlength="7" @input="applySystemPalette" /></div></label>
            </div>
            <div class="palette-presets"><button v-for="p in palettePresets" :key="p.name" type="button" :style="{ background: p.bg, color: p.fg, borderColor: p.accent }" @click="Object.assign(systemPalette, p); applySystemPalette()">{{ p.name }}</button></div>
            <button class="palette-save" type="button" :disabled="saving" @click="saveSystemPalette">{{ saving ? '保存中…' : '保存主题' }}</button>
          </div>
        </section>
        <div class="prefs-head">
          <h3>旅行偏好</h3>
          <span class="badge">为 {{ form.name || '旅人' }} 定制</span>
        </div>
        <el-form label-position="top">
          <h5 class="sec-label">行程</h5>
          <div class="grid2">
            <el-form-item label="想去的目的地（逗号分隔）">
              <el-input v-model="form.favoriteDestinations" placeholder="如：冰岛,日本,新疆" />
            </el-form-item>
            <el-form-item label="常驻出发城市"><el-input v-model="form.defaultDepartureCity" placeholder="如：上海" /></el-form-item>
          </div>
          <div class="grid3">
            <el-form-item label="默认天数"><el-input-number v-model="form.defaultDays" :min="1" :max="30" /></el-form-item>
            <el-form-item label="默认预算"><el-input-number v-model="form.defaultBudget" :min="0" :step="1000" /></el-form-item>
            <el-form-item label="同行人数"><el-input-number v-model="form.defaultTravelers" :min="1" /></el-form-item>
          </div>
          <div class="grid3">
            <el-form-item label="偏好季节">
              <el-select v-model="form.preferredSeason">
                <el-option v-for="s in ['不限','春季','夏季','秋季','冬季']" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item label="出行类型">
              <el-select v-model="form.preferredTripType">
                <el-option v-for="s in ['深度体验','休闲度假','户外探险','亲子游','文化之旅','美食之旅']" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item label="预算档位">
              <el-select v-model="form.budgetLevel">
                <el-option v-for="s in ['经济','舒适','豪华','奢享']" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
          </div>
          <div class="grid2">
            <el-form-item label="旅行风格">
              <el-select v-model="form.travelStyle" placeholder="选择旅行风格">
                <el-option v-for="s in styleOptions" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item label="兴趣标签（可多选）">
              <el-select v-model="interests" multiple collapse-tags placeholder="选择兴趣">
                <el-option v-for="s in interestOptions" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="偏好的景点类型（可多选）">
            <el-select v-model="attractionTypes" multiple collapse-tags placeholder="选择景点类型">
              <el-option v-for="s in attractionOptions" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-checkbox v-model="form.preferFreeAttractions">优先免费景点</el-checkbox>
          </el-form-item>

          <h5 class="sec-label">餐饮</h5>
          <div class="grid3">
            <el-form-item label="饮食要求（可多选）">
              <el-select v-model="dietaryRequirements" multiple collapse-tags placeholder="选择饮食要求">
                <el-option v-for="s in dietOptions" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item label="偏好的菜系（可多选）">
              <el-select v-model="preferredCuisines" multiple collapse-tags placeholder="选择菜系">
                <el-option v-for="s in cuisineOptions" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item label="辣度">
              <el-select v-model="form.spicyLevel">
                <el-option v-for="n in [0,1,2,3]" :key="n" :label="['不辣','微辣','中辣','重辣'][n]" :value="n" />
              </el-select>
            </el-form-item>
          </div>

          <h5 class="sec-label">住宿与交通</h5>
          <div class="grid3">
            <el-form-item label="住宿类型（可多选）">
              <el-select v-model="accommodationType" multiple collapse-tags placeholder="选择住宿类型">
                <el-option v-for="s in accommodationOptions" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item label="最低星级"><el-input-number v-model="form.hotelStarMin" :min="1" :max="5" /></el-form-item>
            <el-form-item label="交通偏好（可多选）">
              <el-select v-model="transportationPreference" multiple collapse-tags placeholder="选择交通方式">
                <el-option v-for="s in transportOptions" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
          </div>

          <h5 class="sec-label">通知</h5>
          <div class="grid3">
            <el-form-item label="出行前提醒（天）"><el-input-number v-model="form.notifyBeforeTripDays" :min="0" :max="30" /></el-form-item>
            <el-form-item><el-checkbox v-model="form.notifyWeatherAlert">天气预警提醒</el-checkbox></el-form-item>
            <el-form-item><el-checkbox v-model="form.notifyPriceChange">价格变动提醒</el-checkbox></el-form-item>
          </div>
        </el-form>
        </section>
      </div>
    </div>

  </main>
</template>

<style scoped lang="scss">
.shell { 
  padding: 16px 20px; 
  height: 100vh; 
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.profile-head { 
  flex-shrink: 0; 
  margin-bottom: 16px;
}
.palette-panel { position: relative; margin-bottom: 24px; padding-bottom: 20px; border-bottom: 1px solid var(--line); }
.palette-trigger { display: flex; align-items: center; justify-content: space-between; width: 100%; padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--card); color: var(--ink); font-weight: 800; cursor: pointer; }
.palette-trigger span { color: var(--ink-2); font-size: 12px; font-weight: 600; }
.palette-popover { margin-top: 10px; padding: 16px; border: 1px solid var(--line); border-radius: 12px; background: var(--card); box-shadow: var(--shadow-soft); }
.palette-popover-head { display: flex; align-items: flex-start; justify-content: space-between; }
.palette-popover-head h3 { margin: 0; font-size: 16px; }
.palette-close { border: 0; background: transparent; color: var(--ink-2); font-size: 22px; line-height: 1; cursor: pointer; }
.palette-hint { margin-top: 5px; color: var(--ink-2); font-size: 12px; }
.palette-fields { display: flex; gap: 14px; flex-wrap: wrap; margin: 16px 0 12px; }
.palette-fields label { display: grid; gap: 7px; color: var(--ink-2); font-size: 12px; font-weight: 700; }
.palette-input { display: flex; align-items: center; gap: 7px; }
.palette-input input[type='color'] { width: 34px; height: 28px; padding: 0; border: 1px solid var(--line); border-radius: 6px; cursor: pointer; }
.palette-input input[type='text'], .palette-input input:not([type]) { width: 92px; height: 28px; padding: 0 7px; border: 1px solid var(--line); border-radius: 6px; background: var(--card); color: var(--ink); font: 12px ui-monospace, monospace; }
.palette-presets { display: flex; gap: 8px; flex-wrap: wrap; }
.palette-presets button { padding: 7px 12px; border: 1px solid; border-radius: 8px; font-size: 12px; font-weight: 700; cursor: pointer; }
.palette-save { width: 100%; margin-top: 14px; padding: 10px; border: 0; border-radius: 8px; background: var(--forest); color: #fff; font-weight: 800; cursor: pointer; }
.palette-save:disabled { opacity: .6; cursor: wait; }
.eyebrow { color: var(--sunset); font-size: 10px; font-weight: 800; letter-spacing: 0.18em; margin: 0 0 8px; }
.profile-head h1 { font: 34px "DM Serif Display", "Noto Sans SC"; color: var(--ink); margin: 0; }
.sub { color: #687873; font-size: 14px; margin: 10px 0 0; }

.layout {
  width: calc(100% - 40px);
  max-width: 1160px;
  margin: 0 auto;
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 24px;
  align-items: stretch;
  overflow: hidden;
}

/* 右侧自己的工作区：收起时只改变右侧，不会挤动左侧资料卡。 */
.right-panel-column {
  min-width: 0;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
}

.right-panel-toolbar {
  flex: 0 0 auto;
  min-height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 9px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: color-mix(in srgb, var(--card) 90%, var(--roam-soft));
  box-shadow: var(--shadow-soft);
}

.right-panel-tabs { min-width: 0; display: flex; align-items: center; gap: 4px; }
.right-panel-tab {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  padding: 9px 11px;
  border: 0;
  border-radius: 11px;
  background: transparent;
  color: var(--ink-2);
  font: inherit;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
  transition: background .18s ease, color .18s ease, transform .18s ease;
}
.right-panel-tab:hover { color: var(--forest); background: var(--roam-soft); transform: translateY(-1px); }
.right-panel-tab.active { background: var(--forest); color: #fff; box-shadow: 0 5px 14px color-mix(in srgb, var(--forest) 18%, transparent); }
.right-panel-tab small { min-width: 18px; padding: 2px 5px; border-radius: 999px; background: color-mix(in srgb, currentColor 14%, transparent); font-size: 10px; text-align: center; }
.tab-mark { font-size: 13px; line-height: 1; }
.right-panel-collapse {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--card);
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 800;
  cursor: pointer;
}
.right-panel-collapse:hover { border-color: var(--forest); color: var(--forest); background: var(--roam-soft); }
.right-panel-collapse b { font-size: 14px; line-height: 1; }

.right-panel-collapsed-card {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  min-height: 128px;
  padding: 24px;
  border: 1px solid var(--line);
  border-radius: 24px;
  background: var(--card);
  box-shadow: var(--shadow-soft);
}
.right-panel-collapsed-card h3 { margin: 0; color: var(--ink); font-size: 22px; }
.right-panel-collapsed-card p:not(.eyebrow) { margin-top: 7px; color: var(--ink-2); font-size: 12px; }
.right-panel-collapsed-card > button {
  flex: 0 0 auto;
  padding: 10px 13px;
  border: 0;
  border-radius: 10px;
  background: var(--forest);
  color: #fff;
  font-size: 11px;
  font-weight: 800;
  cursor: pointer;
}
.right-panel-collapsed-card > button:hover { background: var(--forest-deep); transform: translateY(-1px); }

.posts-panel {
  min-width: 0;
  min-height: 0;
  height: auto;
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 22px;
  border: 1px solid var(--line);
  border-radius: 24px;
  background: var(--card);
  box-shadow: var(--shadow-soft);
}
.posts-panel-head { flex: 0 0 auto; display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; padding-bottom: 18px; border-bottom: 1px solid var(--line); }
.posts-panel-head h3 { margin: 0; font-size: 22px; }
.posts-panel-head p:not(.eyebrow) { margin: 6px 0 0; color: var(--ink-2); font-size: 12px; }
.posts-panel-head button { border: 0; border-radius: 9px; padding: 10px 13px; background: var(--forest); color: #fff; font-weight: 800; cursor: pointer; }
.posts-panel-head button:hover { background: var(--forest-deep); transform: translateY(-1px); }
.profile-post-grid {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-auto-rows: max-content;
  align-content: start;
  align-items: start;
  gap: 14px;
  padding: 18px 8px 8px 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--forest) 32%, var(--line)) transparent;
}
.profile-post-grid::-webkit-scrollbar { width: 8px; }
.profile-post-grid::-webkit-scrollbar-track { background: transparent; }
.profile-post-grid::-webkit-scrollbar-thumb { border: 2px solid transparent; border-radius: 999px; background: color-mix(in srgb, var(--forest) 32%, var(--line)); background-clip: padding-box; }
.profile-post-grid::-webkit-scrollbar-thumb:hover { background: var(--forest); background-clip: padding-box; }
.profile-post { display: block; height: auto; min-height: 0; overflow: hidden; border: 1px solid var(--line); border-radius: 16px; background: var(--card); cursor: pointer; transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease; }
.profile-post:hover { transform: translateY(-2px); box-shadow: var(--shadow-lift); }
.profile-post:focus-visible { outline: 2px solid var(--forest); outline-offset: 3px; }
.profile-post-cover { position: relative; aspect-ratio: 4/3; overflow: hidden; background: var(--wash); }
.profile-post-cover img { width: 100%; height: 100%; object-fit: cover; }
.profile-post-cover-placeholder { width: 100%; height: 100%; display: grid; place-items: center; padding: 20px; background: linear-gradient(135deg, var(--forest), var(--roam)); color: #fff; font: 20px 'DM Serif Display', serif; text-align: center; }
.profile-post-save { position: absolute; right: 12px; top: 10px; z-index: 2; width: 30px; height: 30px; display: grid; place-items: center; border: 0; border-radius: 50%; background: #ffffffd9; color: var(--ink); font-size: 20px; cursor: pointer; }
.profile-post-save:hover { background: #fff; color: var(--forest); }
.profile-post-open { position: absolute; left: 0; right: 0; bottom: 0; z-index: 1; padding: 18px 14px 12px; background: linear-gradient(to top, rgba(0,0,0,.55), transparent); color: #fff; font-size: 13px; font-weight: 700; opacity: 0; transition: opacity .18s ease; text-align: center; }
.profile-post:hover .profile-post-open, .profile-post:focus-visible .profile-post-open { opacity: 1; }
.profile-post-body { display: block; min-height: 126px; padding: 13px 14px 15px; background: var(--card); visibility: visible; opacity: 1; }
.profile-post-author { display: flex; align-items: center; min-width: 0; gap: 7px; color: var(--ink-2); font-size: 11px; white-space: nowrap; }
.post-avatar { display: grid; place-items: center; width: 23px; height: 23px; flex: 0 0 23px; overflow: hidden; border-radius: 50%; background: var(--roam-soft); color: var(--forest); font-weight: 800; }
.post-avatar img { width: 100%; height: 100%; object-fit: cover; }
.profile-post-author > span:not(.post-avatar):first-of-type { max-width: 9em; overflow: hidden; color: var(--ink); font-weight: 800; text-overflow: ellipsis; }
.post-dot { color: var(--ink-3); }
.profile-post-body h4 { display: -webkit-box; overflow: hidden; margin: 10px 0; color: var(--ink); font-size: 15px; line-height: 1.45; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.profile-post-tags { display: flex; flex-wrap: wrap; gap: 6px; min-height: 22px; margin-bottom: 12px; }
.profile-post-tags span { padding: 4px 8px; border-radius: 6px; background: var(--roam-soft); color: var(--forest); font-size: 10px; font-weight: 700; }
.profile-post-footer { display: flex; align-items: center; justify-content: space-between; gap: 7px; padding-top: 10px; border-top: 1px solid var(--line); color: var(--ink-3); font-size: 10px; }
.profile-post-empty { grid-column: 1 / -1; padding: 50px 10px; color: var(--ink-3); text-align: center; }

/* 左：用户资料卡片 - 固定高度，内部滚动 */
.profile-card {
  min-width: 0;
  min-height: 0;
  height: 100%;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 24px;
  box-shadow: 0 2px 4px rgba(22,78,66,.05);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 卡片头 - 固定 */
.profile-card .card-hero { flex-shrink: 0; }

/* 卡片内容区 - 可滚动 */
.profile-card .card-body {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

/* 右：旅行偏好面板 - 固定高度，内部滚动 */
.panel.prefs {
  min-width: 0;
  min-height: 0;
  height: auto;
  flex: 1 1 auto;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 24px;
  padding: 20px 24px;
  box-shadow: 0 2px 4px rgba(22,78,66,.05);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 面板头部 - 固定 */
.panel.prefs > .prefs-head,
.panel.prefs > .palette-panel { flex-shrink: 0; }

/* 面板表单 - 可滚动 */
.panel.prefs > .el-form,
.panel.prefs > form {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

.card-hero { padding: 30px 26px 22px; text-align: center; background: radial-gradient(160% 120% at 50% 10%, var(--roam-soft) 0%, var(--card) 55%); }
.avatar-ring { width: 130px; height: 130px; margin: 0 auto 14px; border-radius: 50%; padding: 5px; background: var(--forest); box-shadow: 0 8px 28px rgba(22,78,66,.22), 0 0 0 10px rgba(79,143,120,.12); }
.avatar-uploader { position: relative; width: 100%; height: 100%; border-radius: 50%; overflow: hidden; background: #fff; cursor: pointer; transition: .3s; }
.avatar-uploader img { width: 100%; height: 100%; object-fit: cover; }
.avatar-uploader .ph { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; background: var(--ink); color: #fff; font-weight: 800; font-size: 42px; }
.mask { position: absolute; inset: auto 0 0 0; background: rgba(0,0,0,.5); color: #fff; font-size: 12px; padding: 8px 0; opacity: 0; transition: .2s; text-align: center; }
.avatar-uploader:hover .mask, .avatar-uploader.drag-over .mask { opacity: 1; }
.card-hero h2 { color: var(--ink); margin: 4px 0 2px; font-size: 22px; }
.card-hero .email { color: #8a9792; font-size: 13px; margin: 0 0 18px; }
.email-unbound { color: var(--sunset); font-weight: 600; }
.preview-box { text-align: center; margin: -6px 0 12px; }
.preview-box img { width: 84px; height: 84px; border-radius: 50%; object-fit: cover; border: 2px solid var(--roam); }
/* 卡片内滚动区域样式 */
.card-body,
.panel.prefs > .el-form {
  scrollbar-width: thin;
  scrollbar-color: #d5d8dc transparent;
}

.card-body::-webkit-scrollbar,
.panel.prefs > .el-form::-webkit-scrollbar {
  width: 6px;
}

.card-body::-webkit-scrollbar-thumb,
.panel.prefs > .el-form::-webkit-scrollbar-thumb {
  background: #d5d8dc;
  border-radius: 3px;
}

/* 卡片底部保存区 */
.card-footer {
  flex-shrink: 0;
  padding: 16px 24px;
  border-top: 1px solid var(--line);
  background: linear-gradient(180deg, transparent 0%, rgba(0,0,0,0.02) 100%);
}

/* 联系方式区（邮箱 + 手机号） */
.contact-info {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  margin: 0 0 16px;
}

.contact-info .email {
  color: #8a9792;
  font-size: 13px;
  margin: 0;
}

.contact-info .phone {
  color: #6b7280;
  font-size: 12px;
  margin: 0;
}

.contact-info .email-unbound {
  color: var(--sunset);
  font-weight: 600;
}

.card-body::-webkit-scrollbar-track,
.panel.prefs > .el-form::-webkit-scrollbar-track {
  background: transparent;
}

.row-btn { margin-top: 8px; display: flex; justify-content: center; gap: 8px; }

.stat-row { display: flex; border-top: 1px solid var(--line); padding-top: 18px; }
.stat { flex: 1; text-align: center; }
.stat + .stat { border-left: 1px solid var(--line); }
.stat b { display: block; color: var(--ink); font-size: 18px; font-weight: 800; }
.stat span { color: #8a9792; font-size: 11px; margin-top: 2px; display: block; }

.card-form { padding: 20px 24px 24px; }
.card-security { padding: 0 24px 20px; border-top: 1px solid var(--line); }
.sec-label { color: var(--sunset); font-size: 11px; font-weight: 800; letter-spacing: 0.12em; margin: 0 0 10px; text-transform: uppercase; }
.basic { text-align: left; }

/* 绑定状态 */
.bind-status { display: flex; align-items: center; justify-content: space-between; padding: 10px 0; }
.bind-info { display: flex; flex-direction: column; }
.bind-label { font-size: 11px; color: var(--ink-3); font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; }
.bind-value { font-size: 14px; font-weight: 600; margin-top: 2px; }
.bind-value.bound { color: var(--ink); }
.bind-value.unbound { color: var(--sunset); }

/* 操作按钮行 */
.bind-actions-row { display: flex; gap: 8px; margin-bottom: 12px; }

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 13px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  color: var(--ink);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  font-family: inherit;
}
.action-btn:hover {
  border-color: var(--forest);
  color: var(--forest);
  background: var(--roam-soft);
}
.action-btn.primary {
  background: var(--forest);
  border-color: var(--forest);
  color: #fff;
}
.action-btn.primary:hover {
  background: var(--forest-deep);
  border-color: var(--forest-deep);
  color: #fff;
}
.action-btn.danger {
  border-color: var(--line);
  color: var(--ink-3);
}
.action-btn.danger:hover {
  border-color: #e74c3c;
  color: #e74c3c;
  background: rgba(231, 76, 60, 0.05);
}
.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 绑定表单 */
.bind-form { padding: 14px; background: var(--wash); border-radius: 12px; position: relative; }
.bind-form-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.bind-form-title { font-size: 13px; font-weight: 700; color: var(--ink); }
.bind-form-close { background: none; border: none; color: var(--ink-3); font-size: 14px; cursor: pointer; padding: 4px 8px; border-radius: 6px; }
.bind-form-close:hover { background: var(--line); color: var(--ink); }
.form-input-sm { display: block; width: 100%; padding: 10px 12px; border: 1px solid var(--line); border-radius: 8px; background: #fff; font-size: 13px; box-sizing: border-box; margin-bottom: 8px; }
.form-input-sm:focus { outline: none; border-color: var(--forest); }
.bind-code-row { display: flex; gap: 8px; margin-bottom: 8px; }
.form-input-sm.code { flex: 1; }
.code-btn-sm { padding: 10px 12px; border: 1px solid var(--forest); border-radius: 8px; background: transparent; color: var(--forest); font-size: 12px; font-weight: 600; cursor: pointer; white-space: nowrap; }
.code-btn-sm:disabled { opacity: 0.5; cursor: not-allowed; }
.bind-submit { width: 100%; padding: 10px; border: none; border-radius: 8px; background: var(--forest); color: #fff; font-weight: 700; font-size: 13px; cursor: pointer; }
.bind-submit:disabled { opacity: 0.6; cursor: not-allowed; }
.bind-hint { font-size: 11px; color: var(--ink-3); margin-top: 8px; text-align: center; }

/* 退出按钮 */
.logout-btn { width: 100%; margin-top: 16px; padding: 10px; border: 1px solid var(--line); border-radius: 10px; background: transparent; color: var(--ink-2); font-size: 13px; font-weight: 500; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 6px; transition: all 0.15s; }
.logout-btn:hover { background: var(--wash); border-color: var(--ink-3); color: var(--ink); }


.prefs-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.prefs-head h3 { color: var(--ink); margin: 0; font-size: 20px; }
.prefs .badge { background: var(--roam-soft); color: var(--forest); font-size: 11px; font-weight: 700; padding: 4px 12px; border-radius: 24px; }
.prefs .sec-label { margin: 20px 0 4px; }
.prefs .sec-label:first-of-type { margin-top: 6px; }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.grid3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 14px; }


.save-btn { 
  border: 0; 
  background: var(--forest); 
  color: #fff; 
  font-weight: 800; 
  padding: 12px 28px; 
  border-radius: 12px; 
  cursor: pointer; 
  box-shadow: 0 4px 12px rgba(22,78,66,.15);
  width: 100%;
  transition: all .15s;
  
  &:hover { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(22,78,66,.2); }
}
.save-btn:disabled { opacity: .65; cursor: not-allowed; }

@media (max-width: 800px) {
  .shell { height: auto; min-height: 100vh; overflow: auto; }
  .layout { width: 100%; flex: none; grid-template-columns: 1fr; overflow: visible; }
  .right-panel-column { height: 720px; overflow: visible; }
  .right-panel-column.is-collapsed { height: auto; }
  .grid2, .grid3 { grid-template-columns: 1fr; }
  .orders-kpi-grid { grid-template-columns: repeat(2, 1fr); }
}

/* 第三阶段：我的订单与 CPS 分佣看板 */
.orders-panel {
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 22px;
  border: 1px solid var(--line);
  border-radius: 24px;
  background: var(--card);
  box-shadow: var(--shadow-soft);
}
.orders-kpi-grid {
  flex: 0 0 auto;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin: 16px 0 14px;
}
.orders-kpi {
  padding: 12px 14px;
  border: 1px solid #e5e9f0;
  border-radius: 14px;
  background: #f8fafd;
  span { display: block; font-size: 11px; color: #5f6368; font-weight: 600; }
  b { display: block; margin: 4px 0 2px; font-size: 20px; color: #1f2329; }
  small { display: block; font-size: 10.5px; color: #80868b; }
}
.orders-kpi--accent {
  background: #f2f7ff;
  border-color: #c2d7fa;
  b { color: #0b57d0; }
}
.orders-list {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 4px;
}
.order-card {
  padding: 14px 16px;
  border: 1px solid #e5e9f0;
  border-radius: 14px;
  background: #ffffff;
  h4 { margin: 8px 0 4px; font-size: 15px; color: #1f2329; }
}
.order-card__top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.order-card__tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.order-cat {
  font-size: 11px;
  font-weight: 700;
  color: #0b57d0;
  background: #e8f0fe;
  padding: 2px 8px;
  border-radius: 999px;
}
.order-dest, .order-date {
  font-size: 11.5px;
  color: #5f6368;
}
.order-status {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 9px;
  border-radius: 999px;
  &.st--pending { background: #fef7e0; color: #b06000; }
  &.st--confirmed { background: #e6f4ea; color: #137333; }
  &.st--cancelled { background: #f1f3f4; color: #5f6368; }
}
.order-sub {
  margin: 0 0 8px;
  font-size: 12.5px;
  color: #5f6368;
}
.order-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 11px;
  color: #80868b;
  padding-bottom: 10px;
  border-bottom: 1px solid #eef1f5;
}
.order-card__foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
  strong { font-size: 16px; color: #0b57d0; }
}
.order-actions {
  display: flex;
  gap: 8px;
}
.order-btn {
  border: 1px solid #dadce0;
  background: #ffffff;
  color: #3c4043;
  font-size: 12px;
  font-weight: 600;
  padding: 5px 12px;
  border-radius: 999px;
  cursor: pointer;
  &:hover { background: #f0f4f9; }
}
.order-btn--primary {
  border-color: #0b57d0;
  background: #0b57d0;
  color: #ffffff;
  &:hover { background: #0842a0; }
}
</style>

<style scoped>
.shell {
  position: relative;
  isolation: isolate;
  padding: 26px 28px 34px;
  background:
    radial-gradient(circle at 88% 0%, color-mix(in srgb, var(--roam) 11%, transparent), transparent 25rem),
    radial-gradient(circle at 7% 80%, color-mix(in srgb, var(--sunset) 7%, transparent), transparent 22rem),
    var(--paper);
}

.profile-head {
  width: min(1160px, calc(100% - 40px));
  margin: 0 auto 18px;
  padding: 22px 26px 18px;
  border: 1px solid color-mix(in srgb, var(--forest) 12%, var(--line));
  border-radius: 22px;
  background: linear-gradient(118deg, color-mix(in srgb, var(--card) 89%, var(--sunset) 11%), color-mix(in srgb, var(--card) 93%, var(--roam) 7%));
  box-shadow: var(--shadow-soft), inset 0 1px 0 rgba(255,255,255,.72);
}

.profile-head h1 { letter-spacing: -.025em; }
.layout { gap: 20px; }
.profile-card, .panel.posts-panel, .panel.prefs { border-color: color-mix(in srgb, var(--forest) 12%, var(--line)); box-shadow: 0 8px 24px color-mix(in srgb, var(--forest) 7%, transparent); }
.card-hero { background: radial-gradient(140% 120% at 50% 0%, color-mix(in srgb, var(--roam-soft) 86%, var(--sunset-soft)), var(--card) 60%); }
.avatar-ring { box-shadow: 0 10px 28px color-mix(in srgb, var(--forest) 22%, transparent), 0 0 0 10px color-mix(in srgb, var(--roam) 12%, transparent); }
.right-panel-toolbar { background: linear-gradient(100deg, color-mix(in srgb, var(--card) 88%, var(--roam-soft)), color-mix(in srgb, var(--card) 90%, var(--sunset-soft))); }
.posts-panel-head button, .save-btn { box-shadow: 0 8px 16px color-mix(in srgb, var(--forest) 14%, transparent); }
.profile-post { border-color: color-mix(in srgb, var(--forest) 12%, var(--line)); box-shadow: 0 2px 0 color-mix(in srgb, var(--forest) 3%, transparent); }
.profile-post:hover { box-shadow: 0 16px 30px color-mix(in srgb, var(--forest) 13%, transparent); }
.profile-post-tags span { border: 1px solid color-mix(in srgb, var(--forest) 8%, transparent); }

@media (max-width: 700px) {
  .shell { padding: 18px 14px 28px; }
  .profile-head { width: 100%; padding: 20px 18px 17px; }
}
</style>
