<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { computed } from 'vue'
import { useContentTabsStore } from '@/stores/contentTabs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listInspirations, addInspiration, updateInspiration, deleteInspiration, uploadImage, type Inspiration } from '@/api/user'
import { renderMarkdown as renderSafeMarkdown } from '@/utils/markdown'

const router = useRouter()

const selected = ref<Inspiration | null>(null)
const studioEditing = ref(false)
const contentTabs = useContentTabsStore()
/** 点击卡片 → 中间卡片标签查看（浏览器式导航，不影响底层页面） */
function openView(item: Inspiration) {
  contentTabs.open({
    kind: 'inspiration',
    title: `灵感目的地-${item.name || '目的地'}`,
    data: {
      keyId: item.id ?? item.name,
      sourceType: 'inspiration',
      inspirationId: item.id,
      destination: item.name || '',
      name: item.name || '',
      content: item.description || item.quote || (`想去 ${item.name} 看看`),
      image: item.imageUrl || '',
      bestSeason: item.bestSeason || ''
    }
  })
  router.push('/card-detail')
}
function openStudioEdit() { if (selected.value) { form.value = { ...selected.value }; studioEditing.value = true } }
function closeStudio() { selected.value = null; studioEditing.value = false }
async function saveStudio() {
  if (!selected.value?.id) return
  if (!form.value.name?.trim()) { ElMessage.warning('请填写目的地名称'); return }
  await updateInspiration(selected.value.id, { ...form.value, imageUrl: (form.value.imageUrl || '').trim() })
  selected.value = { ...form.value }
  studioEditing.value = false
  await load()
  ElMessage.success('已保存')
}

const loading = ref(false)
const list = ref<Inspiration[]>([])

const dialogVisible = ref(false)
const editing = ref<Inspiration | null>(null)
const form = ref<Inspiration>({ name: '' })

// 图片上传相关状态
const imageInputMode = ref<'url' | 'upload'>('url')
const imageUploading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await listInspirations()
    list.value = res.data || []
  } catch (e) {
    console.error(e)
    ElMessage.error('加载灵感目的地失败')
  } finally {
    loading.value = false
  }
}

function openAdd() {
  editing.value = null
  form.value = { name: '', priority: 3, status: 1, sortOrder: list.value.length + 1 }
  imageInputMode.value = 'url'
  imageUploading.value = false
  dialogVisible.value = true
}

function openEdit(item: Inspiration) {
  editing.value = item
  form.value = { ...item }
  // 如果已有本地图片路径，切换到 URL 模式显示
  imageInputMode.value = item.imageUrl?.startsWith('/uploads') ? 'url' : 'url'
  imageUploading.value = false
  dialogVisible.value = true
}

async function handleImageFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  imageUploading.value = true
  try {
    const res = await uploadImage(file, 'inspiration')
    form.value.imageUrl = res.data.url
    ElMessage.success('图片上传成功')
  } catch (err: any) {
    ElMessage.error(err?.message || '上传失败')
  } finally {
    imageUploading.value = false
    input.value = ''
  }
}

async function save() {
  if (!form.value.name) {
    ElMessage.warning('请填写目的地名称')
    return
  }
  try {
    // Keep the uploaded URL in the payload explicitly so it is persisted with
    // the inspiration record, rather than only shown in the local preview.
    form.value.imageUrl = (form.value.imageUrl || '').trim()
    if (editing.value?.id) {
      await updateInspiration(editing.value.id, form.value)
      ElMessage.success('已更新')
    } else {
      await addInspiration(form.value)
      ElMessage.success('已添加')
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    console.error(e)
    ElMessage.error('保存失败')
  }
}

async function remove(item: Inspiration) {
  try {
    await ElMessageBox.confirm(`确定删除「${item.name}」吗？`, '删除灵感目的地', { type: 'warning' })
    if (item.id) await deleteInspiration(item.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    if (e !== 'cancel') console.error(e)
  }
}

const seasonOptions = ['不限', '春季', '夏季', '秋季', '冬季']
const statusText = (s?: number) => (s === 0 ? '待规划' : '已规划')
const budgetText = (b?: number | null) => (b == null ? '费用待确认' : `¥${b.toLocaleString()}`)
const renderMarkdown = (value?: string) => renderSafeMarkdown(value)

/** 将可能包含 Markdown 标记的详情清洗为简洁纯文本摘要，用于卡片缩略展示 */
function plainSummary(raw?: string): string {
  if (!raw) return ''
  return raw
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/```[\s\S]*?```/g, '')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^>\s?/gm, '')
    .replace(/^[\s]*[-*+]\s+/gm, '')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/__([^_]+)__/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1')
    .replace(/~~([^~]+)~~/g, '$1')
    .replace(/\r?\n+/g, ' ')
    .replace(/\s{2,}/g, ' ')
    .trim()
}

function parseTags(raw?: string): string[] {
  if (!raw) return []
  return raw
    .split(/[,，、]+/)
    .map((t) => t.trim())
    .filter(Boolean)
    .slice(0, 3)
}

// 无图片时生成柔和的占位背景
const palettes = [
  'linear-gradient(135deg, #DCE8FC, #EEF4FF)',
  'linear-gradient(135deg, #E2F1E8, #F2FAF5)',
  'linear-gradient(135deg, #FBE9E0, #FFF5F0)',
  'linear-gradient(135deg, #ECE6FA, #F6F3FD)'
]
function thumbStyle(item: Inspiration, i: number) {
  if (item.imageUrl) return { backgroundImage: `url(${item.imageUrl})` }
  return { backgroundImage: palettes[i % palettes.length] }
}

onMounted(load)
</script>

<template>
  <main class="page">
    <section class="head">
      <div class="head-copy">
        <p class="eyebrow">WISHLIST</p>
        <h1>灵感目的地</h1>
        <p class="sub">收藏你向往的远方，点击任意卡片查看完整攻略与行程详情。</p>
      </div>
      <button class="add-btn" @click="openAdd">＋ 添加目的地</button>
    </section>

    <section v-if="selected" class="studio">
      <div class="studio-toolbar"><button class="ghost" @click="closeStudio">← 返回</button><span>{{ studioEditing ? '正在编辑' : '预览' }}</span><button class="add-btn" @click="studioEditing ? saveStudio() : openStudioEdit()">{{ studioEditing ? '保存' : '编辑' }}</button></div>
      <article class="studio-card">
        <div class="studio-cover" :style="thumbStyle(selected, 0)"><span class="status" :class="selected.status === 0 ? 'pending' : 'done'">{{ statusText(selected.status) }}</span></div>
        <div class="studio-content" v-if="!studioEditing"><h2>{{ selected.name }}</h2><div class="markdown-body" v-html="renderMarkdown(selected.description)"></div><div v-if="selected.imageUrl" class="rendered-image"><img :src="selected.imageUrl" :alt="selected.name" /></div><div class="chips"><span v-for="t in parseTags(selected.tags)" :key="t" class="chip">{{ t }}</span></div><p class="meta">最佳季节：{{ selected.bestSeason || '不限' }}　预算：{{ budgetText(selected.estimatedBudget) }}</p></div>
        <div class="studio-content studio-form" v-else><label>目的地<input v-model="form.name" /></label><label>一句话文案<input v-model="form.quote" /></label><label>详细描述<textarea v-model="form.description" rows="5"></textarea></label><label>图片 URL<input v-model="form.imageUrl" /></label><div class="row"><label>标签<input v-model="form.tags" /></label><label>预算<input v-model.number="form.estimatedBudget" type="number" /></label></div><label>最佳季节<select v-model="form.bestSeason"><option v-for="s in seasonOptions" :key="s" :value="s">{{ s }}</option></select></label></div>
      </article>
    </section>
    <section v-else class="grid" v-loading="loading">
      <article v-for="(item, i) in list" :key="item.id" class="card clickable" @click="openView(item)">
        <div class="thumb" :style="thumbStyle(item, i)">
          <span class="status" :class="item.status === 0 ? 'pending' : 'done'">
            <i class="dot"></i>{{ statusText(item.status) }}
          </span>
        </div>
        <div class="body">
          <div class="topline">
            <h3 :title="item.name">{{ item.name }}</h3>
            <span v-if="item.priority" class="priority">★ {{ item.priority }}</span>
          </div>
          <p class="quote" v-if="item.quote" :title="item.quote">“{{ item.quote }}”</p>
          <p class="desc" v-if="item.description">{{ plainSummary(item.description) }}</p>
          <p class="desc desc--placeholder" v-else-if="!item.quote">点击查看或补充目的地灵感与行程备注</p>
          <div class="meta">
            <span v-if="item.bestSeason" class="meta-item">{{ item.bestSeason }}</span>
            <span v-if="item.estimatedBudget != null" class="meta-item">{{ budgetText(item.estimatedBudget) }}</span>
          </div>
          <div class="tags" v-if="parseTags(item.tags).length">
            <span v-for="t in parseTags(item.tags)" :key="t" class="chip">{{ t }}</span>
          </div>
          <div class="actions">
            <button class="ghost ghost--primary" @click.stop="openView(item)">查看详情</button>
            <button class="ghost" @click.stop="openEdit(item)">编辑</button>
            <button class="ghost danger" @click.stop="remove(item)">删除</button>
          </div>
        </div>
      </article>
      <div v-if="!loading && list.length === 0" class="empty">
        还没有灵感目的地，点击上方「添加目的地」开始吧。
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="editing?.id ? '编辑目的地' : '添加目的地'" width="620px" top="5vh" class="inspiration-editor">
      <el-form label-position="top">
        <el-form-item label="名称 *">
          <el-input v-model="form.name" placeholder="如：冰岛极光之旅" />
        </el-form-item>
        <el-form-item label="一句话文案">
          <el-input v-model="form.quote" placeholder="如：在世界尽头，等一场极光" />
        </el-form-item>
        <el-form-item label="详细描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="为什么想去这里…" />
        </el-form-item>
        <el-form-item label="图片">
          <div class="image-input-row">
            <el-radio-group v-model="imageInputMode" size="small" style="margin-bottom:6px">
              <el-radio-button value="url">粘贴链接</el-radio-button>
              <el-radio-button value="upload">上传图片</el-radio-button>
            </el-radio-group>
            <!-- URL 模式 -->
            <el-input
              v-if="imageInputMode === 'url'"
              v-model="form.imageUrl"
              placeholder="https://…（可选）"
              clearable
            />
            <!-- 上传模式 -->
            <div v-else class="upload-row">
              <input
                ref="imageFileRef"
                type="file"
                accept="image/*"
                style="display:none"
                @change="handleImageFile"
              />
              <el-button
                size="small"
                :loading="imageUploading"
                @click="($refs.imageFileRef as HTMLInputElement).click()"
              >
                选择图片
              </el-button>
              <span v-if="form.imageUrl" class="upload-preview">{{ form.imageUrl }}</span>
            </div>
          </div>
          <!-- 图片预览 -->
          <div v-if="form.imageUrl" class="image-preview">
            <img :src="form.imageUrl" alt="预览" />
          </div>
        </el-form-item>
        <div class="row">
          <el-form-item label="标签（逗号分隔）">
            <el-input v-model="form.tags" placeholder="摄影,美食,自驾" />
          </el-form-item>
          <el-form-item label="优先级">
            <el-input-number v-model="form.priority" :min="1" :max="5" />
          </el-form-item>
        </div>
        <div class="row">
          <el-form-item label="预估预算">
            <el-input-number v-model="form.estimatedBudget" :min="0" :step="1000" />
          </el-form-item>
          <el-form-item label="最佳季节">
            <el-select v-model="form.bestSeason" placeholder="选择季节">
              <el-option v-for="s in seasonOptions" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped lang="scss">
.page {
  min-height: 100%;
  height: 100%;
  padding: 20px 24px 64px;
  overflow-y: auto;
  background: #ffffff;
}

.head {
  width: min(1160px, 100%);
  margin: 0 auto 24px;
  padding: 24px 28px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  background: #f8fafd;
  border: 1px solid #e5e9f0;
  border-radius: 20px;
}

.head-copy {
  min-width: 0;
}

.eyebrow {
  color: #0b57d0;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
  margin: 0 0 6px;
}

.head h1 {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: #1f2329;
  margin: 0;
  line-height: 1.25;
}

.sub {
  color: #5f6368;
  font-size: 13.5px;
  margin: 6px 0 0;
  line-height: 1.5;
}

.add-btn {
  flex-shrink: 0;
  border: 0;
  background: #0b57d0;
  color: #ffffff;
  font-weight: 600;
  font-size: 13.5px;
  padding: 10px 20px;
  border-radius: 999px;
  cursor: pointer;
  transition: background 0.16s ease, transform 0.16s ease;

  &:hover {
    background: #0842a0;
    transform: translateY(-1px);
  }
}

.grid {
  width: min(1160px, 100%);
  margin: 0 auto;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 18px;
  align-items: stretch;
}

.card {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #ffffff;
  border: 1px solid #e5e9f0;
  border-radius: 18px;
  overflow: hidden;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.card.clickable {
  cursor: pointer;
}

.card:hover {
  border-color: #c2d7fa;
  transform: translateY(-2px);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
}

.thumb {
  height: 164px;
  flex-shrink: 0;
  position: relative;
  background-size: cover;
  background-position: center;
  background-color: #f0f4f9;
}

.thumb::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(to bottom, rgba(15, 23, 42, 0.16), transparent 48%);
  pointer-events: none;
}

.status {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: rgba(255, 255, 255, 0.94);
  color: #1f2329;
  font-size: 11px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 999px;
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(6px);
}

.status .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.status.done {
  color: #137333;
}
.status.done .dot {
  background: #188038;
}

.status.pending {
  color: #b06000;
}
.status.pending .dot {
  background: #e37400;
}

.body {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 16px 16px 14px;
  min-width: 0;
}

.topline {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.topline h3 {
  margin: 0;
  color: #1f2329;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.35;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.priority {
  color: #b06000;
  background: #fef7e0;
  font-weight: 700;
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 999px;
  flex-shrink: 0;
}

.quote {
  color: #0b57d0;
  font-size: 12.5px;
  font-weight: 500;
  margin: 0 0 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.desc {
  color: #5f6368;
  font-size: 12.5px;
  line-height: 1.55;
  margin: 0 0 10px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-overflow: ellipsis;
  min-height: 39px;
}

.desc--placeholder {
  color: #9aa0a6;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}

.meta-item {
  font-size: 11.5px;
  color: #5f6368;
  background: #f4f6f9;
  padding: 2px 8px;
  border-radius: 6px;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}

.chip {
  background: #e8f0fe;
  color: #0b57d0;
  font-size: 11px;
  font-weight: 500;
  padding: 2px 9px;
  border-radius: 999px;
}

.actions {
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid #eef1f5;
  display: flex;
  gap: 8px;
}

.ghost {
  border: 1px solid #e2e7ee;
  background: #ffffff;
  color: #3c4043;
  font-weight: 600;
  font-size: 12px;
  padding: 6px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;

  &:hover {
    background: #f0f4f9;
    border-color: #d2d8e2;
  }
}

.ghost--primary {
  background: #f0f4f9;
  border-color: transparent;
  color: #0b57d0;

  &:hover {
    background: #e8f0fe;
    border-color: #c2d7fa;
  }
}

.ghost.danger {
  color: #c5221f;
  border-color: #f3d6d5;
  margin-left: auto;

  &:hover {
    background: #fce8e6;
    border-color: #f2b8b5;
  }
}

.empty {
  grid-column: 1 / -1;
  text-align: center;
  color: #80868b;
  padding: 64px 0;
  font-size: 14px;
}

/* Studio 内联预览/编辑模式 */
.studio {
  width: min(920px, 100%);
  margin: 0 auto;
}

.studio-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  color: #5f6368;
  font-size: 13px;
  font-weight: 600;
}

.studio-card {
  background: #ffffff;
  border: 1px solid #e5e9f0;
  border-radius: 20px;
  overflow: hidden;
}

.studio-cover {
  height: 260px;
  position: relative;
  background-size: cover;
  background-position: center;
  padding: 16px;
}

.studio-content {
  padding: 28px 32px 34px;
}

.studio-content h2 {
  margin: 0 0 14px;
  font-size: 26px;
  font-weight: 700;
  color: #1f2329;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin: 16px 0;
}

.studio-form {
  display: grid;
  gap: 16px;
}

.studio-form label {
  display: grid;
  gap: 6px;
  color: #5f6368;
  font-size: 12px;
  font-weight: 600;
}

.studio-form input,
.studio-form textarea,
.studio-form select {
  width: 100%;
  border: 1px solid #dadce0;
  border-radius: 10px;
  padding: 10px 12px;
  background: #ffffff;
  color: #1f2329;
  font: 14px inherit;
  outline: none;

  &:focus {
    border-color: #0b57d0;
  }
}

.markdown-body {
  color: #3c4043;
  font-size: 14.5px;
  line-height: 1.8;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  color: #1f2329;
  margin: 16px 0 8px;
}

.markdown-body :deep(p) {
  margin: 8px 0;
}

.markdown-body :deep(ul) {
  padding-left: 20px;
}

.markdown-body :deep(img) {
  max-width: 100%;
  border-radius: 12px;
  display: block;
  margin: 12px 0;
}

.rendered-image img {
  max-width: 100%;
  max-height: 320px;
  object-fit: cover;
  border-radius: 12px;
  margin-top: 16px;
}

.row {
  display: flex;
  gap: 14px;
}

.row .el-form-item {
  flex: 1;
}

.image-input-row {
  width: 100%;
}

.upload-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.upload-preview {
  font-size: 12px;
  color: #5f6368;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}

.image-preview {
  margin-top: 8px;
}

.image-preview img {
  width: 100%;
  max-height: 180px;
  object-fit: cover;
  border-radius: 10px;
  border: 1px solid #e5e9f0;
}

:deep(.inspiration-editor .el-dialog__body) {
  max-height: 78vh;
  overflow-y: auto;
}

:deep(.inspiration-editor .el-dialog__header) {
  border-bottom: 1px solid #eef1f5;
}

@media (max-width: 700px) {
  .page {
    padding: 16px 14px 48px;
  }
  .head {
    flex-direction: column;
    align-items: flex-start;
    padding: 20px 18px;
  }
}
</style>
