<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useContentTabsStore } from '@/stores/contentTabs'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listJourneys, addJourney, updateJourney, deleteJourney,
  saveJourneyPoints, saveJourneyImages, uploadImage, geocodeAddress,
  type JourneyDetail, type Journey, type JourneyPoint, type JourneyImage
} from '@/api/user'
import { formatCost } from '@/utils/planValues'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const list = ref<JourneyDetail[]>([])
const selectedJourney = ref<JourneyDetail | null>(null)
const journeyStudioEditing = ref(false)

// 新增/编辑旅程
const journeyDialog = ref(false)
const editingJourney = ref<Journey | null>(null)
const jform = ref<Journey>({ destination: '' })

// 详情（含景点 + 图片）
const detailDialog = ref(false)
const current = ref<JourneyDetail | null>(null)
const newPoint = reactive<JourneyPoint>({ name: '', latitude: '', longitude: '', visitDate: '', description: '' })

// 图片输入模式
const imageInputMode = ref<'url' | 'upload'>('url')
const imageUploading = ref(false)
const newImage = reactive<{ imageUrl: string; caption: string }>({ imageUrl: '', caption: '' })

// 粘贴 URL 自动入列，无需手动点 ＋
watch(
  () => newImage.imageUrl,
  (val) => {
    if (!val || !current.value) return
    // 自动补全 https:// 前缀
    let url = val.trim()
    if (url && !/^https?:\/\//.test(url)) {
      url = 'https://' + url
    }
    current.value.images.push({ imageUrl: url, caption: newImage.caption || '' })
    newImage.imageUrl = ''
    newImage.caption = ''
  }
)

const travelTypes = ['亲子', '情侣', '独自', '好友', '家庭', '商务', '跟团', '自驾']
const companionsOptions = ['独自一人', '伴侣', '朋友', '家人', '同事']
const ratingOptions = [5, 4, 3, 2, 1]

async function load() {
  loading.value = true
  try {
    const res = await listJourneys()
    // 兼容扁平 JourneyPO[]（旧/脏数据）与包装 TrueDetail[]（新结构），剔除空项避免渲染崩溃注入
    list.value = ((res.data as any[]) || [])
      .filter((d: any) => d && !!(d.journey || d.destination || d.id))
      .map((d: any) => {
        // 若已是 { journey, points, images } 结构，直接补齐子数组
        if (d.journey) {
          return Object.assign({}, d, { points: d.points || [], images: d.images || [] })
        }
        // 扁平 PO → 包装成 JourneyDetail
        return { journey: d, points: d.points || [], images: d.images || [] }
      })
  } catch (e) {
    console.error(e)
    ElMessage.error('加载旅程失败')
  } finally {
    loading.value = false
  }
}

function openAdd() {
  editingJourney.value = null
  jform.value = { destination: '', travelType: '独自', status: 1 }
  journeyDialog.value = true
}
function openEdit(d: JourneyDetail) {
  editingJourney.value = d.journey
  jform.value = { ...d.journey }
  journeyDialog.value = true
}
function openJourneyStudio(d: JourneyDetail) { selectedJourney.value = { ...d, journey: { ...d.journey }, points: [...(d.points || [])], images: [...(d.images || [])] }; journeyStudioEditing.value = false }
/** 点击旅程卡片 → 中间卡片标签查看（与发现灵感/灵感目的地入口统一） */
const contentTabs = useContentTabsStore()
function openJourneyTab(d: JourneyDetail) {
  contentTabs.open({
    kind: 'journey',
    title: `我的旅程-${d.journey.destination || ('旅程 ' + d.journey.id)}`,
    data: {
      keyId: d.journey.id,
      sourceType: 'journey',
      journeyId: d.journey.id,
      destination: d.journey.destination || '',
      content: d.journey.summary || d.journey.destination || '暂无摘要',
      image: d.images?.[0]?.imageUrl || '',
      bestSeason: d.journey.travelType || ''
    }
  })
  router.push('/card-detail')
}
function closeJourneyStudio() { selectedJourney.value = null; journeyStudioEditing.value = false }
async function saveJourneyStudio() {
  if (!selectedJourney.value?.journey.id) return
  await updateJourney(selectedJourney.value.journey.id, selectedJourney.value.journey)
  journeyStudioEditing.value = false
  await load()
  ElMessage.success('已保存')
}
async function saveJourney() {
  if (!jform.value.destination) { ElMessage.warning('请填写目的地'); return }
  try {
    if (editingJourney.value?.id) {
      await updateJourney(editingJourney.value.id, jform.value)
    } else {
      await addJourney(jform.value)
    }
    journeyDialog.value = false
    ElMessage.success('已保存')
    await load()
  } catch (e) { console.error(e); ElMessage.error('保存失败') }
}

async function openDetail(d: JourneyDetail) {
  current.value = { ...d, points: [...(d.points || [])], images: [...(d.images || [])] }
  newPoint.name = ''; newPoint.latitude = ''; newPoint.longitude = ''; newPoint.visitDate = ''; newPoint.description = ''
  newImage.imageUrl = ''; newImage.caption = ''
  imageInputMode.value = 'url'
  imageUploading.value = false
  detailDialog.value = true
}

async function remove(d: JourneyDetail) {
  try {
    await ElMessageBox.confirm(`确定删除「${d.journey.destination}」这段旅程吗？`, '删除旅程', { type: 'warning' })
    if (d.journey.id) await deleteJourney(d.journey.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    if (e !== 'cancel') console.error(e)
  }
}

// 景点名称变化时自动获取经纬度（防抖）
let geoTimer: ReturnType<typeof setTimeout> | null = null
watch(
  () => newPoint.name,
  async (name) => {
    if (!name || newPoint.latitude || newPoint.longitude) return
    if (geoTimer) clearTimeout(geoTimer)
    geoTimer = setTimeout(async () => {
      if (!name) return
      try {
        const res = await geocodeAddress(name)
        if (res?.data?.latitude && res?.data?.longitude) {
          newPoint.latitude = String(res.data.latitude)
          newPoint.longitude = String(res.data.longitude)
        }
      } catch { /* 静默失败，让用户手动填 */ }
    }, 800)
  }
)

async function addPoint() {
  if (!current.value) return
  if (!newPoint.name) { ElMessage.warning('请填写景点名称'); return }
  // 坐标为空时尝试获取
  if (!newPoint.latitude && !newPoint.longitude) {
    try {
      const res = await geocodeAddress(newPoint.name)
      if (res?.data?.latitude && res?.data?.longitude) {
        newPoint.latitude = String(res.data.latitude)
        newPoint.longitude = String(res.data.longitude)
      }
    } catch { /* 静默失败 */ }
  }
  current.value.points.push({ ...newPoint } as JourneyPoint)
  newPoint.name = ''; newPoint.latitude = ''; newPoint.longitude = ''; newPoint.visitDate = ''; newPoint.description = ''
}
function removePoint(i: number) {
  current.value?.points.splice(i, 1)
}
// addImg 保留为空壳，实际入列由 watcher 自动完成，这里做兜底
function addImg() {
  if (!current.value || !newImage.imageUrl) return
  // 触发 watcher 的同一逻辑（enter 键兜底）
  let url = newImage.imageUrl.trim()
  if (url && !/^https?:\/\//.test(url)) {
    url = 'https://' + url
  }
  current.value.images.push({ imageUrl: url, caption: newImage.caption || '' })
  newImage.imageUrl = ''; newImage.caption = ''
}
async function handleImageFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  imageUploading.value = true
  try {
    const res = await uploadImage(file, 'journey')
    // 上传成功后自动加入照片列表，立即显示缩略图，保存时才会被提交入库
    if (current.value) {
      current.value.images.push({ imageUrl: res.data.url, caption: newImage.caption || '' })
    }
    newImage.imageUrl = ''
    newImage.caption = ''
    ElMessage.success('图片上传成功')
  } catch (err: any) {
    ElMessage.error(err?.message || '上传失败')
  } finally {
    imageUploading.value = false
    input.value = ''
  }
}

function removeImg(i: number) {
  current.value?.images.splice(i, 1)
}
async function saveDetail() {
  if (!current.value?.journey.id) return
  try {
    await saveJourneyPoints(current.value.journey.id, current.value.points)
    await saveJourneyImages(current.value.journey.id, current.value.images)
    ElMessage.success('已保存景点与图片')
    detailDialog.value = false
    await load()
  } catch (e) { console.error(e); ElMessage.error('保存失败') }
}

const dateRange = (d?: JourneyDetail) => {
  if (!d) return '—'
  if (!d.journey) return '—'
  const { startDate, endDate } = d.journey
  return [startDate, endDate].filter(Boolean).join('  ~  ') || '—'
}

onMounted(async () => {
  await load()
  // 从地图点击「查看详情」回来时，自动打开对应旅程的详情弹窗
  const focusId = route.query.focus
  if (focusId) {
    const target = list.value.find((d) => String(d.journey.id) === String(focusId))
    if (target) openDetail(target)
  }
})
</script>

<template>
  <main class="page">
    <section class="head">
      <p class="eyebrow">MEMORIES</p>
      <h1>我的旅程</h1>
      <p class="sub">回望走过的路，把每一段出发都好好收藏。</p>
      <div class="head-actions">
        <button class="add-btn" @click="openAdd">＋ 记录旅程</button>
        <button class="map-btn" @click="router.push('/journey-map')">🗺️ 地图视图</button>
      </div>
    </section>

    <section v-if="selectedJourney" class="journey-studio">
      <div class="studio-toolbar"><button class="ghost" @click="closeJourneyStudio">← 返回</button><span>{{ journeyStudioEditing ? '正在编辑' : '预览' }}</span><button class="add-btn" @click="journeyStudioEditing ? saveJourneyStudio() : journeyStudioEditing = true">{{ journeyStudioEditing ? '保存' : '编辑' }}</button></div>
      <article class="journey-studio-card"><div class="journey-studio-cover" v-if="selectedJourney.images.length"><img :src="selectedJourney.images[0].imageUrl" alt="" /></div><div class="journey-studio-content" v-if="!journeyStudioEditing"><h2>{{ selectedJourney.journey.destination }}</h2><p class="dates">{{ dateRange(selectedJourney) }} · {{ selectedJourney.journey.totalDays || '—' }} 天</p><p class="summary">{{ selectedJourney.journey.summary || '还没有旅程总结。' }}</p><p class="summary">{{ selectedJourney.journey.highlight || '' }}</p><div class="chips"><span>{{ selectedJourney.journey.travelType || '未设置类型' }}</span><span v-if="selectedJourney.journey.totalCost != null">{{ formatCost(selectedJourney.journey.totalCost) }}</span><span v-else>费用待确认</span><span v-if="selectedJourney.journey.departureCity">从 {{ selectedJourney.journey.departureCity }} 出发</span></div><h4 class="sec">路线 · {{ selectedJourney.points.length }} 站</h4><div class="point" v-for="(p,i) in selectedJourney.points" :key="i"><span class="idx">{{ i + 1 }}</span><div class="p-info"><b>{{ p.name }}</b><p>{{ p.description }}</p></div></div></div><div class="journey-studio-content studio-form" v-else><label>目的地<input v-model="selectedJourney.journey.destination" /></label><label>出发城市<input v-model="selectedJourney.journey.departureCity" /></label><div class="row"><label>开始日期<input v-model="selectedJourney.journey.startDate" type="date" /></label><label>结束日期<input v-model="selectedJourney.journey.endDate" type="date" /></label></div><label>总结<textarea v-model="selectedJourney.journey.summary" rows="4"></textarea></label><label>亮点<input v-model="selectedJourney.journey.highlight" /></label><label>小贴士<textarea v-model="selectedJourney.journey.tips" rows="3"></textarea></label></div></article>
    </section>
    <section v-else class="list" v-loading="loading">
      <article v-for="d in list" :key="d.journey.id" class="card" @click="openJourneyTab(d)">
        <div class="cover" v-if="d.images && d.images.length">
          <img :src="d.images[0].imageUrl" alt="" />
        </div>
        <div class="info">
          <div class="topline">
            <h3>{{ d.journey.destination }}</h3>
            <span class="rating" v-if="d.journey.rating">★ {{ d.journey.rating }}</span>
          </div>
          <span class="journey-status">{{ d.journey.status === 0 ? '待记录' : '已完成' }}</span>
          <p class="dates">{{ dateRange(d) }} · {{ d.journey.totalDays }} 天</p>
          <p class="summary" v-if="d.journey.summary">{{ d.journey.summary }}</p>
          <div class="chips">
            <span v-if="d.journey.travelType">{{ d.journey.travelType }}</span>
            <span v-if="d.journey.totalCost != null">{{ formatCost(d.journey.totalCost) }}</span>
            <span v-else>费用待确认</span>
            <span v-if="d.journey.departureCity">从 {{ d.journey.departureCity }} 出发</span>
          </div>
          <div class="route" v-if="d.points && d.points.length">
            <span class="route-label">路线</span>
            <span class="route-line">
              <i v-for="(p, i) in d.points.slice(0, 8)" :key="i" class="pt" :title="p.name"></i>
            </span>
            <span class="route-count">{{ d.points.length }} 站</span>
          </div>
          <div class="actions">
            <button class="ghost" @click="router.push(`/journey-map?journeyId=${d.journey.id}`)">🗺️ 地图</button>
            <button class="ghost" @click="openDetail(d)">查看行程</button>
            <button class="ghost" @click="openEdit(d)">编辑</button>
            <button class="ghost danger" @click="remove(d)">删除</button>
          </div>
        </div>
      </article>
      <div v-if="!loading && list.length === 0" class="empty">
        还没有旅程记录，点击「记录旅程」写下你的第一段回忆。
      </div>
    </section>

    <!-- 新增/编辑旅程 -->
    <el-dialog v-model="journeyDialog" :title="editingJourney?.id ? '编辑旅程' : '记录旅程'" width="620px">
      <el-form label-position="top">
        <el-form-item label="目的地 *"><el-input v-model="jform.destination" /></el-form-item>
        <div class="row">
          <el-form-item label="出发城市"><el-input v-model="jform.departureCity" /></el-form-item>
          <el-form-item label="出行类型">
            <el-select v-model="jform.travelType"><el-option v-for="t in travelTypes" :key="t" :label="t" :value="t" /></el-select>
          </el-form-item>
        </div>
        <div class="row">
          <el-form-item label="开始日期"><el-date-picker v-model="jform.startDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item label="结束日期"><el-date-picker v-model="jform.endDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        </div>
        <div class="row">
          <el-form-item label="天数"><el-input-number v-model="jform.totalDays" :min="1" /></el-form-item>
          <el-form-item label="总花费"><el-input-number v-model="jform.totalCost" :min="0" :step="100" /></el-form-item>
          <el-form-item label="评分">
            <el-select v-model="jform.rating"><el-option v-for="r in ratingOptions" :key="r" :label="`${r} 星`" :value="r" /></el-select>
          </el-form-item>
        </div>
        <div class="row">
          <el-form-item label="同行人">
            <el-select v-model="jform.companions"><el-option v-for="c in companionsOptions" :key="c" :label="c" :value="c" /></el-select>
          </el-form-item>
          <el-form-item label="天气"><el-input v-model="jform.weatherInfo" placeholder="如：晴，18-25℃" /></el-form-item>
        </div>
        <el-form-item label="总结"><el-input v-model="jform.summary" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="亮点"><el-input v-model="jform.highlight" /></el-form-item>
        <el-form-item label="小贴士"><el-input v-model="jform.tips" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="journeyDialog = false">取消</el-button>
        <el-button type="primary" @click="saveJourney">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情：景点 + 图片 -->
    <el-dialog v-model="detailDialog" :title="`${current?.journey.destination} · 行程详情`" width="680px">
      <template v-if="current">
        <h4 class="sec">景点路线</h4>
        <div class="point" v-for="(p, i) in current.points" :key="i">
          <span class="idx">{{ i + 1 }}</span>
          <div class="p-info">
            <b>{{ p.name }}</b>
            <small v-if="p.latitude">{{ p.latitude }}, {{ p.longitude }}</small>
            <p v-if="p.description">{{ p.description }}</p>
          </div>
          <button class="mini danger" @click="removePoint(i)">✕</button>
        </div>
        <div class="inline-form">
          <el-input v-model="newPoint.name" placeholder="景点名称" style="flex:1.2" />
          <el-input v-model="newPoint.latitude" placeholder="纬度" style="flex:0.8" />
          <el-input v-model="newPoint.longitude" placeholder="经度" style="flex:0.8" />
          <el-date-picker v-model="newPoint.visitDate" type="date" value-format="YYYY-MM-DD" placeholder="日期" style="flex:1" />
          <el-button @click="addPoint">＋</el-button>
        </div>
        <el-input v-model="newPoint.description" placeholder="景点描述" class="pdesc" />

        <h4 class="sec">照片</h4>
        <div class="imgs">
          <figure v-for="(img, i) in current.images" :key="i">
            <img :src="img.imageUrl" alt="" />
            <figcaption v-if="img.caption">{{ img.caption }}</figcaption>
            <button class="mini danger abs" @click="removeImg(i)">✕</button>
          </figure>
        </div>
        <div class="inline-form">
          <el-input v-model="newImage.caption" placeholder="描述" style="flex:1" />
          <el-input
            v-if="imageInputMode === 'url'"
            v-model="newImage.imageUrl"
            placeholder="粘贴图片链接后自动加入照片区"
            style="flex:2"
          />
          <template v-else>
            <input
              ref="imgFileRef"
              type="file"
              accept="image/*"
              style="display:none"
              @change="handleImageFile"
            />
            <el-button
              :loading="imageUploading"
              @click="($refs.imgFileRef as HTMLInputElement).click()"
            >
              选择图片
            </el-button>
            <span v-if="newImage.imageUrl" style="font-size:11px;color:#687873;max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">{{ newImage.imageUrl }}</span>
          </template>
          <el-button @click="addImg">＋</el-button>
        </div>
        <div class="image-mode-toggle" style="margin-top:6px">
          <el-radio-group v-model="imageInputMode" size="small">
            <el-radio-button value="url">链接模式</el-radio-button>
            <el-radio-button value="upload">上传模式</el-radio-button>
          </el-radio-group>
        </div>
        <!-- 粘贴 URL 即时预览，与灵感目的地界面行为一致 -->
        <div v-if="imageInputMode === 'url' && newImage.imageUrl" class="url-preview">
          <img :src="newImage.imageUrl" alt="预览" />
          <span class="url-preview-hint">粘贴后自动加入</span>
        </div>
      </template>
      <template #footer>
        <el-button @click="detailDialog = false">取消</el-button>
        <el-button type="primary" @click="saveDetail">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped lang="scss">
.page { min-height: 100vh; height: 100%; padding: 24px 0 80px; overflow-y: auto; }
.head { width: min(1160px, calc(100% - 40px)); margin: 24px auto 30px; }
.eyebrow { color: var(--sunset); font-size: 10px; font-weight: 800; letter-spacing: 0.18em; margin: 0 0 8px; }
.head h1 { font: 34px "DM Serif Display", "Noto Sans SC"; color: var(--ink); margin: 0; }
.sub { color: #687873; font-size: 14px; margin: 10px 0 20px; }
.head-actions { display: flex; gap: 12px; }
.add-btn { border: 0; background: var(--forest); color: #fff; font-weight: 800; padding: 12px 22px; border-radius: 12px; cursor: pointer; box-shadow: 0 10px 20px rgba(22,78,66,.18); }
.map-btn { border: 1px solid var(--roam); background: var(--card); color: var(--forest); font-weight: 800; padding: 12px 22px; border-radius: 12px; cursor: pointer; transition: .2s; }
.map-btn:hover { background: var(--roam-soft); }

.list { width: min(1160px, calc(100% - 40px)); margin: auto; display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 22px; }
.card { background: var(--card); border: 1px solid var(--line); border-radius: 24px; overflow: hidden; }
.journey-studio { width:min(980px,calc(100% - 40px)); margin:0 auto; }
.journey-studio-card { background:var(--card); border:1px solid var(--line); border-radius:20px; overflow:hidden; box-shadow:var(--shadow-soft); }
.journey-studio-cover { height:280px; }
.journey-studio-cover img { width:100%; height:100%; object-fit:cover; }
.journey-studio-content { padding:30px 34px 38px; }
.journey-studio-content h2 { margin:0 0 14px; font:32px 'DM Serif Display','Noto Sans SC'; color:var(--ink); }
.studio-form { display:grid; gap:16px; }
.studio-form label { display:grid; gap:7px; color:var(--ink-2); font-size:12px; font-weight:700; }
.studio-form input,.studio-form textarea { width:100%; border:1px solid var(--line); border-radius:8px; padding:10px 12px; background:var(--card); color:var(--ink); font:14px inherit; }
.cover { height: 160px; }
.cover img { width: 100%; height: 100%; object-fit: cover; }
.info { padding: 18px; }
.topline { display: flex; justify-content: space-between; align-items: center; }
.topline h3 { margin: 0; color: var(--ink); font-size: 19px; }
.journey-status { display: inline-block; margin-top: 6px; padding: 3px 8px; border-radius: 10px; background: var(--roam-soft); color: var(--forest); font-size: 11px; font-weight: 700; }
.rating { color: var(--sunset); font-weight: 800; font-size: 13px; }
.dates { color: #687873; font-size: 12px; margin: 6px 0; }
.summary { color: #4a5f58; font-size: 13px; line-height: 1.6; margin: 0 0 12px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 14px; }
.chips span { background: var(--roam-soft); color: var(--forest); font-size: 11px; padding: 3px 9px; border-radius: 24px; }

/* 路线感视觉：起点森林绿 → 终点日落橙，虚线串联 */
.route { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.route-label { font-size: 10px; font-weight: 800; letter-spacing: 0.08em; color: var(--ink-3); flex-shrink: 0; }
.route-line { flex: 1; display: flex; align-items: center; justify-content: space-between; position: relative; padding: 0 3px; }
.route-line::before { content: ''; position: absolute; left: 5px; right: 5px; top: 50%; border-top: 2px dashed var(--line); }
.pt { width: 8px; height: 8px; border-radius: 50%; background: var(--roam); border: 2px solid var(--card); position: relative; z-index: 1; box-shadow: 0 0 0 1.5px var(--roam); }
.pt:first-child { background: var(--forest); box-shadow: 0 0 0 1.5px var(--forest); }
.pt:last-child { background: var(--sunset); box-shadow: 0 0 0 1.5px var(--sunset); }
.route-count { font-size: 11px; color: var(--ink-2); font-weight: 700; flex-shrink: 0; }

.actions { display: flex; gap: 10px; }
.ghost { border: 1px solid var(--line); background: transparent; color: var(--ink); font-weight: 700; font-size: 12px; padding: 7px 14px; border-radius: 9px; cursor: pointer; }
.ghost.danger { color: #c0392b; border-color: #EAC7BC; }
.empty { grid-column: 1 / -1; text-align: center; color: #8a9792; padding: 60px 0; font-size: 14px; }
.row { display: flex; gap: 14px; }
.row .el-form-item { flex: 1; }

.sec { color: var(--ink); margin: 14px 0 10px; }
.point { display: flex; gap: 12px; align-items: flex-start; background: var(--wash); border-radius: 10px; padding: 10px 12px; margin-bottom: 8px; }
.idx { width: 24px; height: 24px; border-radius: 50%; background: var(--forest); color: #fff; font-weight: 800; font-size: 12px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.p-info { flex: 1; }
.p-info b { color: var(--ink); }
.p-info small { color: #8a9792; margin-left: 8px; }
.p-info p { color: #687873; font-size: 12px; margin: 4px 0 0; }
.mini { border: 0; background: transparent; cursor: pointer; font-size: 13px; color: #c0392b; }
.mini.danger { font-weight: 700; }
.inline-form { display: flex; gap: 8px; margin-top: 10px; align-items: center; }
.pdesc { margin-top: 8px; }
.imgs { display: flex; flex-wrap: wrap; gap: 10px; }
.imgs figure { position: relative; margin: 0; width: 120px; }
.imgs img { width: 120px; height: 90px; object-fit: cover; border-radius: 8px; }
.imgs figcaption { font-size: 11px; color: #687873; margin-top: 4px; }
.abs { position: absolute; top: -8px; right: -8px; background: #fff; border-radius: 50%; width: 20px; height: 20px; box-shadow: 0 2px 6px #0002; }
.url-preview { display: flex; align-items: center; gap: 10px; margin-top: 8px; padding: 8px 10px; background: var(--wash); border-radius: 10px; }
.url-preview img { width: 80px; height: 60px; object-fit: cover; border-radius: 6px; border: 1px solid var(--line); }
.url-preview-hint { font-size: 11px; color: #8a9792; }
</style>

<style scoped>
.page {
  position: relative;
  isolation: isolate;
  background:
    radial-gradient(circle at 88% 3%, color-mix(in srgb, var(--roam) 12%, transparent), transparent 24rem),
    radial-gradient(circle at 12% 38%, color-mix(in srgb, var(--sunset) 7%, transparent), transparent 22rem),
    var(--paper);
}

.head {
  position: relative;
  padding: 26px 28px 22px;
  border: 1px solid color-mix(in srgb, var(--forest) 12%, var(--line));
  border-radius: 22px;
  background: linear-gradient(118deg, color-mix(in srgb, var(--card) 90%, var(--sunset) 10%), color-mix(in srgb, var(--card) 92%, var(--roam) 8%));
  box-shadow: var(--shadow-soft), inset 0 1px 0 rgba(255,255,255,.75);
}

.head h1 { letter-spacing: -.025em; }
.list { gap: 20px; }
.card { border-color: color-mix(in srgb, var(--forest) 12%, var(--line)); box-shadow: 0 3px 0 color-mix(in srgb, var(--forest) 4%, transparent), 0 14px 28px color-mix(in srgb, var(--forest) 6%, transparent); transition: transform .22s, box-shadow .22s; }
.card:hover { transform: translateY(-3px); box-shadow: 0 18px 36px color-mix(in srgb, var(--forest) 14%, transparent); }
.cover { height: 176px; }
.info { padding: 19px; }
.chips span { border: 1px solid color-mix(in srgb, var(--forest) 8%, transparent); }
.journey-studio-card { box-shadow: 0 16px 36px color-mix(in srgb, var(--forest) 10%, transparent); }

@media (max-width: 700px) {
  .head { padding: 22px 18px 18px; }
}
</style>
