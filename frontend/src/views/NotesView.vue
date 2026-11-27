<script setup lang="ts">
import { ref, reactive, nextTick, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { listNotes, getNote, createNote, updateNote, deleteNote, uploadNoteImage,
          getNoteUserId, type NoteDocument } from '@/api/note'
import { publishSocialNote } from '@/api/user'
import { useUserStore } from '@/stores/user'
import panelBtnLeft from '@/assets/侧边栏按钮-左.png'
import panelBtnRight from '@/assets/侧边栏按钮-右.png'
import { useRightPanelStore } from '@/stores/rightPanel'
import { getPreferences } from '@/api/user'
import { parseSystemPalette } from '@/utils/theme'
import {
  DATABASE_WORKSPACE_ID,
  createWorkspaceFileId,
  createWorkspaceId,
  loadEditorFileHandle,
  loadEditorWorkspaceState,
  persistEditorWorkspaceState,
  saveEditorFileHandle,
  type EditorWorkspace,
  type EditorWorkspaceFile
} from '@/utils/editorWorkspace'

type LocalFileHandle = {
  kind?: 'file'
  name?: string
  getFile: () => Promise<File>
  queryPermission?: (options?: { mode?: 'read' | 'readwrite' }) => Promise<PermissionState>
  requestPermission?: (options?: { mode?: 'read' | 'readwrite' }) => Promise<PermissionState>
  createWritable?: () => Promise<{
    write: (data: string) => Promise<void>
    close: () => Promise<void>
  }>
}

type FilePickerWindow = Window & {
  showOpenFilePicker?: (options?: Record<string, unknown>) => Promise<LocalFileHandle[]>
}

// ─── Markdown 引擎 ──────────────────────────────────────
const md = new MarkdownIt({
  html: true,
  linkify: true,
  breaks: true,
  typographer: true,
  table: true
  ,highlight: (code: string, lang: string) => highlightCode(code, lang)
} as any)

function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

// Lightweight highlighting for the languages most commonly used in travel notes.
// Markdown remains valid even when a language is unknown.
//
// 安全约束：
// 1) 超长代码块跳过语法着色（只转义），防止正则/浏览器渲染卡死；
// 2) 三次替换都作用在纯文本上，已高亮片段用占位符暂存，
//    之后的正则永远不会命中已生成的 <span> 标签，杜绝嵌套膨胀。
function highlightCode(code: string, lang: string): string {
  const source = escapeHtml(code)
  if (!/^(java|javascript|typescript|js|ts|json|css|html|xml|sql|bash|sh|python|py)?$/i.test(lang || '')) return source
  // 超长代码块不做高亮（只转义显示），避免产生巨量 span 卡死界面
  if (source.length > 8000) return source

  const markers: string[] = []
  const stash = (raw: string, cls: string): string => {
    markers.push('<span class="' + cls + '">' + raw + '</span>')
    return '\u0000' + String(markers.length - 1) + '\u0000'
  }
  let highlighted = source
    .replace(/\u0000/g, '') // 丢弃内容中极罕见的空字符，防止占位冲突
    .replace(/(\/\/[^\n]*|#[^\n]*)/g, (m: string) => stash(m, 'code-comment'))
    .replace(/(&quot;.*?&quot;|&#39;.*?&#39;)/g, (m: string) => stash(m, 'code-string'))
    .replace(/\b(abstract|boolean|break|case|catch|class|const|continue|def|else|extends|final|for|from|function|if|implements|import|in|interface|let|new|null|package|private|protected|public|return|static|this|throw|try|var|void|while|async|await|true|false)\b/g, (m: string) => stash(m, 'code-keyword'))
  // 还原占位符为真实 span（这些标签是程序生成的，不再参与正则匹配）
  highlighted = highlighted.replace(/\u0000(\d+)\u0000/g, (_, i: string) => markers[Number(i)] ?? '')
  return highlighted
}

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  token.attrSet('class', 'md-link')
  token.attrSet('data-link', 'true')
  return self.renderToken(tokens, idx, options)
}

function renderMarkdown(source: string): string {
  if (!source) return ''
  // 安全防线：内容异常巨大时跳过完整渲染，避免 markdown-it / DOMPurify /
  // 浏览器对超大输入卡死（正常笔记远低于该值，长文可正常渲染）。
  if (source.length > 300000) {
    return '<div class="md-oversize" style="padding:16px;color:#8a9792;font-size:13px">' +
      '内容过长（' + source.length + ' 字符），已暂停实时渲染以保护编辑器性能；保存后仍完整保留。</div>'
  }
  // 分割线兼容：md 中的 `---`/`***`/`___` 及带空格变体（`- - -`、`* * *` 等）
  // 紧贴上一段时会被 CommonMark 解析成 setext 标题，导致分割线太浅或不显示。
  // 渲染前给独立分隔线行前后补空行，强制按 <hr> 处理（不影响 #/## 标题语法）。
  source = source.replace(
    /^[ \t]*([-*_])(?:[ \t]*\1){2,}[ \t]*$/gm,
    (m: string) => '\n\n' + m.trim() + '\n\n'
  )
  try {
    const raw = md.render(source)
    // 为代码块提取语言，标注到 <pre> 上（用于显示语言角标 + 代码框）
    let html = raw.replace(
      /<pre><code[^>]*class="language-([\w+-]+)"[^>]*>/g,
      (m: string, lang: string) => `<pre data-lang="${lang}"><code class="language-${lang}">`
    )
    // 图片：解析 title 中的 w=NNN 持久化尺寸 → width 属性，并包上缩放手柄。
    // 兼容同时带有普通 title 的图片，例如 title="封面 w=640"。
    html = html.replace(
      /<img([^>]*?)\stitle="([^"]*)"([^>]*)>/g,
      (m: string, pre: string, title: string, post: string) => {
        const size = title.match(/(?:^|\s)w=(\d+)(?=\s|$)/)
        if (!size) return m
        const cleanTitle = title.replace(/(?:^|\s)w=\d+(?=\s|$)/, '').trim()
        const titleAttr = cleanTitle ? ` title="${cleanTitle}"` : ''
        return `<img${pre} width="${size[1]}"${titleAttr}${post}>`
      }
    )
    html = html.replace(
      /<p><img([^>]*)><\/p>/g,
      '<p class="img-line"><img$1 draggable="true"><i class="img-grip" title="拖动缩放"></i></p>'
    )
    // 兜底：未包裹的独立图片（表格/列表内等），跳过已带 draggable 的
    html = html.replace(
      /<img((?:(?!draggable="true")[^>])*)>/g,
      (m: string, attrs: string) => {
        if (/class="img-grip"/.test(m) || /draggable="true"/.test(m)) return m
        return `<span class="img-line"><img${attrs} draggable="true"><i class="img-grip" title="拖动缩放"></i></span>`
      }
    )
    return DOMPurify.sanitize(html, {
      ADD_TAGS: ['img', 'hr', 'input', 'figure', 'figcaption'],
      ADD_ATTR: ['contenteditable', 'draggable', 'data-note-id', 'data-link', 'width', 'title', 'style', 'loading', 'target', 'rel']
    })
  } catch {
    return source
  }
}

// ─── 状态 ───────────────────────────────────────────────
const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const creating = ref(false)
const currentId = ref<number | null>(null)
const docs = ref<NoteDocument[]>([])
const curDoc = reactive<NoteDocument>({ title: '', content: '', updatedAt: '' })

const editorContent = ref('')
const editorRef = ref<HTMLTextAreaElement | null>(null)
const liveEditorRef = ref<HTMLElement | null>(null)
const themePopoverVisible = ref(false)
const showPreview = ref(true)
const isEditorMode = ref(false)
const dirty = ref(false)
const published = ref(false)
const userStore = useUserStore()
const copySourceNoteId = computed(() => {
  try {
    const meta = curDoc.themeJson ? JSON.parse(curDoc.themeJson) : null
    return curDoc.sourceSocialNoteId || (meta?.sourceType === 'copy' ? Number(meta.sourceNoteId || 0) : 0) || 0
  } catch { return curDoc.sourceSocialNoteId || 0 }
})

// 全局右侧面板
const rightPanel = useRightPanelStore()

// 右侧信息栏折叠状态
const selectionThemeVisible = ref(true)
const outlineVisible = ref(true)
const infoVisible = ref(false)
const shortcutsVisible = ref(true)
const outlinePanelHeight = ref(360)
let outlineResizeCleanup: (() => void) | null = null

const currentUserId = computed(() => getNoteUserId())

// ─── 工作台 / 本地源文件工作区 ──────────────────────────────
// 工作区索引和本地源文件快照写入 roamly_editor_workspaces_v1；工作区中新建的
// 数据库文件正文仍写入 note_document，两条数据流保持分离。
const savedWorkspaceState = loadEditorWorkspaceState()
const workspaces = ref<EditorWorkspace[]>(savedWorkspaceState.workspaces)
const activeWorkspaceId = ref(savedWorkspaceState.activeWorkspaceId)
const activeLocalFileId = ref<string | null>(savedWorkspaceState.activeFileId)
const workbenchExpanded = ref(savedWorkspaceState.expanded)
const localFileMode = ref(savedWorkspaceState.localFileMode)
const localSourceContent = ref('')
const localEditorMode = ref<'rendered' | 'source' | 'collab'>(savedWorkspaceState.editorMode || 'rendered')
// 协同模式单独维护预览文本，避免每次输入都重绘左侧 contenteditable 导致光标跳动。
const collabPreviewContent = ref('')
let localSnapshotTimer: ReturnType<typeof setTimeout> | undefined
let documentRequestId = 0

const activeWorkspace = computed(() => workspaces.value.find(workspace => workspace.id === activeWorkspaceId.value) || null)
const activeLocalFile = computed(() => activeWorkspace.value?.files.find(file => file.id === activeLocalFileId.value) || null)
const localMarkdownMode = computed(() => localFileMode.value && isMarkdownFile(activeLocalFile.value?.name || curDoc.title))
const showRenderedEditor = computed(() => !localFileMode.value || (localMarkdownMode.value && (localEditorMode.value === 'rendered' || localEditorMode.value === 'collab')))
const showCollabEditor = computed(() => localFileMode.value && localMarkdownMode.value && localEditorMode.value === 'collab')
const renderedCollabPreview = computed(() => renderMarkdown(collabPreviewContent.value))
watch(renderedCollabPreview, () => {
  if (!showCollabEditor.value) return
  void nextTick(() => syncCollaborationScroll(localSourceEditorRef.value, collabPreviewRef.value))
})
const workspaceOwnerName = computed(() => {
  const storeName = String(userStore.nickname || '').trim()
  if (storeName && storeName !== '旅人') return storeName
  const cachedName = String(localStorage.getItem('roamly_username') || '').trim()
  return cachedName || '我的'
})
const hasActiveDocument = computed(() => Boolean(curDoc.id || creating.value || localFileMode.value))
const databaseDocs = computed(() => docs.value.filter(doc => {
  const workspaceId = noteWorkspaceId(doc)
  // 如果浏览器本地工作区索引被清理，仍把数据库文件兜底显示在“我的笔记”，
  // 避免文件只因工作区元数据丢失就无法找回。
  return !workspaceId || !workspaceById(workspaceId)
}))

function persistWorkspaceSession() {
  persistEditorWorkspaceState({
    workspaces: workspaces.value,
    activeWorkspaceId: activeWorkspaceId.value,
    activeFileId: activeLocalFileId.value,
    localFileMode: localFileMode.value,
    expanded: workbenchExpanded.value,
    editorMode: localEditorMode.value
  })
}

function workspaceById(id: string) {
  return workspaces.value.find(workspace => workspace.id === id) || null
}

function workspaceFileById(id: string) {
  for (const workspace of workspaces.value) {
    const file = workspace.files.find(item => item.id === id)
    if (file) return { workspace, file }
  }
  return null
}

function workspaceFileForDocument(workspace: EditorWorkspace, documentId: number) {
  return workspace.files.find(file => file.storage === 'database' && file.documentId === documentId) || null
}

/** 把数据库中的工作区文件补回工作台索引；正文仍只从 note_document 读取。 */
function ensureDatabaseWorkspaceFile(workspace: EditorWorkspace, doc: NoteDocument) {
  if (doc.id == null) return null
  const title = doc.title || '未命名文件.md'
  const existing = workspaceFileForDocument(workspace, doc.id)
  if (existing) {
    existing.name = title
    existing.sourcePath = `数据库文件 / ${title}`
    existing.size = new Blob([doc.content || '']).size
    existing.updatedAt = doc.updatedAt || existing.updatedAt
    existing.storage = 'database'
    existing.documentId = doc.id
    return existing
  }
  const record: EditorWorkspaceFile = {
    id: createWorkspaceFileId(workspace.id),
    name: title,
    sourcePath: `数据库文件 / ${title}`,
    storage: 'database',
    documentId: doc.id,
    size: new Blob([doc.content || '']).size,
    lastModified: 0,
    // 数据库文件不依赖本地快照；snapshot 只为兼容工作台索引结构保留为空。
    snapshot: '',
    updatedAt: doc.updatedAt || new Date().toISOString()
  }
  workspace.files.unshift(record)
  return record
}

function syncDatabaseWorkspaceFiles() {
  for (const workspace of workspaces.value) {
    if (workspace.id === DATABASE_WORKSPACE_ID) continue
    const workspaceDocs = docs.value.filter(doc => doc.id != null && noteWorkspaceId(doc) === workspace.id)
    const documentIds = new Set(workspaceDocs.map(doc => doc.id as number))
    workspaceDocs.forEach(doc => ensureDatabaseWorkspaceFile(workspace, doc))
    workspace.files = workspace.files.filter(file => file.storage !== 'database' || (file.documentId != null && documentIds.has(file.documentId)))
  }
}

function toggleWorkbench() {
  workbenchExpanded.value = !workbenchExpanded.value
  persistWorkspaceSession()
}

async function createEditorWorkspace() {
  try {
    const result = await ElMessageBox.prompt('给新的本地文件工作区起个名字', '新增工作区', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：旅行项目源码'
    })
    const name = String(result.value || '').trim()
    if (!name) return
    const timestamp = new Date().toISOString()
    const workspace: EditorWorkspace = {
      id: createWorkspaceId(),
      name,
      createdAt: timestamp,
      updatedAt: timestamp,
      files: []
    }
    workspaces.value.push(workspace)
    flushLocalSnapshot()
    activeWorkspaceId.value = workspace.id
    activeLocalFileId.value = null
    localFileMode.value = false
    resetEditorState()
    workbenchExpanded.value = true
    persistWorkspaceSession()
    ElMessage.success(`工作区「${name}」已创建`)
  } catch {
    // 用户取消输入
  }
}

/** 在指定工作区创建一个真正落到 note_document 的空文件，并立即进入统一编辑器。 */
async function createWorkspaceFile(targetWorkspaceId = activeWorkspaceId.value) {
  if (targetWorkspaceId === DATABASE_WORKSPACE_ID) {
    await addDoc('')
    return
  }
  const workspace = workspaceById(targetWorkspaceId)
  if (!workspace) return ElMessage.info('请先选择一个工作区')

  try {
    const result = await ElMessageBox.prompt('给新文件起个名字', '新增文件', {
      confirmButtonText: '创建并编辑',
      cancelButtonText: '取消',
      inputValue: '未命名.md',
      inputPlaceholder: '例如：珠海周末路线.md'
    })
    let title = String(result.value || '').trim()
    if (!title) return
    if (!/\.[a-z0-9]+$/i.test(title)) title += '.md'

    flushLocalSnapshot()
    documentRequestId++
    localFileMode.value = false
    activeWorkspaceId.value = workspace.id
    activeLocalFileId.value = null
    resetEditorState()
    curDoc.title = title
    creating.value = true
    const created = await createNote({
      title,
      content: '',
      visibility: 'private',
      themeJson: themeToJson(workspace.id)
    })
    if (created.id == null) throw new Error('文件创建成功但没有返回文件 ID')
    docs.value.unshift(created)
    const record = ensureDatabaseWorkspaceFile(workspace, created)
    workspace.updatedAt = new Date().toISOString()
    if (record) activeLocalFileId.value = record.id
    await openDoc(created.id)
    // openDoc 会按工作区元数据恢复上下文；这里再显式写回一次，兼容旧后端未回显扩展元数据的情况。
    activeWorkspaceId.value = workspace.id
    activeLocalFileId.value = record?.id || null
    workbenchExpanded.value = true
    persistWorkspaceSession()
    ElMessage.success(`文件「${title}」已创建，开始编辑吧`)
  } catch (e: any) {
    if (e === 'cancel' || e === 'close' || e?.message === 'cancel' || e?.message === 'close') return
    if (e?.message) ElMessage.error(e.message)
  } finally {
    creating.value = false
  }
}

function resetEditorState() {
  currentId.value = null
  curDoc.id = undefined
  curDoc.title = ''
  curDoc.destination = ''
  curDoc.coverUrl = ''
  curDoc.visibility = 'private'
  curDoc.updatedAt = ''
  curDoc.themeJson = ''
  curDoc.content = ''
  curDoc.sourceSocialNoteId = undefined
  editorContent.value = ''
  localSourceContent.value = ''
  collabPreviewContent.value = ''
  dirty.value = false
  published.value = false
}

function closeLocalFileMode(clearDocument = true) {
  if (localFileMode.value) flushLocalSnapshot()
  localFileMode.value = false
  activeLocalFileId.value = null
  if (clearDocument && currentId.value == null && !creating.value) resetEditorState()
  persistWorkspaceSession()
}

function selectWorkspace(id: string) {
  const workspace = workspaceById(id)
  if (!workspace) return
  if (activeWorkspaceId.value !== id) flushLocalSnapshot()
  activeWorkspaceId.value = id
  activeLocalFileId.value = null
  if (id === DATABASE_WORKSPACE_ID) {
    closeLocalFileMode(false)
    const currentIsDatabaseNote = currentId.value != null && databaseDocs.value.some(doc => doc.id === currentId.value)
    if (!currentIsDatabaseNote) {
      if (databaseDocs.value[0]?.id) void openDoc(databaseDocs.value[0].id)
      else resetEditorState()
    }
    persistWorkspaceSession()
    return
  }

  localFileMode.value = false
  if (workspace.files.length > 0) {
    void openWorkspaceFile(workspace.files[0].id)
  } else {
    resetEditorState()
    persistWorkspaceSession()
  }
}

// ─── 主题 ────────────────────────────────────────────────
const theme = reactive({
  bg: '#ffffff',
  fg: '#1f2329',
  accent: '#3370ff'
})
// 系统主题是笔记编辑器的默认主题；只有用户明确关闭时才使用单篇笔记主题。
const useSystemTheme = ref(true)
const systemTheme = reactive({ bg: '#F7F3EA', fg: '#1D2B27', accent: '#164E42' })
const effectiveTheme = computed(() => useSystemTheme.value ? systemTheme : theme)

const themePresets = [
  { name: '明亮', bg: '#ffffff', fg: '#1f2329', accent: '#3370ff' },
  { name: '护眼', bg: '#f5f0e1', fg: '#3d3527', accent: '#c4702b' },
  { name: '夜蓝', bg: '#1a1a2e', fg: '#e0e0e0', accent: '#5378ff' },
  { name: '森林', bg: '#f0f7f1', fg: '#2d3e35', accent: '#4a9b6e' },
  { name: '樱花', bg: '#fff5f7', fg: '#3d2937', accent: '#e8758e' },
  { name: '石墨', bg: '#2c2c2c', fg: '#e8e8e8', accent: '#888888' },
]
const themeRows: { key: 'bg' | 'fg' | 'accent'; label: string }[] = [
  { key: 'bg', label: '背景色' },
  { key: 'fg', label: '文字色' },
  { key: 'accent', label: '强调色' }
]

function applyTheme() {
  const root = document.documentElement
  root.style.setProperty('--notes-bg', effectiveTheme.value.bg)
  root.style.setProperty('--notes-fg', effectiveTheme.value.fg)
  root.style.setProperty('--notes-accent', effectiveTheme.value.accent)
  root.style.setProperty('--notes-line', `color-mix(in srgb, ${effectiveTheme.value.fg} 15%, ${effectiveTheme.value.bg})`)
  root.style.setProperty('--notes-wash', `color-mix(in srgb, ${effectiveTheme.value.bg} 88%, ${effectiveTheme.value.fg} 12%)`)
}
watch([theme, systemTheme, useSystemTheme], applyTheme, { deep: true })

type NoteEditorMeta = {
  editorWorkspaceId?: string
  sourceType?: string
  sourceNoteId?: number
  [key: string]: unknown
}

function parseNoteMeta(json?: string): NoteEditorMeta {
  if (!json) return {}
  try {
    const parsed = JSON.parse(json)
    return parsed && typeof parsed === 'object' ? parsed as NoteEditorMeta : {}
  } catch {
    return {}
  }
}

function noteWorkspaceId(doc?: Pick<NoteDocument, 'themeJson'> | null): string | null {
  const value = String(parseNoteMeta(doc?.themeJson).editorWorkspaceId || '').trim()
  return value || null
}

function themeToJson(workspaceId?: string): string {
  const resolvedWorkspaceId = workspaceId || (currentId.value != null ? noteWorkspaceId(curDoc) : null)
  const payload: NoteEditorMeta = {
    bg: theme.bg,
    fg: theme.fg,
    accent: theme.accent,
    useSystemTheme: useSystemTheme.value
  }
  if (resolvedWorkspaceId) payload.editorWorkspaceId = resolvedWorkspaceId
  return JSON.stringify(payload)
}

async function toggleSystemTheme() {
  applyTheme()
  if (localFileMode.value) {
    // 本地源文件不携带 Roamly 笔记主题，切换主题只影响当前编辑器外观。
    dirty.value = true
    return
  }
  // The checkbox represents an explicit preference, so persist it immediately.
  await saveDoc()
}

function loadThemeFromJson(jsonStr?: string) {
  if (!jsonStr) return
  try {
    const saved = JSON.parse(jsonStr)
    if (saved.bg) theme.bg = saved.bg
    if (saved.fg) theme.fg = saved.fg
    if (saved.accent) theme.accent = saved.accent
    useSystemTheme.value = saved.useSystemTheme !== false
  } catch {}
}

async function loadSystemTheme() {
  try {
    const result = await getPreferences()
    const profile = (result.data || {}) as { systemThemeJson?: string; name?: string; nickname?: string; username?: string }
    Object.assign(systemTheme, parseSystemPalette(profile.systemThemeJson))
    const profileName = String(profile.name || profile.nickname || profile.username || '').trim()
    if (profileName) userStore.setNickname(profileName)
    applyTheme()
  } catch {}
}

// ─── 时间格式化 ──────────────────────────────────────────
function formatTime(dateStr?: string): string {
  if (!dateStr) return '刚刚'
  try {
    let parsed: Date
    if (dateStr.includes('T')) {
      const [datePart, timePart] = dateStr.split('T')
      parsed = new Date(datePart + 'T' + (timePart || '00:00:00') + '.000')
    } else {
      parsed = new Date(dateStr)
    }
    if (isNaN(parsed.getTime())) return '刚刚'
    const now = new Date()
    const diffMs = now.getTime() - parsed.getTime()
    const diffMin = Math.floor(diffMs / 60000)
    if (diffMin < 1) return '刚刚'
    if (diffMin < 60) return `${diffMin} 分钟前`
    const y = parsed.getFullYear()
    const m = String(parsed.getMonth() + 1).padStart(2, '0')
    const d = String(parsed.getDate()).padStart(2, '0')
    const h = String(parsed.getHours()).padStart(2, '0')
    const mi = String(parsed.getMinutes()).padStart(2, '0')
    return `${y}-${m}-${d} ${h}:${mi}`
  } catch { return '刚刚' }
}

const displayTime = computed(() => formatTime(curDoc.updatedAt))
const renderedPreview = computed(() => renderMarkdown(editorContent.value))

// ─── 列表加载 ────────────────────────────────────────────
async function load() {
  loading.value = true
  try {
    docs.value = await listNotes()
    syncDatabaseWorkspaceFiles()
    persistWorkspaceSession()
    if (!localFileMode.value && currentId.value == null) {
      // 刷新后优先恢复用户正在看的自定义工作区文件；空工作区保持空白，
      // 不再被默认数据库笔记“抢回去”。
      const selectedWorkspaceFile = activeWorkspaceId.value !== DATABASE_WORKSPACE_ID && activeLocalFileId.value
        ? workspaceFileById(activeLocalFileId.value)
        : null
      if (selectedWorkspaceFile) {
        await openWorkspaceFile(selectedWorkspaceFile.file.id)
      } else if (activeWorkspaceId.value === DATABASE_WORKSPACE_ID && databaseDocs.value.length > 0) {
        await openDoc(databaseDocs.value[0].id!)
      }
    }
  } catch (e) { console.error(e) } finally { loading.value = false }
}

async function restoreLocalWorkspaceFile() {
  if (!localFileMode.value || !activeLocalFileId.value) return false
  const stored = workspaceFileById(activeLocalFileId.value)
  if (!stored || stored.workspace.id === DATABASE_WORKSPACE_ID) {
    closeLocalFileMode()
    return false
  }
  activateLocalFile(stored.workspace, stored.file)
  return true
}

onMounted(async () => {
  await restoreLocalWorkspaceFile()
  await load()
  loadSystemTheme()
  applyTheme()
  document.addEventListener('keydown', handleGlobalKeydown)
  window.addEventListener('beforeunload', persistBeforeUnload)
})

onBeforeUnmount(() => {
  flushLocalSnapshot()
  document.removeEventListener('keydown', handleGlobalKeydown)
  window.removeEventListener('beforeunload', persistBeforeUnload)
})

// ─── 打开笔记 ───────────────────────────────────────────
async function openDoc(id: number) {
  if (localFileMode.value) flushLocalSnapshot()
  const requestId = ++documentRequestId
  try {
    const d = await getNote(id)
    // 用户快速连续点击时，旧请求不能覆盖最后一次选择。
    if (requestId !== documentRequestId) return
    const docWorkspace = noteWorkspaceId(d) ? workspaceById(noteWorkspaceId(d) as string) : null
    const workspaceFile = docWorkspace ? ensureDatabaseWorkspaceFile(docWorkspace, d) : null
    localFileMode.value = false
    activeWorkspaceId.value = docWorkspace?.id || DATABASE_WORKSPACE_ID
    activeLocalFileId.value = workspaceFile?.id || null
    persistWorkspaceSession()
    currentId.value = id
    curDoc.id = d.id
    curDoc.title = d.title || ''
    curDoc.destination = d.destination || ''
    curDoc.coverUrl = d.coverUrl || ''
    curDoc.visibility = d.visibility || 'private'
    curDoc.updatedAt = d.updatedAt || ''
    curDoc.themeJson = d.themeJson || ''
    curDoc.content = d.content || ''
    curDoc.sourceSocialNoteId = d.sourceSocialNoteId

    editorContent.value = d.content || ''
    collabPreviewContent.value = d.content || ''
    dirty.value = false
    published.value = false

    if (d.themeJson) loadThemeFromJson(d.themeJson)
  } catch (e: any) { ElMessage.error(e?.message || '打开失败') }
}

// ─── 保存笔记 ────────────────────────────────────────────
async function saveDoc(silent = false, contentOverride?: string): Promise<NoteDocument | undefined> {
  const hasContentOverride = contentOverride !== undefined
  if (hasContentOverride) {
    // 图片缩放等 DOM 操作可能紧接着触发 Vue 的重新渲染。保存时接收一个
    // 明确的内容快照，避免下一次 DOM → Markdown 同步把刚改过的尺寸覆盖掉。
    editorContent.value = contentOverride
    curDoc.content = contentOverride
    if (localFileMode.value) {
      localSourceContent.value = contentOverride
      collabPreviewContent.value = contentOverride
    }
  }
  if (localFileMode.value) {
    await saveLocalFile(silent, contentOverride)
    return undefined
  }
  if (!hasContentOverride) syncLiveEditor()
  if (currentId.value == null) {
    await addDoc(hasContentOverride ? contentOverride : editorContent.value)
    return undefined
  }
  saving.value = true
  try {
    const payload = {
      title: curDoc.title || '未命名笔记',
      content: editorContent.value,
      destination: curDoc.destination || '',
      coverUrl: curDoc.coverUrl || '',
      visibility: curDoc.visibility || 'private',
      themeJson: themeToJson(noteWorkspaceId(curDoc) || undefined),
      sourceSocialNoteId: curDoc.sourceSocialNoteId
    }
    const d = await updateNote(currentId.value, payload as NoteDocument)
    if (!silent) ElMessage.success('已保存')
    curDoc.updatedAt = d.updatedAt || new Date().toISOString().replace('Z', '')
    curDoc.content = editorContent.value
    curDoc.themeJson = d.themeJson || themeToJson()
    dirty.value = false
    docs.value = await listNotes()
    syncDatabaseWorkspaceFiles()
    persistWorkspaceSession()
    return d
  } catch (e: any) {
    console.error('Save failed:', e)
    ElMessage.error(e?.message || '保存失败')
  } finally { saving.value = false }
}

/** 把当前文档发布到社区；内容仍保留在 note_document，社区只保存可展示快照。 */
async function publishCurrentNote() {
  if (localFileMode.value) {
    ElMessage.info('本地源文件只支持编辑和回写，请使用“导入文件”后再发布到圈子')
    return
  }
  if (currentId.value == null) return ElMessage.info('请先选择一篇笔记')
  syncLiveEditor()
  if (!curDoc.title?.trim()) return ElMessage.warning('请先填写笔记标题')
  if (!editorContent.value.trim()) return ElMessage.warning('正文不能为空')
  saving.value = true
  try {
    const saved = await saveDoc(true)
    if (currentId.value != null && !saved) return
    const result = await publishSocialNote({
      userId: currentUserId.value,
      privateNoteId: currentId.value,
      sourceNoteId: copySourceNoteId.value || undefined,
      title: curDoc.title.trim(),
      content: editorContent.value,
      coverUrl: curDoc.coverUrl || '',
      destination: curDoc.destination || '',
      tags: curDoc.destination ? [curDoc.destination] : [],
      authorName: userStore.nickname || '旅行者',
      authorAvatar: userStore.avatar || ''
    })
    published.value = result.data?.published === true
    dirty.value = false
    if (published.value) ElMessage.success('已发布到我的圈子')
    else if (result.data?.reviewRequired) ElMessage.warning(result.data.message || '已提交平台人工审核')
    else ElMessage.warning(result.data?.message || 'Agent 版权检测未通过，暂不允许发布')
  } catch (e: any) {
    ElMessage.error(e?.message || '发布失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

async function addDoc(initialContent = '') {
  documentRequestId++
  flushLocalSnapshot()
  closeLocalFileMode(false)
  activeWorkspaceId.value = DATABASE_WORKSPACE_ID
  resetEditorState()
  persistWorkspaceSession()
  // Initialize the editor state before the request completes so the toolbar
  // and title input remain present while the new document is being created.
  curDoc.title = '新笔记'
  curDoc.content = initialContent
  editorContent.value = initialContent
  dirty.value = false
  published.value = false
  creating.value = true
  try {
    const d = await createNote({
      title: '新笔记',
      content: initialContent,
      visibility: 'private',
      themeJson: themeToJson()
    })
    docs.value.unshift(d)
    await openDoc(d.id!)
  } catch (e: any) { ElMessage.error(e?.message || '创建失败') }
  finally { creating.value = false }
}

async function removeDoc() {
  if (localFileMode.value) return ElMessage.info('本地源文件请在源文件工作区管理')
  if (currentId.value == null) return
  try {
    const deletedId = currentId.value
    await ElMessageBox.confirm('确定删除这篇笔记吗？', '删除笔记', { type: 'warning' })
    await deleteNote(deletedId)
    for (const workspace of workspaces.value) {
      workspace.files = workspace.files.filter(file => file.documentId !== deletedId)
    }
    ElMessage.success('已删除')
    resetEditorState()
    activeWorkspaceId.value = DATABASE_WORKSPACE_ID
    persistWorkspaceSession()
    await load()
  } catch {}
}

// ─── 链接点击处理 ───────────────────────────────────────
function handlePreviewClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  const linkEl = target.closest('a') as HTMLAnchorElement | null
  if (!linkEl) return

  const url = linkEl.getAttribute('href') || linkEl.getAttribute('data-href') || ''
  if (!url) return

  e.preventDefault()
  e.stopPropagation()

  if (url.startsWith('mailto:')) {
    window.open(url, '_blank')
    return
  }
  if (url.startsWith('tel:')) {
    window.open(url, '_blank')
    return
  }

  const title = linkEl.textContent?.trim() || url
  const previewUrl = normalizeWebUrl(url)
  if (!previewUrl) {
    ElMessage.warning('这个链接不是可预览的网页地址')
    return
  }
  // 在全局右侧面板打开链接预览
  rightPanel.openLink({ url: previewUrl, title, })
}

/** 只允许网页协议进入 iframe，避免 Markdown 链接触发 javascript 等危险协议。 */
function normalizeWebUrl(raw: string): string | null {
  const value = String(raw || '').trim()
  if (!value) return null
  try {
    const parsed = new URL(value, window.location.href)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.href : null
  } catch {
    return null
  }
}

function syncLiveEditor() {
  const el = liveEditorRef.value
  if (!el) return
  const markdown = htmlToMarkdown(el)
  editorContent.value = markdown
  if (localFileMode.value) {
    // 本地 Markdown 的排版编辑器和源码编辑器共用一份文本；这里是唯一的
    // DOM → 源文件同步点，保存时不会把渲染后的 HTML 写进原文件。
    localSourceContent.value = markdown
    curDoc.content = markdown
    if (showCollabEditor.value) collabPreviewContent.value = markdown
    if (activeLocalFile.value && activeLocalFile.value.snapshot !== markdown) dirty.value = true
    scheduleLocalSnapshot()
  }
}

function handleLiveEditorInput() {
  markDirty()
  if (!localFileMode.value || !liveEditorRef.value) return
  // 本地 Markdown 在排版视图输入时也即时更新快照，但不改 editorContent，
  // 避免 v-html 每次按键重绘导致光标跳动。
  const markdown = htmlToMarkdown(liveEditorRef.value)
  localSourceContent.value = markdown
  curDoc.content = markdown
  if (showCollabEditor.value) collabPreviewContent.value = markdown
  scheduleLocalSnapshot()
}

function markDirty() {
  dirty.value = true
}

function setLocalEditorMode(mode: 'rendered' | 'source' | 'collab') {
  if (!localMarkdownMode.value) return
  if (mode === 'source') {
    // 先把当前排版编辑中的改动落到 Markdown，再切换到源码视图。
    syncLiveEditor()
    localSourceContent.value = editorContent.value
  } else {
    editorContent.value = localSourceContent.value
    curDoc.content = localSourceContent.value
    collabPreviewContent.value = localSourceContent.value
  }
  localEditorMode.value = mode
  persistWorkspaceSession()
  void nextTick(() => {
    if (mode === 'rendered') liveEditorRef.value?.focus()
    else localSourceEditorRef.value?.focus()
    if (mode === 'collab') syncCollaborationScroll(localSourceEditorRef.value, collabPreviewRef.value)
  })
}

/** 内容格式工具栏：保留当前选区后使用浏览器原生编辑命令，兼容 Markdown 内容。 */
function runEditorCommand(command: string, value?: string) {
  const el = liveEditorRef.value
  if (!el) return
  el.focus()
  const resolvedValue = value?.startsWith('var(')
    ? getComputedStyle(document.documentElement).getPropertyValue(value.slice(4, -1)).trim()
    : value
  try { document.execCommand('styleWithCSS', false, 'true') } catch { /* ignore */ }
  try { document.execCommand(command, false, resolvedValue) } catch { /* ignore */ }
  markDirty()
  syncLiveEditor()
}

function insertEditorHtml(html: string) {
  const el = liveEditorRef.value
  if (!el) return
  el.focus()
  try { document.execCommand('insertHTML', false, html) } catch { el.insertAdjacentHTML('beforeend', html) }
  markDirty()
  syncLiveEditor()
}

function insertEditorCallout() {
  insertEditorHtml('<blockquote><strong>旅行提示</strong><br>把值得记住的细节写在这里。</blockquote><p><br></p>')
}

function insertEditorDivider() { insertEditorHtml('<hr><p><br></p>') }

function htmlToMarkdown(root: HTMLElement): string {
  const MAX_DEPTH = 80
  const walk = (node: Node, depth = 0): string => {
    if (depth > MAX_DEPTH) return ''
    if (node.nodeType === Node.TEXT_NODE) return node.textContent || ""
    if (!(node instanceof HTMLElement)) return Array.from(node.childNodes).map(c => walk(c, depth + 1)).join("")
    // 缩放手柄是渲染辅助元素：不产出任何 markdown（否则会变成斜体 * 标记）
    if (node.classList && node.classList.contains('img-grip')) return ""
    const inner = Array.from(node.childNodes).map(c => walk(c, depth + 1)).join("")
    const tag = node.tagName.toLowerCase()
    let md = ''
    if (/^h[1-6]$/.test(tag)) md = '#'.repeat(Number(tag[1])) + ' ' + inner.trim() + '\n\n'
    else if (tag === 'p') md = inner.trim() + '\n\n'
    else if (tag === 'br') md = '\n'
    else if (tag === 'hr') md = '\n\n---\n\n'
    else if (tag === 'div') md = '\n' + inner
    else if (tag === 'strong' || tag === 'b') md = '**' + inner + '**'
    else if (tag === 'em' || tag === 'i') md = '*' + inner + '*'
    else if (tag === 'u') md = '<u>' + inner + '</u>'
    else if (tag === 's' || tag === 'strike') md = '<s>' + inner + '</s>'
    else if (tag === 'mark') md = '<mark>' + inner + '</mark>'
    else if (tag === 'span') {
      const style = node.getAttribute('style') || ''
      md = style ? '<span style="' + style.replace(/"/g, '&quot;') + '">' + inner + '</span>' : inner
    }
    else if (tag === 'code' && node.parentElement?.tagName.toLowerCase() !== 'pre') md = '`' + inner + '`'
    else if (tag === 'pre') md = '\n\n```\n' + node.innerText.trim() + '\n```\n\n'
    else if (tag === 'img') {
      const imgEl = node as HTMLImageElement
      const alt = imgEl.getAttribute('alt') || ''
      const src = imgEl.getAttribute('src') || ''
      const styleW = imgEl.style?.width || ''
      const attrW = imgEl.getAttribute('width') || ''
      const num = /^(\d+)(px)?$/
      const w = num.test(styleW) ? parseInt(styleW) : (num.test(attrW) ? parseInt(attrW) : 0)
      const titles: string[] = []
      const oldT = imgEl.getAttribute('title')
      if (oldT && !/^w=\d+$/.test(oldT)) titles.push(oldT)
      if (w > 0) titles.push('w=' + w)
      md = '![' + alt + '](' + src + (titles.length ? ' "' + titles.join(' ') + '"' : '') + ')'
    }
    else if (tag === 'table') {
      const rows = Array.from(node.querySelectorAll('tr')).map(r => Array.from(r.children).map(c => String(c.textContent || '').trim()))
      if (rows.length) {
        const head = '| ' + rows[0].join(' | ') + ' |'
        const div = '| ' + rows[0].map(() => '---').join(' | ') + ' |'
        const body = rows.slice(1).map(r => '| ' + r.join(' | ') + ' |').join('\n')
        md = head + '\n' + div + (body ? '\n' + body : '') + '\n\n'
      }
    }
    else if (tag === 'li') md = '- ' + inner.trim() + '\n'
    else if (tag === 'ul' || tag === 'ol') md = inner + '\n'
    else if (tag === 'blockquote') md = inner.split('\n').filter(Boolean).map(l => '> ' + l).join('\n') + '\n\n'
    else md = inner
    return md
  }
  return walk(root).replace(/\n{3,}/g, '\n\n').trim()
}

/** 同步编辑器 → Markdown，并返回光标对应的 Markdown 偏移 */
function syncWithCursor(): { md: string; pos: number } {
  const el = liveEditorRef.value
  const fallback = { md: editorContent.value, pos: editorContent.value.length }
  if (!el) return fallback
  const sel = window.getSelection()
  const anchor = (sel && sel.rangeCount > 0 ? sel.anchorNode : null) as Node | null
  const anchorOffset = sel && sel.rangeCount > 0 ? sel.anchorOffset : 0
  let pos = -1
  let acc = 0

  const walk = (node: Node, depth = 0): string => {
    if (depth > 80) return ''
    // 命中光标落点
    if (pos < 0) {
      if (node === anchor && node.nodeType === Node.TEXT_NODE) {
        pos = acc + Math.min(anchorOffset, (node.textContent || '').length)
      } else if (node === anchor) {
        pos = acc
      }
    }
    if (node.nodeType === Node.TEXT_NODE) {
      const t = node.textContent || ''
      acc += t.length
      return t
    }
    if (!(node instanceof HTMLElement)) {
      const s = Array.from(node.childNodes).map(c => walk(c, depth + 1)).join('')
      return s
    }
    if (node.classList?.contains('img-grip')) return ''
    const inner = Array.from(node.childNodes).map(c => walk(c, depth + 1)).join('')
    const tag = node.tagName.toLowerCase()
    let md = ''
    if (/^h[1-6]$/.test(tag)) md = '#'.repeat(Number(tag[1])) + ' ' + inner.trim() + '\n\n'
    else if (tag === 'p') md = inner.trim() + '\n\n'
    else if (tag === 'br') md = '\n'
    else if (tag === 'hr') md = '\n\n---\n\n'
    else if (tag === 'div') md = '\n' + inner
    else if (tag === 'strong' || tag === 'b') md = '**' + inner + '**'
    else if (tag === 'em' || tag === 'i') md = '*' + inner + '*'
    else if (tag === 'u') md = '<u>' + inner + '</u>'
    else if (tag === 's' || tag === 'strike') md = '<s>' + inner + '</s>'
    else if (tag === 'mark') md = '<mark>' + inner + '</mark>'
    else if (tag === 'span') {
      const style = node.getAttribute('style') || ''
      md = style ? '<span style="' + style.replace(/"/g, '&quot;') + '">' + inner + '</span>' : inner
    }
    else if (tag === 'code' && node.parentElement?.tagName.toLowerCase() !== 'pre') md = '`' + inner + '`'
    else if (tag === 'pre') md = '\n\n```\n' + node.innerText.trim() + '\n```\n\n'
    else if (tag === 'img') {
      const imgEl = node as HTMLImageElement
      const alt = imgEl.getAttribute('alt') || ''
      const src = imgEl.getAttribute('src') || ''
      const styleW = imgEl.style?.width || ''
      const attrW = imgEl.getAttribute('width') || ''
      const num = /^(\d+)(px)?$/
      const w = num.test(styleW) ? parseInt(styleW) : (num.test(attrW) ? parseInt(attrW) : 0)
      const titles: string[] = []
      const oldT = imgEl.getAttribute('title')
      if (oldT && !/^w=\d+$/.test(oldT)) titles.push(oldT)
      if (w > 0) titles.push('w=' + w)
      md = '![' + alt + '](' + src + (titles.length ? ' "' + titles.join(' ') + '"' : '') + ')'
    }
    else if (tag === 'table') {
      const rows = Array.from(node.querySelectorAll('tr')).map(r => Array.from(r.children).map(c => String(c.textContent || '').trim()))
      if (rows.length) {
        const head = '| ' + rows[0].join(' | ') + ' |'
        const div = '| ' + rows[0].map(() => '---').join(' | ') + ' |'
        const body = rows.slice(1).map(r => '| ' + r.join(' | ') + ' |').join('\n')
        md = head + '\n' + div + (body ? '\n' + body : '') + '\n\n'
      }
    }
    else if (tag === 'li') md = '- ' + inner.trim() + '\n'
    else if (tag === 'ul' || tag === 'ol') md = inner + '\n'
    else if (tag === 'blockquote') md = inner.split('\n').filter(Boolean).map(l => '> ' + l).join('\n') + '\n\n'
    else md = inner
    acc += md.length
    return md
  }

  let raw = walk(el).replace(/\n{3,}/g, '\n\n')
  const trimmed = raw.trim()
  const headDrop = raw.length - trimmed.length - (trimmed.split('').reverse().join('').match(/^\s*/)?.[0].length || 0)
  raw = trimmed
  pos = pos < 0 ? raw.length : Math.max(0, pos - headDrop)
  return { md: raw, pos: Math.min(pos, raw.length) }
}

/** 在光标位置插入图片（粘贴 / 选择上传）——直接操作 Markdown 字符串 */
function insertImageAtCursor(url: string) {
  const { md, pos } = syncWithCursor()
  const imgMd = '![](' + url + ')'
  const before = md.slice(0, pos)
  const after = md.slice(pos)
  const pre = before === '' || /\n\n$/.test(before) ? before : before + '\n\n'
  const post = after === '' || /^\n\n/.test(after) ? after : '\n\n' + after
  editorContent.value = (pre + imgMd + post).replace(/\n{3,}/g, '\n\n').trim()
}

/** 在最近文本末尾的下一行插入图片（拖拽）——直接操作 Markdown 字符串 */
function insertImageAtEnd(url: string) {
  const md = editorContent.value.trim()
  editorContent.value = (md ? md + '\n\n' : '') + '![](' + url + ')'
}

function syncLocalRenderedContent() {
  if (!localFileMode.value) return
  localSourceContent.value = editorContent.value
  curDoc.content = editorContent.value
  collabPreviewContent.value = editorContent.value
  dirty.value = true
  scheduleLocalSnapshot()
}

/** 源码视图下也保留插图入口，插入的是可迁移的 Markdown 图片语法。 */
function insertLocalSourceImageAtCursor(url: string, mode: 'cursor' | 'end') {
  const textarea = localSourceEditorRef.value
  const source = localSourceContent.value
  const pos = mode === 'cursor' && textarea
    ? Math.min(textarea.selectionStart, source.length)
    : source.length
  const before = source.slice(0, pos)
  const after = source.slice(pos)
  const prefix = before && !before.endsWith('\n') ? '\n\n' : ''
  const suffix = after && !after.startsWith('\n') ? '\n\n' : ''
  const image = '![](' + url + ')'
  const next = (before + prefix + image + suffix + after).replace(/\n{3,}/g, '\n\n')
  localSourceContent.value = next
  editorContent.value = next
  curDoc.content = next
  collabPreviewContent.value = next
  dirty.value = true
  scheduleLocalSnapshot()
  void nextTick(() => {
    if (!textarea) return
    const nextPos = Math.min(before.length + prefix.length + image.length, next.length)
    textarea.focus()
    textarea.setSelectionRange(nextPos, nextPos)
  })
}

type MarkdownImageSpan = { start: number; end: number; target: string }

/**
 * 找出 Markdown 内联图片的完整范围。
 *
 * 之前用一个简单正则截取图片地址，遇到没有 title 的图片时会把
 * `"w=640"` 插到整段 Markdown 的开头；URL 中带括号时也可能匹配失败，
 * 最终表现为拖动时看起来成功、重新加载后尺寸恢复。这里用小型括号扫描
 * 处理 URL 和 title，仍保持原有的 title="w=NNN" 兼容格式。
 */
function findMarkdownImageSpans(source: string): MarkdownImageSpan[] {
  const spans: MarkdownImageSpan[] = []
  let cursor = 0
  while (cursor < source.length) {
    const start = source.indexOf('![', cursor)
    if (start < 0) break
    const targetStart = source.indexOf('](', start + 2)
    if (targetStart < 0) {
      cursor = start + 2
      continue
    }

    let depth = 1
    let quote = ''
    let escaped = false
    let end = targetStart + 2
    for (; end < source.length; end += 1) {
      const char = source[end]
      if (escaped) {
        escaped = false
        continue
      }
      if (char === '\\') {
        escaped = true
        continue
      }
      if (quote) {
        if (char === quote) quote = ''
        continue
      }
      if (char === '"' || char === "'") {
        quote = char
        continue
      }
      if (char === '(') depth += 1
      if (char === ')' && --depth === 0) break
    }
    if (depth !== 0) {
      cursor = start + 2
      continue
    }
    spans.push({ start, end: end + 1, target: source.slice(targetStart + 2, end) })
    cursor = end + 1
  }
  return spans
}

function parseMarkdownImageTarget(target: string): { url: string; title: string; bracketed: boolean } | null {
  const value = target.trim()
  if (!value) return null
  if (value.startsWith('<')) {
    const close = value.indexOf('>')
    if (close < 0) return null
    const suffix = value.slice(close + 1).trim()
    return {
      url: value.slice(1, close),
      title: suffix.length >= 2 && ((suffix.startsWith('"') && suffix.endsWith('"')) || (suffix.startsWith("'") && suffix.endsWith("'")))
        ? suffix.slice(1, -1)
        : '',
      bracketed: true
    }
  }
  const match = value.match(/^(\S+)(?:\s+([\s\S]*))?$/)
  if (!match) return null
  const suffix = (match[2] || '').trim()
  return {
    url: match[1],
    title: suffix.length >= 2 && ((suffix.startsWith('"') && suffix.endsWith('"')) || (suffix.startsWith("'") && suffix.endsWith("'")))
      ? suffix.slice(1, -1)
      : '',
    bracketed: false
  }
}

/** 在 Markdown 中按图片地址/顺序回写尺寸 title="w=NNN"。 */
function setImageWidthInMarkdown(source: string, src: string, w: number, imageIndex = -1): string {
  const spans = findMarkdownImageSpans(source)
  if (!spans.length) return source
  const matches = spans.map((span, index) => ({ span, index, parsed: parseMarkdownImageTarget(span.target) }))
    .filter(item => item.parsed && (item.parsed.url === src || item.parsed.url.includes(src) || src.includes(item.parsed.url)))
  if (!matches.length) return source

  const selected = (imageIndex >= 0 ? matches.find(item => item.index === imageIndex) : null) || matches[0]
  if (!selected.parsed) return source
  const width = Math.round(Math.min(1000, Math.max(48, Number(w) || 48)))
  const oldTitle = selected.parsed.title.trim()
  const titles = oldTitle && !/^w=\d+$/.test(oldTitle) ? [oldTitle] : []
  titles.push('w=' + width)
  const url = selected.parsed.bracketed ? '<' + selected.parsed.url + '>' : selected.parsed.url
  const nextTarget = url + ' "' + titles.join(' ') + '"'
  return source.slice(0, selected.span.start) + source.slice(selected.span.start, selected.span.end).replace(selected.span.target, nextTarget) + source.slice(selected.span.end)
}

/** 上传图片并插入（统一入口：粘贴 / 拖拽 / 选择） */
async function insertUploadedImage(file: File, mode: 'cursor' | 'end') {
  if (!imageEditable()) {
    ElMessage.info('请先新建或打开一篇笔记')
    return
  }
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('仅支持图片文件')
    return
  }
  uploadingImage.value = true
  try {
    const url = await uploadNoteImage(file)
    if (localFileMode.value && (!localMarkdownMode.value || localEditorMode.value === 'source' || localEditorMode.value === 'collab')) {
      insertLocalSourceImageAtCursor(url, mode)
    } else {
      if (mode === 'cursor') insertImageAtCursor(url)
      else insertImageAtEnd(url)
      if (localFileMode.value) syncLocalRenderedContent()
      else markDirty()
    }
    ElMessage.success('图片已插入，记得点击保存')
  } catch (e: any) {
    ElMessage.error(e?.message || '图片上传失败')
  } finally {
    uploadingImage.value = false
  }
}

/** 编辑器粘贴：识别剪贴板中的图片并插入光标位置 */
function handleEditorPaste(e: ClipboardEvent) {
  const items = Array.from(e.clipboardData?.items || [])
  const imgItem = items.find(item => item.type.startsWith('image/'))
  if (!imgItem) return
  e.preventDefault()
  const file = imgItem.getAsFile()
  if (file) void insertUploadedImage(file, 'cursor')
}

/** 拖拽外部图片到编辑器：插入到最近文本末尾的下一行（Markdown 末尾追加） */
function handleImageDrop(e: DragEvent) {
  const files = Array.from(e.dataTransfer?.files || [])
  const imgFile = files.find(f => f.type.startsWith('image/'))
  if (!imgFile) return
  e.preventDefault()
  void insertUploadedImage(imgFile, 'end')
}

function handleDragOver(e: DragEvent) { e.preventDefault() }

/** 图片缩放手柄：按住右下角圆点自由调整宽度（保持比例） */
function handleEditorPointerDown(e: PointerEvent) {
  const grip = (e.target as HTMLElement).closest('.img-grip')
  if (!grip) return
  const img = grip.closest('.img-line')?.querySelector('img') as HTMLImageElement | null
  if (!img) return
  e.preventDefault()
  const startX = e.clientX
  const startW = img.clientWidth
  const onMove = (ev: PointerEvent) => {
    const w = Math.min(1000, Math.max(48, startW + (ev.clientX - startX)))
    img.style.width = w + 'px'
    img.style.height = 'auto'
  }
  const onUp = () => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    const img = grip.closest('.img-line')?.querySelector('img') as HTMLImageElement | null
    const src = img?.getAttribute('src') || ''
    if (!img) return
    // 1) 当前 DOM → Markdown，并按 DOM 顺序定位本次拖动的图片。
    const source = liveEditorRef.value ? htmlToMarkdown(liveEditorRef.value) : editorContent.value
    const imageIndex = liveEditorRef.value
      ? Array.from(liveEditorRef.value.querySelectorAll('img')).indexOf(img)
      : -1
    // 2) 显式回写尺寸。即使 DOM 转换未携带 width，也不会丢掉本次调整。
    const w = Math.min(1000, Math.max(48, img.clientWidth))
    const resizedMarkdown = src
      ? setImageWidthInMarkdown(source, src, w, imageIndex)
      : source
    editorContent.value = resizedMarkdown
    if (localFileMode.value) syncLocalRenderedContent()
    dirty.value = true
    // 3) 使用同一份内容快照保存，避免保存函数再次读取旧 DOM 覆盖尺寸。
    void saveDoc(true, resizedMarkdown)
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

/** 图片是否可插入（已有文档或正在创建） */
function imageEditable(): boolean {
  return localFileMode.value
    ? Boolean(activeLocalFile.value)
    : (curDoc.id != null || creating.value)
}

const mdInput = ref<HTMLInputElement | null>(null)
const localFileInput = ref<HTMLInputElement | null>(null)
const localImageInput = ref<HTMLInputElement | null>(null)
const localSourceEditorRef = ref<HTMLTextAreaElement | null>(null)
const collabPreviewRef = ref<HTMLElement | null>(null)
let collabScrollSyncing = false
const uploadingImage = ref(false)

function openMdPicker() { mdInput.value?.click() }
function openLocalImagePicker() { localImageInput.value?.click() }

function isMarkdownFile(fileName: string) {
  return /\.(md|markdown)$/i.test(fileName || '')
}

function handleLocalImageInput(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) void insertUploadedImage(file, 'cursor')
}

function localFilePath(file: File) {
  const value = file as File & { webkitRelativePath?: string }
  return String(value.webkitRelativePath || file.name || '未命名文件')
}

function localFileTitle(fileName: string) {
  return fileName.replace(/\.[^.]+$/, '') || fileName
}

function ensureLocalWorkspace(fileName: string) {
  const current = activeWorkspace.value
  if (current && current.id !== DATABASE_WORKSPACE_ID) return current

  const timestamp = new Date().toISOString()
  const workspace: EditorWorkspace = {
    id: createWorkspaceId(),
    name: `${localFileTitle(fileName)} 工作区`,
    createdAt: timestamp,
    updatedAt: timestamp,
    files: []
  }
  workspaces.value.push(workspace)
  activeWorkspaceId.value = workspace.id
  return workspace
}

function activateLocalFile(workspace: EditorWorkspace, file: EditorWorkspaceFile) {
  if (localFileMode.value && activeLocalFileId.value !== file.id) flushLocalSnapshot()
  documentRequestId++
  localFileMode.value = true
  activeWorkspaceId.value = workspace.id
  activeLocalFileId.value = file.id
  currentId.value = null
  creating.value = false
  curDoc.id = undefined
  curDoc.title = file.name
  curDoc.destination = ''
  curDoc.coverUrl = ''
  curDoc.visibility = 'private'
  curDoc.updatedAt = file.updatedAt
  curDoc.themeJson = ''
  curDoc.content = file.snapshot
  curDoc.sourceSocialNoteId = undefined
  localSourceContent.value = file.snapshot
  editorContent.value = file.snapshot
  collabPreviewContent.value = file.snapshot
  if (isMarkdownFile(file.name)) {
    // 恢复当前文件上一次使用的视图；新文件默认进入排版编辑。
    const restoringSameFile = savedWorkspaceState.activeFileId === file.id
    localEditorMode.value = restoringSameFile && savedWorkspaceState.editorMode
      ? savedWorkspaceState.editorMode
      : 'rendered'
  } else {
    localEditorMode.value = 'source'
  }
  dirty.value = false
  published.value = false
  persistWorkspaceSession()
}

async function rememberLocalFile(file: File, handle?: LocalFileHandle | null) {
  const workspace = ensureLocalWorkspace(file.name)
  const path = localFilePath(file)
  let record = workspace.files.find(item => item.sourcePath === path && item.name === file.name)
  const timestamp = new Date().toISOString()
  if (!record) {
    record = {
      id: createWorkspaceFileId(workspace.id),
      name: file.name,
      sourcePath: path,
      storage: 'local',
      size: file.size,
      lastModified: file.lastModified,
      snapshot: await file.text(),
      updatedAt: timestamp
    }
    workspace.files.unshift(record)
  } else {
    record.storage = 'local'
    record.documentId = undefined
    record.size = file.size
    record.lastModified = file.lastModified
    record.snapshot = await file.text()
    record.updatedAt = timestamp
  }
  workspace.updatedAt = timestamp
  if (handle) {
    try { await saveEditorFileHandle(record.id, handle) } catch { /* 句柄不可持久化时仍保留快照 */ }
  }
  activateLocalFile(workspace, record)
  workbenchExpanded.value = true
  persistWorkspaceSession()
  ElMessage.success(`已打开 ${file.name}，当前为本地源文件模式`)
}

async function openLocalFile() {
  const picker = (window as FilePickerWindow).showOpenFilePicker
  if (picker) {
    try {
      const handles = await picker({
        multiple: false,
        excludeAcceptAllOption: false,
        types: [{
          description: '文本与源码文件',
          accept: {
            'text/plain': ['.md', '.markdown', '.txt', '.json', '.html', '.css', '.js', '.ts', '.java', '.sql', '.vue']
          }
        }]
      })
      const handle = handles[0]
      if (handle) await rememberLocalFile(await handle.getFile(), handle)
      return
    } catch (error: any) {
      if (error?.name === 'AbortError') return
      // 部分浏览器实现了 picker 但拒绝了类型配置，继续走兼容输入框。
    }
  }
  localFileInput.value?.click()
}

async function openLocalFileFromInput(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  await rememberLocalFile(file)
}

async function openWorkspaceFile(fileId: string) {
  const stored = workspaceFileById(fileId)
  if (!stored || stored.workspace.id === DATABASE_WORKSPACE_ID) return
  if (stored.file.storage === 'database' && stored.file.documentId != null) {
    await openDoc(stored.file.documentId)
    return
  }
  // 先展示上次快照，避免文件句柄或权限检查让编辑器出现空白等待。
  activateLocalFile(stored.workspace, stored.file)
  if (stored.file.snapshot) return

  const handle = await loadEditorFileHandle(stored.file.id) as LocalFileHandle | null
  if (!handle || activeLocalFileId.value !== stored.file.id) return
  try {
    const file = await handle.getFile()
    const source = await file.text()
    stored.file.snapshot = source
    stored.file.size = file.size
    stored.file.lastModified = file.lastModified
    stored.file.updatedAt = new Date().toISOString()
    if (activeLocalFileId.value === stored.file.id) {
      localSourceContent.value = source
      editorContent.value = source
      collabPreviewContent.value = source
      curDoc.content = source
    }
    persistWorkspaceSession()
  } catch {
    ElMessage.info('文件路径或权限已变化，已保留上次本地快照')
  }
}

function updateLocalSnapshot() {
  if (!localFileMode.value || !activeLocalFile.value) return
  const file = activeLocalFile.value
  const timestamp = new Date().toISOString()
  file.snapshot = localSourceContent.value
  file.size = new Blob([localSourceContent.value]).size
  file.updatedAt = timestamp
  activeWorkspace.value!.updatedAt = timestamp
  curDoc.updatedAt = timestamp
  curDoc.content = localSourceContent.value
  persistWorkspaceSession()
}

function scheduleLocalSnapshot() {
  if (localSnapshotTimer) clearTimeout(localSnapshotTimer)
  localSnapshotTimer = setTimeout(() => {
    localSnapshotTimer = undefined
    updateLocalSnapshot()
  }, 350)
}

function flushLocalSnapshot() {
  if (localSnapshotTimer) {
    clearTimeout(localSnapshotTimer)
    localSnapshotTimer = undefined
  }
  updateLocalSnapshot()
  persistWorkspaceSession()
}

function persistBeforeUnload() {
  flushLocalSnapshot()
}

function handleLocalSourceInput() {
  editorContent.value = localSourceContent.value
  collabPreviewContent.value = localSourceContent.value
  curDoc.content = localSourceContent.value
  dirty.value = true
  scheduleLocalSnapshot()
}

/**
 * 协同模式的滚动同步使用可滚动高度比例，而不是逐行硬编码：
 * Markdown 源码和渲染后的段落高度不同，但用户在两侧拖动时仍能保持
 * 大致位于同一段内容，长文和图片较多的文档也不会出现跳到末尾的问题。
 */
function syncCollaborationScroll(source: HTMLElement | null, target: HTMLElement | null) {
  if (!source || !target || collabScrollSyncing) return
  const sourceMax = Math.max(0, source.scrollHeight - source.clientHeight)
  const targetMax = Math.max(0, target.scrollHeight - target.clientHeight)
  if (sourceMax <= 0 || targetMax <= 0) return

  const ratio = Math.min(1, Math.max(0, source.scrollTop / sourceMax))
  collabScrollSyncing = true
  target.scrollTop = ratio * targetMax
  window.requestAnimationFrame(() => { collabScrollSyncing = false })
}

function handleCollabSourceScroll(event: Event) {
  syncCollaborationScroll(event.currentTarget as HTMLElement, collabPreviewRef.value)
}

function handleCollabPreviewScroll(event: Event) {
  syncCollaborationScroll(event.currentTarget as HTMLElement, localSourceEditorRef.value)
}

async function writeLocalFileDirectly(): Promise<boolean> {
  if (!activeLocalFile.value) return false
  const handle = await loadEditorFileHandle(activeLocalFile.value.id) as LocalFileHandle | null
  if (!handle?.createWritable) return false
  try {
    let permission = await handle.queryPermission?.({ mode: 'readwrite' })
    if (permission !== 'granted' && handle.requestPermission) {
      permission = await handle.requestPermission({ mode: 'readwrite' })
    }
    if (permission && permission !== 'granted') return false
    const writable = await handle.createWritable()
    await writable.write(localSourceContent.value)
    await writable.close()
    return true
  } catch {
    return false
  }
}

function downloadLocalFileFallback() {
  const name = activeLocalFile.value?.name || curDoc.title || 'roamly-file.txt'
  const url = URL.createObjectURL(new Blob([localSourceContent.value], { type: 'text/plain;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

async function saveLocalFile(silent = false, contentOverride?: string) {
  if (!localFileMode.value || !activeLocalFile.value) return
  if (contentOverride !== undefined) {
    // 图片缩放保存时直接使用已生成的 Markdown 快照，避免再次从旧 DOM 读取。
    editorContent.value = contentOverride
    localSourceContent.value = contentOverride
    collabPreviewContent.value = contentOverride
    curDoc.content = contentOverride
  } else if (localMarkdownMode.value && localEditorMode.value === 'rendered') {
    syncLiveEditor()
  } else if (showCollabEditor.value) {
    editorContent.value = localSourceContent.value
    collabPreviewContent.value = localSourceContent.value
    curDoc.content = localSourceContent.value
  }
  if (localSnapshotTimer) {
    clearTimeout(localSnapshotTimer)
    localSnapshotTimer = undefined
  }
  updateLocalSnapshot()
  saving.value = true
  try {
    const written = await writeLocalFileDirectly()
    if (!written) {
      downloadLocalFileFallback()
      if (!silent) ElMessage.warning('浏览器未提供源文件写权限，已下载同名文件；本地快照已保存')
    } else if (!silent) {
      ElMessage.success('源文件已回写，本地快照也已保存')
    }
    dirty.value = false
  } finally {
    saving.value = false
  }
}

/** 导入入口：图片走上传插入，Markdown 走原导入逻辑 */
async function importFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (localFileMode.value) closeLocalFileMode(false)
  activeWorkspaceId.value = DATABASE_WORKSPACE_ID
  persistWorkspaceSession()
  if (file.type.startsWith('image/')) {
    // 从本地源文件模式导入图片时，先创建一篇数据库笔记，保证“导入文件”
    // 永远有可写入的目标，不会因为当前没有 note_document 而静默失效。
    if (currentId.value == null && !creating.value) await addDoc('')
    await insertUploadedImage(file, 'cursor')
    return
  }
  try {
    const text = await file.text()
    const title = file.name.replace(/\.md$/i, '')
    
    if (currentId.value != null) {
      // 更新现有文档
      editorContent.value = text
      curDoc.title = title
      const updated = await updateNote(currentId.value, {
        title,
        content: text,
        destination: curDoc.destination || '',
        coverUrl: curDoc.coverUrl || '',
        visibility: curDoc.visibility || 'private',
        themeJson: themeToJson()
      } as NoteDocument)
      curDoc.updatedAt = updated.updatedAt || new Date().toISOString()
      curDoc.content = text
    } else {
      // 直接创建完整文档，避免先创建空文档再更新导致导入状态不一致
      const created = await createNote({
        title,
        content: text,
        visibility: 'private',
        themeJson: themeToJson()
      })
      docs.value.unshift(created)
      await openDoc(created.id!)
    }
    await load()
    ElMessage.success('Markdown 已导入')
  } catch (e: any) {
    ElMessage.error(e?.message || '导入失败')
  }
}

// ─── 拖拽调整面板宽度（修复版） ────────────────────────────
const LEFT_MIN = -176
const LEFT_MAX = 1420
const RIGHT_MIN = -264
const RIGHT_MAX = 1640
const CENTER_MIN = -360
const leftPanelWidth = ref(Number(localStorage.getItem('roamly-notes-left-width')) || 220)
const rightPanelWidth = ref(Number(localStorage.getItem('roamly-notes-right-width')) || 320)
const showRightPanel = ref(localStorage.getItem('roamly-notes-right-visible') !== '0')
const showLeftPanel = ref(localStorage.getItem('roamly-notes-left-visible') !== '0')

function notesBounds() {
  const el = document.querySelector('.notes-app') as HTMLElement | null
  return el?.getBoundingClientRect() || { left: 0, right: window.innerWidth, width: window.innerWidth }
}

function clampPanelWidth(width: number, side: 'left' | 'right') {
  const bounds = notesBounds()
  const available = Math.max(0, bounds.width - CENTER_MIN - 12)
  if (side === 'left') return Math.round(Math.min(LEFT_MAX, Math.max(LEFT_MIN, Math.min(width, available - (showRightPanel.value ? rightPanelWidth.value : 0)))))
  return Math.round(Math.min(RIGHT_MAX, Math.max(RIGHT_MIN, Math.min(width, available - (showLeftPanel.value ? leftPanelWidth.value : 0)))))
}

watch(leftPanelWidth, value => localStorage.setItem('roamly-notes-left-width', String(value)))
watch(rightPanelWidth, value => localStorage.setItem('roamly-notes-right-width', String(value)))
watch(showLeftPanel, value => localStorage.setItem('roamly-notes-left-visible', value ? '1' : '0'))
watch(showRightPanel, value => localStorage.setItem('roamly-notes-right-visible', value ? '1' : '0'))

function onLeftHandleMouseDown(e: MouseEvent) {
  e.preventDefault()
  e.stopPropagation()
  const bounds = notesBounds()
  leftPanelWidth.value = clampPanelWidth(e.clientX - bounds.left, 'left')

  const handleMove = (ev: MouseEvent) => {
    leftPanelWidth.value = clampPanelWidth(ev.clientX - bounds.left, 'left')
  }

  const handleUp = () => {
    document.removeEventListener('mousemove', handleMove)
    document.removeEventListener('mouseup', handleUp)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
  }

  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  document.addEventListener('mousemove', handleMove)
  document.addEventListener('mouseup', handleUp)
}

function onRightHandleMouseDown(e: MouseEvent) {
  e.preventDefault()
  e.stopPropagation()
  const bounds = notesBounds()
  rightPanelWidth.value = clampPanelWidth(bounds.right - e.clientX, 'right')

  const handleMove = (ev: MouseEvent) => {
    rightPanelWidth.value = clampPanelWidth(bounds.right - ev.clientX, 'right')
  }

  const handleUp = () => {
    document.removeEventListener('mousemove', handleMove)
    document.removeEventListener('mouseup', handleUp)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
  }

  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  document.addEventListener('mousemove', handleMove)
  document.addEventListener('mouseup', handleUp)
}

// 快捷键
function handleGlobalKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault()
    saveDoc()
  }
  if ((e.ctrlKey || e.metaKey) && e.key === 'e') {
    e.preventDefault()
    showPreview.value = !showPreview.value
  }
  if ((e.ctrlKey || e.metaKey) && e.key === 'b') {
    e.preventDefault()
    showRightPanel.value = !showRightPanel.value
  }
}

// ─── 字数统计 ───────────────────────────────────────────
const wordCount = computed(() => {
  const text = editorContent.value
  if (!text) return 0
  const chinese = (text.match(/[\u4e00-\u9fa5]/g) || []).length
  const english = (text.match(/[a-zA-Z]+/g) || []).length
  return chinese + english
})

// ─── 大纲提取 ───────────────────────────────────────────
function cleanOutlineText(value: string): string {
  let source = String(value || '')
  if (!source) return ''
  try {
    // 富文本内容可能经历过一次 HTML 转义（例如 &lt;span ...&gt;），
    // 因此最多解码两轮，确保目录只拿到可见文字而不是标签源码。
    for (let round = 0; round < 2; round += 1) {
      const holder = document.createElement('div')
      holder.innerHTML = source
      const text = holder.textContent || ''
      if (!/<[a-z][\s\S]*>/i.test(text)) return text.replace(/\s+/g, ' ').trim()
      source = text
    }
    return source.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim()
  } catch {
    return source.replace(/<[^>]*>/g, '').replace(/&nbsp;/gi, ' ').replace(/\s+/g, ' ').trim()
  }
}

const outline = computed(() => {
  const lines = editorContent.value.split('\n')
  const result: { level: number; text: string; line: number }[] = []
  lines.forEach((line, idx) => {
    const match = line.match(/^(#{1,3})\s+(.+)$/)
    if (match) {
      const text = cleanOutlineText(match[2])
      if (text) result.push({ level: match[1].length, text, line: idx })
    }
  })

  // 兼容直接保存的富文本：只取 h1~h3 的可见文字，绝不把 style/span 等
  // 底层标签显示到目录里。
  const htmlHeadings = Array.from(editorContent.value.matchAll(/<h([1-3])(?:\s[^>]*)?>([\s\S]*?)<\/h\1>/gi))
  htmlHeadings.forEach((match, index) => {
    const text = cleanOutlineText(match[2])
    if (text && !result.some(item => item.text === text)) {
      result.push({ level: Number(match[1]), text, line: index })
    }
  })

  // 没有 Markdown/HTML 标题时，首个有意义的内容块作为文档入口。这样像
  // `<span style="color:…">travel-agent : …</span>` 只显示干净文字。
  if (!result.length) {
    const firstLine = lines.map(line => cleanOutlineText(line.replace(/^\s*[-*+]\s+/, ''))).find(Boolean)
    if (firstLine) result.push({ level: 1, text: firstLine.slice(0, 120), line: 0 })
    else if (cleanOutlineText(curDoc.title)) result.push({ level: 1, text: cleanOutlineText(curDoc.title), line: -1 })
  }
  return result
})

function scrollToHeading(line: number) {
  const item = outline.value.find(entry => entry.line === line)
  const container = liveEditorRef.value
  if (!container || !item) return
  if (line < 0) {
    container.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  const headings = container.querySelectorAll('h1, h2, h3')
  const heading = Array.from(headings).find(node => cleanOutlineText(node.innerHTML) === item.text.trim()) as HTMLElement | undefined
  if (heading) {
    const cRect = container.getBoundingClientRect()
    const hRect = heading.getBoundingClientRect()
    // 精准定位：标题行刚好对齐容器顶部（不差行、不偏移）
    const delta = hRect.top - cRect.top
    container.scrollTo({ top: container.scrollTop + delta, behavior: 'smooth' })
  }
}

function startOutlineResize(e: PointerEvent) {
  e.preventDefault()
  const startY = e.clientY
  const startHeight = outlinePanelHeight.value
  const move = (ev: PointerEvent) => {
    outlinePanelHeight.value = Math.max(180, Math.min(window.innerHeight * .78, startHeight + ev.clientY - startY))
  }
  const up = () => { document.removeEventListener('pointermove', move); document.removeEventListener('pointerup', up); outlineResizeCleanup = null; document.body.style.cursor = '' }
  document.body.style.cursor = 'row-resize'
  document.addEventListener('pointermove', move)
  document.addEventListener('pointerup', up)
  outlineResizeCleanup = up
}

// ─── 右侧面板 section 高度 & 上下拖拽 ────────────────────────
const sectionHeights = reactive<Record<string, number | undefined>>({
  theme: undefined,
  outline: 200,
  info: undefined,
  shortcuts: undefined
})

const MIN_SECTION_HEIGHT = 44    // 压缩到这个高度以下 = 自动折叠
const MAX_SECTION_HEIGHT = 800   // 绝对上限（防止极端情况）

// section -> visible ref map for determining collapsed state
const sectionVisibleRefs: Record<string, { value: boolean }> = {
  theme: selectionThemeVisible,
  outline: outlineVisible,
  info: infoVisible,
  shortcuts: shortcutsVisible
}

function getSectionHeightStyle(key: string) {
  const visible = sectionVisibleRefs[key]?.value ?? true
  // Collapsed state: let CSS class handle it (show only header)
  if (!visible) return ''
  // Expanded state: height stays auto (content-driven)
  // Only when user explicitly resized, set max-height on body
  const h = sectionHeights[key]
  if (!h) return ''
  // Set max-height on the section body via CSS var or inline style
  return ''
}

// Apply height to section-body instead of section
function getSectionBodyHeightStyle(key: string) {
  const visible = sectionVisibleRefs[key]?.value ?? true
  if (!visible) return ''
  const h = sectionHeights[key]
  if (!h) return ''
  // Subtract header height (approx 44px)
  const bodyHeight = Math.max(h - 44, 42)
  return `max-height: ${bodyHeight}px;`
}

// 测量 section 的自然内容高度（展开状态下）
function getNaturalHeight(el: HTMLElement): number {
  const body = el.querySelector('.section-body') as HTMLElement | null
  if (!body) return MIN_SECTION_HEIGHT
  // 临时解除高度限制测量内容
  const prev = { h: el.style.height, mh: el.style.maxHeight }
  el.style.height = 'auto'
  el.style.maxHeight = 'none'
  const natural = Math.max(body.scrollHeight + 48, MIN_SECTION_HEIGHT) // +48 = header + padding
  el.style.height = prev.h
  el.style.maxHeight = prev.mh
  return Math.min(natural, MAX_SECTION_HEIGHT)
}

function onSectionResizeStart(e: PointerEvent, beforeKey: string, afterKey: string) {
  e.preventDefault()
  e.stopPropagation()
  const container = (e.currentTarget as HTMLElement).closest('.right-panel') as HTMLElement | null
  if (!container) return

  const beforeEl = container.querySelector(`[data-section="${beforeKey}"]`) as HTMLElement | null
  const afterEl = container.querySelector(`[data-section="${afterKey}"]`) as HTMLElement | null
  if (!beforeEl || !afterEl) return

  const beforeStart = beforeEl.getBoundingClientRect().height
  const afterStart = afterEl.getBoundingClientRect().height
  const startY = e.clientY

  // 记录自然高度作为上限参考
  const naturalBefore = getNaturalHeight(beforeEl)
  const naturalAfter = getNaturalHeight(afterEl)

  let collapsedBefore = false
  let collapsedAfter = false

  const move = (ev: PointerEvent) => {
    const delta = ev.clientY - startY

    // 如果某一方已被折叠，阻止继续变化
    if (collapsedBefore && collapsedAfter) return

    // 拖拽方向修正：
    //   向上拖 (delta < 0) → 上方面板变高，下方面板变矮
    //   向下拖 (delta > 0) → 上方面板变矮，下方面板变高
    let newBefore = beforeStart + delta   // 向上拖 → before 变矮
    let newAfter = afterStart - delta     // 向上拖 → after 变高

    // 压缩检测：低于最小高度 → 自动折叠
    if (!collapsedBefore && newBefore <= MIN_SECTION_HEIGHT) {
      collapsedBefore = true
      sectionHeights[beforeKey] = undefined
      if (sectionVisibleRefs[beforeKey]) {
        sectionVisibleRefs[beforeKey].value = false
      }
      newBefore = 0
    }
    if (!collapsedAfter && newAfter <= MIN_SECTION_HEIGHT) {
      collapsedAfter = true
      sectionHeights[afterKey] = undefined
      if (sectionVisibleRefs[afterKey]) {
        sectionVisibleRefs[afterKey].value = false
      }
      newAfter = 0
    }

    // 不限制最大高度，用户可以自由拉高（内容多时内部滚动）
    if (!collapsedBefore) {
      newBefore = Math.max(newBefore, MIN_SECTION_HEIGHT)
      sectionHeights[beforeKey] = Math.round(newBefore)
    }
    if (!collapsedAfter) {
      newAfter = Math.max(newAfter, MIN_SECTION_HEIGHT)
      sectionHeights[afterKey] = Math.round(newAfter)
    }
  }

  const up = () => {
    document.removeEventListener('pointermove', move)
    document.removeEventListener('pointerup', up)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
  }

  document.body.style.cursor = 'row-resize'
  document.body.style.userSelect = 'none'
  document.addEventListener('pointermove', move)
  document.addEventListener('pointerup', up)
}
</script>

<template>
  <div class="notes-app" :style="{ background: effectiveTheme.bg, color: effectiveTheme.fg }">
    <!-- ===== 左侧笔记列表面板（与右侧面板一致） ===== -->
    <Transition name="sidebar-slide-left">
    <aside 
      v-if="showLeftPanel"
      class="left-panel" 
      :style="{ width: leftPanelWidth + 'px', background: effectiveTheme.bg, color: effectiveTheme.fg }"
    >
      <!-- 面板工具条 -->
      <div class="left-toolbar">
        <button 
          class="panel-collapse-btn"
          title="收起左侧面板"
          @click="showLeftPanel = false"
        ><img class="panel-btn-icon" :src="panelBtnLeft" alt="收起左栏" /></button>
        <span class="left-toolbar-title">🗂 笔记列表</span>
      </div>

      <div class="panel-toolbar">
        <input 
          ref="mdInput" 
          type="file" 
          accept=".md,text/markdown,image/jpeg,image/png,image/gif" 
          hidden 
          @change="importFile" 
        />
        <input
          ref="localFileInput"
          type="file"
          accept=".md,.markdown,.txt,.json,.html,.css,.js,.ts,.java,.sql,.vue,text/*"
          hidden
          @change="openLocalFileFromInput"
        />
        <input
          ref="localImageInput"
          type="file"
          accept="image/jpeg,image/png,image/gif,image/webp"
          hidden
          @change="handleLocalImageInput"
        />
        <div class="file-action-row">
          <button class="toolbar-btn" @click="openMdPicker" title="导入 Markdown 或图片到数据库">
            📥 导入文件
          </button>
          <button class="toolbar-btn local-file-btn" @click="openLocalFile" title="打开本地源文件，不写入笔记数据库">
            ↗ 打开文件
          </button>
        </div>
        <p class="file-action-hint">导入文件 = 数据库笔记 · 打开文件 = 本地源文件</p>
        <button 
          class="new-note-btn" 
          :style="{ background: effectiveTheme.accent }"
          @click="addDoc('')"
        >
          ＋ 新建笔记
        </button>
      </div>

      <div class="workspace-mini">
        <div class="workspace-trigger-row">
          <button class="workspace-trigger" :aria-expanded="workbenchExpanded" @click="toggleWorkbench">
            <span class="workspace-name">
              <span class="workspace-dot" :style="{ background: effectiveTheme.accent }">R</span>
              <span class="workspace-name-text">{{ workspaceOwnerName }} 的 Roamly 工作台</span>
            </span>
            <span class="workspace-chevron">{{ workbenchExpanded ? '⌃' : '⌄' }}</span>
          </button>
        </div>
        <div v-if="workbenchExpanded" class="workspace-dropdown">
          <div class="workspace-entry-row" :class="{ active: activeWorkspaceId === DATABASE_WORKSPACE_ID }">
            <button
              type="button"
              class="workspace-entry"
              :class="{ active: activeWorkspaceId === DATABASE_WORKSPACE_ID }"
              @click="selectWorkspace(DATABASE_WORKSPACE_ID)"
            >
              <span class="workspace-entry-icon">▤</span>
              <span class="workspace-entry-copy"><b>我的笔记</b><small>数据库笔记 · 可发布</small></span>
              <span class="workspace-entry-count">{{ databaseDocs.length }}</span>
            </button>
            <button
              type="button"
              class="workspace-entry-create"
              title="在“我的笔记”中新建文件"
              aria-label="在“我的笔记”中新建文件"
              @click.stop="createWorkspaceFile(DATABASE_WORKSPACE_ID)"
            >＋</button>
          </div>
          <template v-for="workspace in workspaces.filter(item => item.id !== DATABASE_WORKSPACE_ID)" :key="workspace.id">
            <div class="workspace-entry-row" :class="{ active: activeWorkspaceId === workspace.id }">
              <button
                type="button"
                class="workspace-entry"
                :class="{ active: activeWorkspaceId === workspace.id }"
                @click="selectWorkspace(workspace.id)"
              >
                <span class="workspace-entry-icon local">⌘</span>
                <span class="workspace-entry-copy"><b>{{ workspace.name }}</b><small>编辑工作区 · {{ workspace.files.length }} 个文件</small></span>
                <span class="workspace-entry-count">{{ workspace.files.length }}</span>
              </button>
              <button
                type="button"
                class="workspace-entry-create"
                :title="`在“${workspace.name}”中新建文件`"
                :aria-label="`在“${workspace.name}”中新建文件`"
                @click.stop="createWorkspaceFile(workspace.id)"
              >＋</button>
            </div>
            <div v-if="activeWorkspaceId === workspace.id && workspace.files.length" class="workspace-file-list">
              <button v-for="file in workspace.files" :key="file.id" type="button" class="workspace-file-entry" :class="{ active: activeLocalFileId === file.id }" @click.stop="openWorkspaceFile(file.id)">
                <span class="workspace-file-icon">·</span>
                <span><b>{{ file.name }}</b><small>{{ file.storage === 'database' ? '数据库文件 · 可发布' : '本地快照' }} · {{ formatTime(file.updatedAt) }}</small></span>
              </button>
            </div>
          </template>
          <button type="button" class="workspace-create-btn" @click="createEditorWorkspace">＋ 新增工作区</button>
        </div>
      </div>

      <div class="notes-list" v-loading="loading">
        <template v-if="activeWorkspaceId === DATABASE_WORKSPACE_ID">
          <button
            v-for="d in databaseDocs"
            :key="d.id"
            class="note-item"
            :class="{ active: currentId === d.id }"
            :style="currentId === d.id ? { background: effectiveTheme.accent + '18', color: effectiveTheme.accent } : {}"
            @click="openDoc(d.id!)"
          >
            <div class="note-title">{{ d.title || '未命名笔记' }}</div>
            <div class="note-time">{{ formatTime(d.updatedAt) }}</div>
          </button>
          <p v-if="!loading && databaseDocs.length === 0" class="empty-hint">
            还没有笔记，点上方新建。
          </p>
        </template>
        <template v-else>
          <button
            v-for="file in activeWorkspace?.files || []"
            :key="file.id"
            class="note-item local-note-item"
            :class="{ active: activeLocalFileId === file.id }"
            @click="openWorkspaceFile(file.id)"
          >
            <div class="note-title">⌘ {{ file.name }}</div>
            <div class="note-time">{{ file.storage === 'database' ? '数据库文件 · 可发布' : '本地快照' }} · {{ formatTime(file.updatedAt) }}</div>
          </button>
          <p v-if="!activeWorkspace?.files.length" class="empty-hint">
            工作区还没有文件，点击当前工作区右侧“＋”创建数据库文件，或点击“打开文件”编辑本地源文件。
          </p>
        </template>
      </div>

<!--      <div class="panel-footer">-->
<!--        <span>{{ currentUserId }}</span>-->
<!--      </div>-->
    </aside>
    </Transition>

    <!-- ===== 左侧拖拽手柄 ===== -->
    <div 
      v-if="showLeftPanel"
      class="drag-handle left-handle" 
      @mousedown="onLeftHandleMouseDown"
    ></div>

    <!-- ===== 左栏收起后的展开按钮 ===== -->
    <button
      v-if="!showLeftPanel"
      class="left-restore-btn"
      title="展开笔记列表"
      @click="showLeftPanel = true"
    ><img class="panel-btn-icon" :src="panelBtnRight" alt="展开左栏" /></button>

    <!-- ===== 中间编辑区 ===== -->
    <main class="center-panel">
      <header class="editor-toolbar" v-if="hasActiveDocument">
          <div class="breadcrumb">
          <span>{{ activeWorkspace?.name || '我的笔记' }}</span>
          <i>/</i>
          <b>{{ curDoc.title || '未命名笔记' }}</b>
        </div>
        
        <div class="toolbar-right">
          <div class="title-stack">
            <input
              v-model="curDoc.title"
              class="title-input"
              placeholder="标题"
              :readonly="localFileMode"
              @input="markDirty"
            />
            <input
              v-if="!localFileMode"
              v-model="curDoc.destination"
              class="destination-input"
              placeholder="添加目的地（可选）"
              @input="markDirty"
            />
          </div>

          <div v-if="localMarkdownMode" class="local-view-switch" role="tablist" aria-label="Markdown 编辑模式">
            <button type="button" role="tab" :aria-selected="localEditorMode === 'rendered'" :class="{ active: localEditorMode === 'rendered' }" @click="setLocalEditorMode('rendered')">排版编辑</button>
            <button type="button" role="tab" :aria-selected="localEditorMode === 'source'" :class="{ active: localEditorMode === 'source' }" @click="setLocalEditorMode('source')">源码</button>
            <button type="button" role="tab" title="边编辑边预览" :aria-selected="localEditorMode === 'collab'" :class="{ active: localEditorMode === 'collab' }" @click="setLocalEditorMode('collab')">协同</button>
          </div>
          
          <div class="tool-group">
          </div>
          
          <div class="tool-group">
            <div v-if="!localFileMode" class="theme-popover-wrap">
              <button class="tool-btn" :class="{ active: showRightPanel && selectionThemeVisible }" @click="showRightPanel = true; selectionThemeVisible = true" title="打开主题设置">🎨</button>
              <div v-if="themePopoverVisible" class="theme-popover">
                <div class="popover-title">主题颜色</div>
                <label v-for="row in themeRows" :key="row.key" class="popover-color-row">
                  <span>{{ row.label }}</span>
                  <input type="color" v-model="theme[row.key]" />
                  <input class="hex-input" v-model="theme[row.key]" maxlength="7" spellcheck="false" />
                </label>
                <div class="theme-presets compact-presets"><button v-for="p in themePresets" :key="p.name" class="preset-btn" :style="{ background: p.bg, color: p.fg, borderColor: p.accent }" @click="Object.assign(theme, p)">{{ p.name }}</button></div>
                <label class="system-theme-check"><input type="checkbox" v-model="useSystemTheme" @change="toggleSystemTheme" /> 应用系统主题</label>
                <button class="theme-save-btn" :style="{ background: effectiveTheme.accent }" @click="saveDoc(); themePopoverVisible = false">保存主题</button>
              </div>
            </div>
            <span class="save-indicator" :class="{ saving }">
              {{ saving ? '保存中…' : dirty ? '未保存' : published ? '已发布' : '已保存' }}
            </span>
            <span v-if="localFileMode" class="local-source-badge" title="本地源文件不会写入 note_document">本地源文件 · 快照分离</span>
            <span v-if="copySourceNoteId" class="archive-indicator" :title="`该笔记复制自社区帖子 ${copySourceNoteId}，发布时会进行版权检测`">来源帖子 · {{ copySourceNoteId }}</span>
            <button
              v-if="!localFileMode"
              class="publish-btn"
              :disabled="saving"
              title="发布到我的圈子"
              @click="publishCurrentNote"
            >{{ published ? '再次发布' : '发布到圈子' }}</button>
            <button 
              class="save-btn" 
              :style="{ background: effectiveTheme.accent }"
              :disabled="saving"
              @click="localFileMode ? saveLocalFile() : saveDoc()"
            >
              {{ saving ? '保存中…' : localFileMode ? '写回文件' : '保存' }}
            </button>
          </div>
        </div>
      </header>

      <div class="editor-body" v-if="hasActiveDocument">
        <div v-if="!showRenderedEditor" class="local-source-editor-shell">
          <div class="local-source-toolbar">
            <span class="local-source-path">⌘ {{ activeLocalFile?.sourcePath || activeLocalFile?.name }}</span>
            <div class="local-source-toolbar-actions">
              <span>{{ localMarkdownMode ? 'Markdown 源码' : '源文件编辑' }} · 只保存本地快照</span>
              <button type="button" class="local-insert-image-btn" :disabled="uploadingImage" @click="openLocalImagePicker">
                {{ uploadingImage ? '上传中…' : '＋ 插图' }}
              </button>
              <button v-if="localMarkdownMode" type="button" class="local-source-switch-btn" @click="setLocalEditorMode('rendered')">切换排版</button>
            </div>
          </div>
          <textarea ref="localSourceEditorRef" v-model="localSourceContent" class="local-source-editor" spellcheck="false" wrap="off" aria-label="本地源文件编辑器" @input="handleLocalSourceInput"></textarea>
          <div class="local-source-footer"><span>修改会自动记录快照</span><span>⌘ / Ctrl + S 写回原文件</span></div>
        </div>
        <div v-else class="editor-area" :class="{ 'is-collaborative': showCollabEditor }">
          <div class="editor-main">
            <div v-if="showCollabEditor" class="collaboration-source-pane">
              <div class="collaboration-source-header">
                <span>源文件编辑</span>
                <small>Markdown · 修改会实时同步到右侧</small>
                <button type="button" class="local-insert-image-btn" :disabled="uploadingImage" @click="openLocalImagePicker">
                  {{ uploadingImage ? '上传中…' : '＋ 插图' }}
                </button>
              </div>
              <textarea
                ref="localSourceEditorRef"
                v-model="localSourceContent"
                class="collaboration-source-editor"
                spellcheck="false"
                wrap="off"
                aria-label="协同模式 Markdown 源文件编辑器"
                @input="handleLocalSourceInput"
                @scroll="handleCollabSourceScroll"
              ></textarea>
            </div>
            <template v-else>
              <div class="format-toolbar" role="toolbar" aria-label="笔记格式工具栏">
                <select class="format-select" aria-label="段落样式" @change="runEditorCommand('formatBlock', ($event.target as HTMLSelectElement).value)">
                  <option value="p">正文</option>
                  <option value="h1">标题 1</option>
                  <option value="h2">标题 2</option>
                  <option value="h3">标题 3</option>
                </select>
                <select class="format-select" aria-label="字号" @change="runEditorCommand('fontSize', ($event.target as HTMLSelectElement).value)">
                  <option value="3">标准</option>
                  <option value="2">较小</option>
                  <option value="4">大</option>
                  <option value="5">特大</option>
                </select>
                <span class="format-separator"></span>
                <button type="button" class="format-btn" title="加粗" @mousedown.prevent="runEditorCommand('bold')">B</button>
                <button type="button" class="format-btn italic" title="斜体" @mousedown.prevent="runEditorCommand('italic')">I</button>
                <button type="button" class="format-btn underline" title="下划线" @mousedown.prevent="runEditorCommand('underline')">U</button>
                <button type="button" class="format-btn" title="无序列表" @mousedown.prevent="runEditorCommand('insertUnorderedList')">☷</button>
                <button type="button" class="format-btn" title="引用" @mousedown.prevent="runEditorCommand('formatBlock', 'blockquote')">❝</button>
                <button type="button" class="format-btn" title="提示卡片" @mousedown.prevent="insertEditorCallout">✦</button>
                <button type="button" class="format-btn" title="分割线" @mousedown.prevent="insertEditorDivider">—</button>
                <span class="format-separator"></span>
                <button type="button" class="color-dot color-dot--forest" title="森林绿文字" @mousedown.prevent="runEditorCommand('foreColor', 'var(--forest)')"></button>
                <button type="button" class="color-dot color-dot--sunset" title="日落橙文字" @mousedown.prevent="runEditorCommand('foreColor', 'var(--sunset)')"></button>
                <button type="button" class="color-dot color-dot--blue" title="蓝色文字" @mousedown.prevent="runEditorCommand('foreColor', '#5378ff')"></button>
                <span class="format-spacer"></span>
                <button type="button" class="insert-image-tool" :disabled="uploadingImage" title="插入插图" @mousedown.prevent="localFileMode ? openLocalImagePicker() : openMdPicker()">{{ uploadingImage ? '上传中…' : '＋ 插图' }}</button>
              </div>

              <div ref="liveEditorRef" class="preview-content markdown-body live-editor" contenteditable="true" spellcheck="false" v-html="renderedPreview" @input="handleLiveEditorInput" @blur="syncLiveEditor" @click="handlePreviewClick" @paste="handleEditorPaste" @drop="handleImageDrop" @dragover="handleDragOver" @pointerdown="handleEditorPointerDown"></div>
            </template>
          </div>

          <section v-if="showCollabEditor" class="collaboration-preview-pane" aria-label="实时预览">
            <div class="collaboration-preview-header">
              <span>实时预览</span>
              <small>点击链接可在右侧查看</small>
            </div>
            <div ref="collabPreviewRef" class="preview-content markdown-body collaboration-preview-content" v-html="renderedCollabPreview" @click="handlePreviewClick" @scroll="handleCollabPreviewScroll"></div>
          </section>
          
        </div>
      </div>
      
      <div v-else class="empty-state">
        <span>{{ activeWorkspaceId === DATABASE_WORKSPACE_ID ? '选择或创建一篇笔记开始编辑' : '这个工作区还没有文件' }}</span>
        <div class="empty-state-actions">
          <button type="button" class="empty-state-primary" :style="{ background: effectiveTheme.accent }" @click="activeWorkspaceId === DATABASE_WORKSPACE_ID ? addDoc('') : createWorkspaceFile()">
            ＋ {{ activeWorkspaceId === DATABASE_WORKSPACE_ID ? '新建笔记' : '新建文件' }}
          </button>
          <button v-if="activeWorkspaceId !== DATABASE_WORKSPACE_ID" type="button" class="empty-state-secondary" @click="openLocalFile">↗ 打开本地文件</button>
        </div>
      </div>
    </main>

    <!-- ===== 右侧拖拽手柄 ===== -->
    <div 
      v-if="showRightPanel" 
      class="drag-handle right-handle" 
      @mousedown="onRightHandleMouseDown"
    ></div>

    <!-- ===== 右侧信息面板 ===== -->
    <Transition name="sidebar-slide-right">
    <aside 
      v-if="showRightPanel"
      class="right-panel"
      :style="{ width: rightPanelWidth + 'px', background: effectiveTheme.bg }"
    >
      <!-- 面板收起条（固定在顶部） -->
      <div class="right-panel-header">
        <span class="right-toolbar-title">📄 面板</span>
        <button 
          class="panel-collapse-btn"
          title="收起右侧面板 (Ctrl+B)"
          @click="showRightPanel = false"
        ><img class="panel-btn-icon" :src="panelBtnRight" alt="收起右栏" /></button>
      </div>

      <!-- 可滚动内容区 -->
      <div class="right-panel-scroll">
      <!-- 主题设置 -->
      <div class="panel-section" data-section="theme"  :class="{ collapsed: !selectionThemeVisible }">
        <div class="section-header" @click="selectionThemeVisible = !selectionThemeVisible">
          <span>🎨 主题设置</span>
          <span class="collapse-icon">{{ selectionThemeVisible ? '▼' : '▶' }}</span>
        </div>
        <div class="section-body" :style="getSectionBodyHeightStyle('theme')" v-if="selectionThemeVisible" :class="{ collapsed: !selectionThemeVisible }">
          <div class="theme-row">
            <label>背景色</label>
            <input type="color" v-model="theme.bg" />
            <input class="hex-input" v-model="theme.bg" maxlength="7" spellcheck="false" />
          </div>
          <div class="theme-row">
            <label>文字色</label>
            <input type="color" v-model="theme.fg" />
            <input class="hex-input" v-model="theme.fg" maxlength="7" spellcheck="false" />
          </div>
          <div class="theme-row">
            <label>强调色</label>
            <input type="color" v-model="theme.accent" />
            <input class="hex-input" v-model="theme.accent" maxlength="7" spellcheck="false" />
          </div>
          <div class="theme-presets">
            <button 
              v-for="p in themePresets" 
              :key="p.name"
              class="preset-btn"
              :style="{ background: p.bg, color: p.fg, borderColor: p.accent }"
              @click="Object.assign(theme, p)"
            >{{ p.name }}</button>
          </div>
          <label class="system-theme-check"><input type="checkbox" v-model="useSystemTheme" @change="toggleSystemTheme" /> 应用系统主题</label>
          <button 
            class="theme-save-btn"
            :style="{ background: effectiveTheme.accent }"
            @click="saveDoc()"
          >💾 保存主题</button>
        </div>
      </div>

      <!-- 上下拖拽分割线 -->
      <div 
        class="section-drag-handle" 
        @pointerdown="onSectionResizeStart($event, 'theme', 'outline')"
      ></div>
      <!-- 大纲 -->
      <div class="panel-section" data-section="outline"  :class="{ collapsed: !outlineVisible }" v-if="outline.length > 0">
        <div class="section-header" @click="outlineVisible = !outlineVisible">
          <span>📑 大纲目录</span>
          <span class="collapse-icon">{{ outlineVisible ? '▼' : '▶' }}</span>
        </div>
        <div class="section-body" :style="getSectionBodyHeightStyle('outline')" v-if="outlineVisible">
          <div 
            v-for="(item, idx) in outline" 
            :key="idx"
            class="outline-item"
            :style="{ paddingLeft: (item.level - 1) * 12 + 12 + 'px' }"
            @click="scrollToHeading(item.line)"
          >
            {{ item.text }}
          </div>
          <div class="section-resize-handle" title="拖动调整大纲高度" @pointerdown="startOutlineResize"><span></span></div>
        </div>
      </div>

      <!-- 上下拖拽分割线 -->
      <div 
        class="section-drag-handle" 
        @pointerdown="onSectionResizeStart($event, 'outline', 'info')"
      ></div>
      <!-- 文档信息 -->
      <div class="panel-section" data-section="info"  :class="{ collapsed: !infoVisible }" v-if="hasActiveDocument">
        <div class="section-header" @click="infoVisible = !infoVisible">
          <span>📄 文档信息</span>
          <span class="collapse-icon">{{ infoVisible ? '▼' : '▶' }}</span>
        </div>
        <div class="section-body" :style="getSectionBodyHeightStyle('info')" v-if="infoVisible">
          <div class="info-row">
            <span>字数：</span>
            <b>{{ wordCount }}</b>
          </div>
          <div class="info-row">
            <span>最后编辑：</span>
            <b>{{ displayTime }}</b>
          </div>
          <div class="info-row">
            <span>状态：</span>
            <b>{{ localFileMode ? '本地编辑' : published ? '已发布' : '草稿' }}</b>
          </div>
          <div v-if="localFileMode" class="info-row local-info-row"><span>文件：</span><b>{{ activeLocalFile?.sourcePath || '路径不可见' }}</b></div>
          <button v-if="!localFileMode" class="danger-btn" @click="removeDoc">🗑 删除笔记</button>
        </div>
      </div>

      <!-- 上下拖拽分割线 -->
      <div 
        class="section-drag-handle" 
        @pointerdown="onSectionResizeStart($event, 'info', 'shortcuts')"
      ></div>
      <!-- 快捷键提示 -->
      <div class="panel-section" data-section="shortcuts"  :class="{ collapsed: !shortcutsVisible }">
        <div class="section-header" @click="shortcutsVisible = !shortcutsVisible">
          <span>⌨️ 快捷键</span>
          <span class="collapse-icon">{{ shortcutsVisible ? '▼' : '▶' }}</span>
        </div>
        <div class="section-body" :style="getSectionBodyHeightStyle('shortcuts')" v-if="shortcutsVisible">
          <div class="shortcut-row"><kbd>Command</kbd> + <kbd>S</kbd> 保存</div>
          <div class="shortcut-row"><kbd>Command</kbd> + <kbd>E</kbd> 切换预览</div>
          <div class="shortcut-row"><kbd>Command</kbd> + <kbd>B</kbd> 显示 / 隐藏右侧</div>
          <div class="shortcut-row"><kbd>Command</kbd> + <kbd>A</kbd> 全选</div>

        </div>
      </div>
      </div>  <!-- /.right-panel-scroll -->
    </aside>
    </Transition>

    <!-- 右栏收起后的展开按钮 -->
    <button
      v-if="!showRightPanel"
      class="right-restore-btn"
      title="展开右侧面板 (Ctrl+B)"
      @click="showRightPanel = true"
    ><img class="panel-btn-icon" :src="panelBtnLeft" alt="展开右栏" /></button>
  </div>
</template>

<style scoped lang="scss">
.notes-app {
  position: relative;
  display: flex;
  align-items: stretch;
  height: 100vh;
  overflow: hidden;
  min-width: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
  background: #F5F7FA !important;
  color: var(--notes-fg, #1F2329);
  padding: 8px;
  gap: 10px;
}

.system-theme-check {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 8px 0;
  color: var(--notes-fg, #1F2329);
  font-size: 12px;
  cursor: pointer;
}

/* ─── 左侧面板 ─────────────────────────────────── */
.left-panel {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid #E2E7EE;
  border-radius: 14px;
  margin: 0;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
  overflow: hidden;
  will-change: transform, opacity;
}

.sidebar-slide-left-enter-active,
.sidebar-slide-left-leave-active,
.sidebar-slide-right-enter-active,
.sidebar-slide-right-leave-active {
  /* 宽度收缩/展开 + 淡出：flex 占位连续变化，中间内容平滑填充，不再瞬间跳变 */
  transition: width .3s cubic-bezier(.4, 0, .2, 1), transform .3s ease, opacity .3s ease;
  overflow: hidden;
}
.sidebar-slide-left-enter-from,
.sidebar-slide-left-leave-to {
  width: 0 !important;
  transform: translateX(-18px);
  opacity: 0;
}
.sidebar-slide-right-enter-from,
.sidebar-slide-right-leave-to {
  width: 0 !important;
  transform: translateX(18px);
  opacity: 0;
}

/* 收起/展开按钮 */
.panel-collapse-btn {
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 50%;
  background: transparent;
  padding: 0;
  cursor: pointer;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform .15s, box-shadow .15s;

  .panel-btn-icon {
    width: 100%;
    height: 100%;
    object-fit: contain;
    user-select: none;
    pointer-events: none;
    border-radius: 50%;
  }

  &:hover {
    transform: scale(1.08);
    box-shadow: 0 0 0 5px rgba(51,112,255,0.15), 0 4px 14px rgba(51,112,255,0.35);
  }
}

/* 左侧面板工具条（与右侧 right-toolbar 一致） */
@keyframes restore-pop {
  from { transform: scale(.72); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}

.left-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid #e5e6e8;
  background: rgba(0, 0, 0, 0.02);
  flex-shrink: 0;

  .left-toolbar-title {
    font-size: 12px;
    font-weight: 600;
    color: #8f959e;
    white-space: nowrap;
  }

  .panel-collapse-btn { width: 26px; height: 26px; flex-shrink: 0; }
}

/* 工作台标识条与下拉列表 */
.workspace-mini {
  padding: 10px 14px;
  border-bottom: 1px solid var(--notes-line, #e5e6e8);
  flex-shrink: 0;
  min-width: 0;
}

.workspace-trigger {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
  text-align: left;
}

.workspace-trigger-row {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
}

.workspace-trigger-row .workspace-trigger {
  flex: 1;
  min-width: 0;
}

.workspace-entry-row {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
  border-radius: 8px;
}

.workspace-entry-row > .workspace-entry {
  width: auto;
  flex: 1 1 auto;
  min-width: 0;
}

.workspace-entry-create {
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--notes-accent, #164e42);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  visibility: hidden;
  pointer-events: none;
  transition: opacity .15s, visibility .15s, background .15s, transform .15s;
}

.workspace-entry-row:hover .workspace-entry-create,
.workspace-entry-row:focus-within .workspace-entry-create {
  opacity: 1;
  visibility: visible;
  pointer-events: auto;
}

.workspace-entry-create:hover,
.workspace-entry-create:focus-visible {
  background: color-mix(in srgb, var(--notes-accent, #164e42) 10%, transparent);
  transform: translateY(-1px);
}

.workspace-trigger:hover .workspace-name-text,
.workspace-trigger:focus-visible .workspace-name-text {
  color: var(--notes-accent, #164e42);
}

.workspace-name {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 650;
  color: inherit;
}

.workspace-name-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color .15s;
}

.workspace-chevron {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: 6px;
  color: #8f959e;
  font-size: 14px;
  transition: background .15s, color .15s;
}

.workspace-trigger:hover .workspace-chevron,
.workspace-trigger:focus-visible .workspace-chevron {
  background: var(--notes-wash, #f0f1f2);
  color: var(--notes-accent, #164e42);
}

.workspace-dot {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  font-size: 12px;
  flex-shrink: 0;
}

.workspace-dropdown {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: min(38vh, 320px);
  margin-top: 8px;
  padding: 6px;
  overflow-y: auto;
  border: 1px solid #E5E9F0;
  border-radius: 12px;
  background: color-mix(in srgb, var(--notes-bg, #fff) 96%, var(--notes-fg, #1f2329) 4%);
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.04);
  scrollbar-width: thin;
}

.workspace-entry,
.workspace-file-entry,
.workspace-create-btn {
  width: 100%;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
  text-align: left;
}

.workspace-entry {
  padding: 8px;
  transition: background .15s, color .15s;
}

.workspace-entry:hover,
.workspace-entry:focus-visible,
.workspace-entry.active {
  background: color-mix(in srgb, var(--notes-accent, #164e42) 12%, transparent);
}

.workspace-entry-icon {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  border-radius: 7px;
  background: color-mix(in srgb, var(--notes-accent, #164e42) 15%, transparent);
  color: var(--notes-accent, #164e42);
  font-size: 12px;
  font-weight: 800;
}

.workspace-entry-icon.local {
  background: color-mix(in srgb, var(--sunset, #f27a4f) 15%, transparent);
  color: var(--sunset, #f27a4f);
}

.workspace-entry-copy,
.workspace-file-entry > span:last-child {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 2px;
}

.workspace-entry-copy b,
.workspace-file-entry b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 650;
}

.workspace-entry-copy small,
.workspace-file-entry small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #8f959e;
  font-size: 10px;
}

.workspace-entry-count {
  flex-shrink: 0;
  min-width: 20px;
  padding: 2px 6px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--notes-fg, #1f2329) 8%, transparent);
  color: #8f959e;
  font-size: 10px;
  text-align: center;
}

.workspace-file-list {
  display: grid;
  gap: 2px;
  margin: -1px 0 2px 30px;
  padding-left: 8px;
  border-left: 1px solid var(--notes-line, #e5e6e8);
}

.workspace-file-entry {
  padding: 6px 7px;
  gap: 6px;
  color: #646a73;
}

.workspace-file-entry:hover,
.workspace-file-entry:focus-visible,
.workspace-file-entry.active {
  background: color-mix(in srgb, var(--notes-accent, #164e42) 10%, transparent);
  color: var(--notes-accent, #164e42);
}

.workspace-file-icon {
  flex-shrink: 0;
  color: var(--notes-accent, #164e42);
  font-size: 18px;
  line-height: 1;
}

.workspace-create-btn {
  justify-content: center;
  margin-top: 4px;
  padding: 8px;
  border: 1px dashed var(--notes-line, #d8dade);
  color: var(--notes-accent, #164e42);
  font-size: 12px;
  transition: background .15s, border-color .15s;
}

.workspace-create-btn:hover,
.workspace-create-btn:focus-visible {
  background: color-mix(in srgb, var(--notes-accent, #164e42) 8%, transparent);
  border-color: var(--notes-accent, #164e42);
}

/* 左栏收起后的展开按钮 */
.left-restore-btn {
  position: absolute;
  left: 12px;
  top: 14px;
  animation: restore-pop .22s ease;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: transparent;
  padding: 0;
  cursor: pointer;
  z-index: 50;
  transition: transform .15s, box-shadow .15s;

  .panel-btn-icon {
    width: 100%;
    height: 100%;
    object-fit: contain;
    user-select: none;
    pointer-events: none;
    border-radius: 50%;
  }

  &:hover {
    transform: scale(1.08);
    box-shadow: 0 0 0 5px rgba(51,112,255,0.15), 0 4px 14px rgba(51,112,255,0.35);
  }
}

/* 右侧面板收起工具条 */
/* 面板标题栏（固定在顶部，不滚动） */
.right-panel-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 4px 12px;
  background: var(--notes-bg, #fafafa);
  border-bottom: 1px solid #e5e6e8;
  margin-bottom: 8px;
  z-index: 5;

  .right-toolbar-title {
    font-size: 13px;
    font-weight: 700;
    color: var(--notes-fg, #1f2329);
  }

  .panel-collapse-btn { width: 26px; height: 26px; }
}

/* 可滚动内容区 */
.right-panel-scroll {
  flex: 1 1 auto;
  overflow-y: auto;
  overflow-x: hidden;
  min-height: 0;                     /* 关键：允许 flex 子项收缩以触发滚动 */
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-bottom: 16px;              /* 底部留白 */
  scrollbar-width: thin;
  scrollbar-color: rgba(0,0,0,.2) transparent;
}

.right-panel-scroll::-webkit-scrollbar {
  width: 6px;
}
.right-panel-scroll::-webkit-scrollbar-thumb {
  background: rgba(0,0,0,.2);
  border-radius: 3px;
}
.right-panel-scroll::-webkit-scrollbar-track {
  background: transparent;
}

/* 右栏收起后的展开按钮 */
.right-restore-btn {
  position: absolute;
  right: 12px;
  top: 14px;
  animation: restore-pop .22s ease;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: transparent;
  padding: 0;
  cursor: pointer;
  z-index: 50;
  transition: transform .15s, box-shadow .15s;

  .panel-btn-icon {
    width: 100%;
    height: 100%;
    object-fit: contain;
    user-select: none;
    pointer-events: none;
    border-radius: 50%;
  }

  &:hover {
    transform: scale(1.08);
    box-shadow: 0 0 0 5px rgba(51,112,255,0.15), 0 4px 14px rgba(51,112,255,0.35);
  }
}


.panel-toolbar {
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-bottom: 1px solid var(--notes-line, #e5e6e8);
}

.file-action-row {
  display: flex;
  gap: 6px;

  .toolbar-btn {
    width: auto;
    flex: 1 1 0;
    min-width: 0;
    padding-inline: 6px;
  }
}

.file-action-hint {
  margin: -2px 2px 0;
  color: #8f959e;
  font-size: 10px;
  line-height: 1.4;
  text-align: center;
}

.toolbar-btn {
  width: 100%;
  padding: 7px 10px;
  border: 1px solid #E2E7EE;
  border-radius: 10px;
  background: #F8FAFD;
  color: #3C4043;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  text-align: center;
  transition: all .15s ease;

  &:hover {
    border-color: #C2D4F8;
    color: #0B57D0;
    background: #EDF3FE;
  }
}

.local-file-btn {
  border-color: #E2E7EE;
  color: #444746;
}

.new-note-btn {
  width: 100%;
  padding: 9px 14px;
  border: 0;
  border-radius: 999px;
  background: #0B57D0 !important;
  color: #FFFFFF;
  font-weight: 600;
  font-size: 13px;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(11, 87, 208, 0.2);
  transition: transform .15s ease, box-shadow .15s ease, background .15s ease;

  &:hover {
    background: #0842A0 !important;
    transform: translateY(-1px);
    box-shadow: 0 4px 12px rgba(11, 87, 208, 0.25);
  }
}

.notes-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.note-item {
  width: 100%;
  text-align: left;
  padding: 12px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background .15s;

  &:hover { background: #f0f1f2; }

  .note-title {
    font-size: 14px;
    font-weight: 500;
    margin-bottom: 4px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .note-time {
    font-size: 12px;
    color: #8f959e;
  }
}

.local-note-item {
  .note-title {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 12px;
  }

  &:hover {
    background: color-mix(in srgb, var(--notes-accent, #164e42) 8%, transparent);
  }
}

.empty-hint {
  text-align: center;
  color: #aaa;
  font-size: 13px;
  padding: 40px 16px;
}

.panel-footer {
  padding: 12px 16px;
  border-top: 1px solid #e5e6e8;
  font-size: 12px;
  color: #8f959e;
}

/* ─── 拖拽手柄（与面板内部分割线统一：1px #e5e6e8 细线） ─── */
.drag-handle {
  width: 8px;
  flex-shrink: 0;
  cursor: col-resize;
  background: transparent;
  position: relative;
  z-index: 100;                     /* 关键：高于 right-panel 的 z-index */
  pointer-events: auto;
  user-select: none;
  touch-action: none;

  /* 热区：向上向下扩展，与右侧面板同高 */
  &::before {
    content: '';
    position: absolute;
    top: -8px;
    bottom: -8px;
    left: 0;
    right: 0;
  }

  /* 默认态：中间一条细线 */
  &::after {
    content: '';
    position: absolute;
    top: 12px;
    bottom: 12px;
    left: 50%;
    transform: translateX(-50%);
    width: 2px;
    border-radius: 999px;
    background: var(--notes-line, #d5d8dc);
    transition: background .15s, width .15s;
  }

  &:hover::after {
    width: 3px;
    background: var(--notes-accent, #3370ff);
  }

  &:active::after {
    width: 4px;
    background: var(--notes-accent, #3370ff);
    box-shadow: 0 0 0 3px color-mix(in srgb, var(--notes-accent, #3370ff) 14%, transparent);
  }
}

.left-handle { margin: 0 -4px; }
.right-handle { margin: 0 -4px; }

/* ─── 中间编辑区 ─────────────────────────────────── */
.center-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--notes-bg, #FFFFFF);
  border: 1px solid #E2E7EE;
  border-radius: 14px;
  margin: 0;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}

.editor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 24px;
  border-bottom: 1px solid #EEF1F5;
  border-radius: 0;
  gap: 14px;
  flex-shrink: 0;
  background: var(--notes-bg, #FFFFFF);
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #8f959e;

  i { font-style: normal; color: #c5c7ca; }
  b { font-weight: 500; color: inherit; }
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.local-view-switch {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--notes-line, #e5e6e8);
  border-radius: 8px;
  background: color-mix(in srgb, var(--notes-bg, #fff) 75%, var(--notes-fg, #1f2329) 25%);
}

.local-view-switch button {
  border: 0;
  border-radius: 6px;
  padding: 5px 8px;
  background: transparent;
  color: #8f959e;
  font-size: 11px;
  cursor: pointer;
}

.local-view-switch button.active {
  background: var(--notes-bg, #fff);
  color: var(--notes-accent, #164e42);
  box-shadow: 0 1px 4px color-mix(in srgb, var(--notes-fg, #1f2329) 10%, transparent);
}

.title-stack {
  display: grid;
  gap: 1px;
  min-width: 220px;
}

.title-input {
  border: 0;
  outline: none;
  font-size: 16px;
  font-weight: 500;
  padding: 6px 8px;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  min-width: 200px;

  &:focus { background: #f5f6f7; }
}

.destination-input {
  border: 0;
  outline: 0;
  padding: 0 8px 4px;
  background: transparent;
  color: var(--ink-3, #8c9993);
  font-size: 10px;
  letter-spacing: .02em;
  &::placeholder { color: var(--ink-3, #8c9993); }
  &:focus { color: var(--notes-accent, #164e42); }
}

.tool-group {
  display: flex;
  align-items: center;
  gap: 4px;
}

.publish-btn {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--sunset, #f27a4f);
  border-radius: 8px;
  background: var(--sunset, #f27a4f);
  color: #fff;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
  white-space: nowrap;
  transition: transform .15s, box-shadow .15s, opacity .15s;
  &:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 5px 14px color-mix(in srgb, var(--sunset) 24%, transparent); }
  &:disabled { opacity: .55; cursor: wait; }
}

.format-toolbar {
  min-height: 42px;
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 6px 16px;
  border-bottom: 1px solid #EEF1F5;
  background: color-mix(in srgb, var(--notes-bg, #FFFFFF) 97%, var(--notes-fg, #1F2329) 3%);
  flex-wrap: wrap;
}
.format-select {
  height: 28px;
  border: 1px solid transparent;
  border-radius: 7px;
  padding: 0 7px;
  background: transparent;
  color: var(--notes-fg, #1f2329);
  font-size: 12px;
  outline: 0;
  cursor: pointer;
  &:hover, &:focus { background: var(--notes-bg, #fff); border-color: var(--notes-line, #e5e6e8); }
}
.format-btn {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--notes-fg, #1f2329);
  font: 800 14px Georgia, serif;
  cursor: pointer;
  transition: background .15s, color .15s, transform .15s;
  &:hover { background: var(--notes-wash, #f0f1f2); color: var(--notes-accent, #3370ff); transform: translateY(-1px); }
}
.format-btn.italic { font-style: italic; }
.format-btn.underline { text-decoration: underline; }
.format-separator { width: 1px; height: 20px; background: var(--notes-line, #e5e6e8); margin: 0 3px; }
.format-spacer { flex: 1; }
.color-dot { width: 14px; height: 14px; border: 2px solid var(--notes-bg, #fff); border-radius: 50%; box-shadow: 0 0 0 1px var(--notes-line); cursor: pointer; &:hover { transform: scale(1.15); } }
.color-dot--forest { background: var(--forest); }
.color-dot--sunset { background: var(--sunset); }
.color-dot--blue { background: #5378ff; }
.insert-image-tool { height: 28px; border: 1px solid #D2E3FC; border-radius: 999px; padding: 0 12px; background: #E8F0FE; color: #0B57D0; font-size: 12px; font-weight: 600; cursor: pointer; transition: background .15s; &:hover:not(:disabled) { background: #D2E3FC; } &:disabled { opacity: .55; cursor: wait; } }

.theme-popover-wrap { position: relative; }
.theme-popover {
  position: absolute;
  top: calc(100% + 10px);
  right: 0;
  z-index: 100;
  width: 286px;
  padding: 14px;
  border: 1px solid #dfe2e6;
  border-radius: 10px;
  background: var(--card, #fffdf8);
  box-shadow: 0 12px 30px rgba(0,0,0,.14);
}
.popover-title { font-size: 13px; font-weight: 700; margin-bottom: 10px; }
.popover-color-row { display: grid; grid-template-columns: 62px 32px 1fr; align-items: center; gap: 8px; margin: 8px 0; font-size: 12px; color: #646a73; }
.popover-color-row input[type='color'] { width: 28px; height: 28px; border: 1px solid #d8dade; border-radius: 5px; padding: 2px; background: #fff; }
.hex-input { min-width: 0; width: 100%; border: 1px solid #d8dade; border-radius: 5px; padding: 6px 7px; font: 12px ui-monospace, monospace; color: #1f2329; background: #fff; }
.theme-row .hex-input { width: 96px; }
.compact-presets { display: flex; flex-wrap: wrap; gap: 5px; margin: 12px 0; }
.compact-presets .preset-btn { padding: 4px 7px; font-size: 11px; }

.tool-btn {
  padding: 6px 10px;
  border: 1px solid #d8dade;
  border-radius: 6px;
  background: #fff;
  color: #646a73;
  font-size: 13px;
  cursor: pointer;
  transition: all .15s;

  &.active, &:hover {
    color: #245bdb;
    border-color: #9bb8ff;
    background: #f4f7ff;
  }
}

.save-indicator {
  font-size: 12px;
  color: #8f959e;

  &.saving { color: #f0a020; }
}

.save-btn {
  padding: 7px 16px;
  border: 0;
  border-radius: 999px;
  background: #0B57D0 !important;
  color: #FFFFFF;
  font-weight: 600;
  font-size: 12.5px;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(11, 87, 208, 0.2);
  transition: background .15s, transform .15s;

  &:hover:not(:disabled) { background: #0842A0 !important; transform: translateY(-1px); }
  &:disabled { opacity: .5; cursor: not-allowed; }
}

.editor-body {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  contain: layout paint;
}

.local-source-editor-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: color-mix(in srgb, var(--notes-bg, #fff) 96%, var(--notes-fg, #1f2329) 4%);
}

.local-source-toolbar,
.local-source-footer {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 22px;
  color: #8f959e;
  font-size: 11px;
  border-bottom: 1px solid var(--notes-line, #e5e6e8);
}

.local-source-toolbar-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  min-width: 0;
}

.local-insert-image-btn,
.local-source-switch-btn {
  flex-shrink: 0;
  padding: 5px 9px;
  border: 1px solid var(--notes-line, #d8dade);
  border-radius: 7px;
  background: var(--notes-bg, #fff);
  color: var(--notes-accent, #164e42);
  font-size: 11px;
  cursor: pointer;
}

.local-insert-image-btn:hover,
.local-source-switch-btn:hover {
  border-color: var(--notes-accent, #164e42);
  background: var(--notes-wash, #f0f1f2);
}

.local-insert-image-btn:disabled {
  opacity: .55;
  cursor: wait;
}

.local-source-footer {
  border-top: 1px solid var(--notes-line, #e5e6e8);
  border-bottom: 0;
}

.local-source-path {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--notes-fg, #1f2329);
  font: 600 11px/1.4 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.local-source-editor {
  flex: 1;
  min-height: 0;
  width: 100%;
  resize: none;
  border: 0;
  outline: 0;
  padding: 24px 30px 120px;
  background: transparent;
  color: var(--notes-fg, #1f2329);
  font: 13px/1.8 ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace;
  tab-size: 2;
  white-space: pre;
  overflow: auto;
  caret-color: var(--notes-accent, #164e42);

  &:focus {
    box-shadow: inset 2px 0 0 var(--notes-accent, #164e42);
  }
}

.local-source-badge {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 4px 8px;
  border: 1px solid color-mix(in srgb, var(--notes-accent, #164e42) 24%, transparent);
  border-radius: 999px;
  color: var(--notes-accent, #164e42);
  font-size: 10px;
}

.local-info-row {
  align-items: flex-start !important;

  b {
    min-width: 0;
    overflow-wrap: anywhere;
    text-align: right;
  }
}


.editor-area {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.editor-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;

  &.half { flex: 0.5; }
}

/* 协同模式：左侧继续编辑，右侧实时渲染；两栏各自滚动，不挤压页面高度。 */
.editor-area.is-collaborative {
  .editor-main {
    flex: 1 1 50%;
    min-width: 0;
    border-right: 1px solid var(--notes-line, #e5e6e8);
  }
}

.collaboration-source-pane {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: color-mix(in srgb, var(--notes-bg, #fff) 96%, var(--notes-fg, #1f2329) 4%);
}

.collaboration-source-header {
  flex-shrink: 0;
  min-height: 44px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 6px 16px;
  border-bottom: 1px solid var(--notes-line, #e5e6e8);
  color: var(--notes-fg, #1f2329);
  font-size: 12px;
  font-weight: 700;

  small {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--ink-3, #8c9993);
    font-size: 10px;
    font-weight: 500;
  }
}

.collaboration-source-editor {
  flex: 1;
  min-height: 0;
  width: 100%;
  resize: none;
  border: 0;
  outline: 0;
  padding: 34px 30px 110px;
  background: transparent;
  color: var(--notes-fg, #1f2329);
  font: 13px/1.8 ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace;
  tab-size: 2;
  white-space: pre;
  overflow: auto;
  caret-color: var(--notes-accent, #164e42);

  &:focus {
    box-shadow: inset 2px 0 0 var(--notes-accent, #164e42);
  }
}

.collaboration-preview-pane {
  flex: 1 1 50%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: color-mix(in srgb, var(--notes-bg, #fff) 96%, var(--notes-fg, #1f2329) 4%);
}

.collaboration-preview-header {
  flex-shrink: 0;
  min-height: 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 16px;
  border-bottom: 1px solid var(--notes-line, #e5e6e8);
  color: var(--notes-accent, #164e42);
  font-size: 12px;
  font-weight: 700;

  small {
    color: var(--ink-3, #8c9993);
    font-size: 10px;
    font-weight: 500;
  }
}

.collaboration-preview-content {
  width: 100%;
  padding: 34px 34px 110px;
  cursor: default;

  .img-grip { display: none; }
  img { cursor: default; }
}

.md-textarea {
  flex: 1;
  width: 100%;
  padding: 48px 72px 120px;
  border: 0;
  outline: none;
  resize: none;
  font-family: 'SF Mono', 'Menlo', 'Consolas', 'Liberation Mono', 'Courier New', monospace;
  font-size: 15px;
  line-height: 1.8;
  color: inherit;
  background: transparent;
  tab-size: 2;
  overflow-y: auto;

  &::placeholder {
    color: #c5c7ca;
    white-space: pre;
  }
}

.editor-preview {
  flex: 0.5;
  display: flex;
  flex-direction: column;
  border-left: 1px solid #e5e6e8;
  overflow: hidden;
  background: var(--notes-bg, #fff);

  .preview-label {
    padding: 8px 16px;
    font-size: 12px;
    color: #8f959e;
    border-bottom: 1px solid #e5e6e8;
    background: #fafafa;
  }
}

.preview-content {
  flex: 1;
  width: min(100%, 1080px);
  margin: 0 auto;
  padding: 52px 64px 140px;
  overflow-y: auto;
  line-height: 1.8;
}

.live-editor {
  outline: none;
  cursor: text;
  min-height: 100%;
}
.live-editor:focus { box-shadow: inset 0 0 0 1px rgba(51,112,255,.12); }

.preview-content.scrollable { overflow-y: auto; }

.empty-state {
  flex: 1;
  display: grid;
  align-content: center;
  justify-items: center;
  gap: 14px;
  color: #c5c7ca;
  font-size: 14px;
}

.empty-state-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.empty-state-primary,
.empty-state-secondary {
  border-radius: 8px;
  padding: 8px 13px;
  font-size: 12px;
  cursor: pointer;
}

.empty-state-primary {
  border: 0;
  color: #fff;
  font-weight: 700;
}

.empty-state-secondary {
  border: 1px solid var(--notes-line, #d8dade);
  background: var(--notes-bg, #fff);
  color: var(--notes-accent, #164e42);
}

/* ─── 右侧面板（卡片式布局，每个 section 独立成卡片） ────────────────── */
.right-panel {
  flex: 0 0 auto;
  width: 300px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  box-sizing: border-box;
  border: 1px solid #E2E7EE;
  border-radius: 14px;
  padding: 0 12px 12px;
  margin: 0;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
  overflow: hidden;
  background: var(--notes-bg, #FFFFFF);
  position: relative;
  z-index: 2;
}

.right-panel::-webkit-scrollbar { width: 6px; }
.right-panel::-webkit-scrollbar-thumb { background: rgba(15, 23, 42, 0.14); border-radius: 999px; }
.right-panel::-webkit-scrollbar-track { background: transparent; }

/* 每个 section 独立成卡片 */
.panel-section {
  position: relative;
  display: flex;
  flex-direction: column;
  flex: 0 0 auto;
  flex-shrink: 0;
  min-height: 44px;
  background: #FFFFFF;
  border: 1px solid #E8ECF2;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: none;
}

/* 折叠状态：只保留 header，隐藏 body */
.panel-section.collapsed {
  max-height: 44px !important;
  overflow: hidden;
}

.panel-section.collapsed .section-header {
  border-bottom: 0;
}

.panel-section.collapsed .section-body {
  display: none;
}

/* section 之间的上下拖拽把手：极简柔和细条，悬浮时高亮 */
.section-drag-handle {
  height: 10px;
  margin: 1px 0;
  cursor: row-resize;
  position: relative;
  flex-shrink: 0;
  user-select: none;
  touch-action: none;
  display: flex;
  align-items: center;

  &::before {
    content: '';
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    width: 32px;
    height: 3px;
    border-radius: 999px;
    background: #E2E7EE;
    transition: background .15s ease, width .15s ease;
  }

  &:hover::before,
  &:active::before {
    width: 48px;
    background: #0B57D0;
  }
}

/* 折叠状态：只保留 header，隐藏 body */
.panel-section.collapsed {
  max-height: 46px !important;   /* 只显示 header 的高度 */
  overflow: hidden;
}

.panel-section.collapsed .section-header {
  border-bottom: 0;
}

.panel-section.collapsed .section-body {
  display: none;
}

/* section 之间的上下拖拽把手 */
.section-resize {
  display: flex;
  flex-direction: column;
  flex: 0 0 auto;
  min-height: 0;
}

/* 可拖拽的 section-body（flex:1 让它填充剩余空间） */
.panel-section .section-body {
  flex: 1 1 auto;
  overflow: auto;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  color: inherit;
  border-bottom: 1px solid #eef0f2;   /* 卡片内 header 与 body 的分隔 */
  background: #fafbfc;

  &:hover { background: #f0f2f4; }
}

.collapse-icon { font-size: 10px; color: #8f959e; }

.section-body {
  position: relative;
  flex: 0 0 auto;                  /* 不拉伸不压缩，按内容高度 */
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 42px;
  max-height: none;                /* 不限制最大高度 */
  overflow: visible;               /* 内容超出时显示（由父容器滚动） */
  background: transparent;         /* 透明，用 section 背景 */
}
.section-body.outline { min-height: 120px; overflow-y: auto; max-height: 60vh; }

/* 主题设置 */
.theme-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;

  label {
    width: 56px;
    color: #646a73;
  }

  input[type="color"] {
    width: 32px;
    height: 28px;
    border: 1px solid #d8dade;
    border-radius: 4px;
    cursor: pointer;
    padding: 2px;
  }

  .hex {
    font-family: monospace;
    font-size: 12px;
    color: #8f959e;
  }
}

.theme-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.preset-btn {
  padding: 5px 11px;
  border: 1px solid;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: transform .14s ease, box-shadow .14s ease;

  &:hover { transform: translateY(-1px); box-shadow: 0 2px 6px rgba(15, 23, 42, 0.08); }
}

.theme-save-btn {
  padding: 8px 14px;
  border: 0;
  border-radius: 999px;
  background: #0B57D0 !important;
  color: #FFFFFF;
  font-weight: 600;
  font-size: 12.5px;
  cursor: pointer;
  transition: background .15s ease;

  &:hover { background: #0842A0 !important; }
}

/* 大纲 */
.outline {
  max-height: 200px;
  overflow-y: auto;
}

.outline-item {
  padding: 6px 12px;
  font-size: 13px;
  cursor: pointer;
  border-radius: 4px;
  transition: background .1s;

  &:hover { background: #f5f6f7; }
}

/* 文档信息 */
.info {
  .info-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 10px 12px;
    margin: 4px 0;
    background: #f7f8fa;
    border-radius: 8px;
    font-size: 13px;
    line-height: 1.5;

    &:last-of-type { margin-bottom: 8px; }
    
    span {
      color: #8c99a8;
      font-size: 12px;
      font-weight: 500;
      letter-spacing: 0.3px;
    }
    
    b { 
      font-weight: 600; 
      color: #1f2329;
      font-size: 14px;
    }
  }
    b { color: inherit; font-weight: 500; }
}

.danger-btn {
  width: 100%;
  padding: 8px;
  border: 1px solid #f5c6cb;
  border-radius: 6px;
  background: #fff;
  color: #c33;
  font-size: 13px;
  cursor: pointer;
  margin-top: 8px;
  transition: all .15s;

  &:hover { background: #fde8e8; }
}

/* 快捷键 */
.shortcuts {
  .shortcut-row {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: #646a73;
    padding: 2px 0;
  }

  kbd {
    display: inline-block;
    padding: 2px 6px;
    border: 1px solid #d8dade;
    border-radius: 3px;
    background: #f5f6f7;
    font-family: monospace;
    font-size: 11px;
    color: #646a73;
  }
}

/* ─── Markdown 预览样式（Cursor/GitHub 宽松排版） ────── */
/* 必须用 :deep()：v-html 注入的内容不带 scoped 属性，普通选择器无法命中 */
:deep(.markdown-body) {
  font-size: 16px;
  line-height: 1.75;
  letter-spacing: .01em;
  color: var(--notes-fg, #1f2329);
  word-break: break-word;

  h1, h2, h3, h4, h5, h6 {
    font-weight: 650;
    line-height: 1.35;
    margin-top: 2.1em;
    margin-bottom: .7em;
  }

  h1 {
    font-size: 2em;
    border-bottom: 1px solid #e5e6e8;
    padding-bottom: 10px;
    margin-top: 0;
  }

  h2 {
    font-size: 1.5em;
    border-bottom: 1px solid #e5e6e8;
    padding-bottom: 8px;
  }

  h3 { font-size: 1.25em; }
  h4 { font-size: 1em; }
  h5 { font-size: 0.9em; }
  h6 { font-size: 0.85em; color: #646a73; }

  p { margin: 1em 0; line-height: 1.8; }

  ul, ol {
    padding-left: 1.8em;
    margin: 1em 0;

    li {
      margin: .35em 0;
      line-height: 1.7;
    }

    ul, ol { margin: 8px 0; }
  }

  blockquote {
    border-left: 4px solid var(--notes-accent, #3370ff);
    padding: 4px 16px;
    color: #646a73;
    margin: 18px 0;
    background: rgba(51, 112, 255, 0.04);
    border-radius: 0 6px 6px 0;

    p { margin: 12px 0; }
  }

  code {
    padding: 3px 6px;
    border-radius: 5px;
    background: #f0f2f4;
    color: #b42318;
    font-family: 'SF Mono', ui-monospace, 'Menlo', monospace;
    font-size: 0.88em;
  }

  pre {
    position: relative;
    padding: 36px 18px 16px;
    border-radius: 12px;
    background: #F6F8FA;
    border: 1px solid #E2E7EE;
    box-shadow: none;
    color: #1F2329;
    overflow-x: auto;
    margin: 18px 0;
    font-size: 13.5px;
    line-height: 1.7;

    code {
      padding: 0;
      background: transparent;
      color: inherit;
      font-size: inherit;
      font-family: 'SF Mono', ui-monospace, 'Menlo', 'Consolas', monospace;
    }

    .code-comment { color: #6E7781; font-style: italic; }
    .code-string { color: #0A3069; }
    .code-keyword { color: #0550AE; font-weight: 600; }

    /* 语言角标 */
    &::after {
      content: attr(data-lang);
      position: absolute;
      top: 10px;
      right: 12px;
      font-size: 10.5px;
      font-weight: 600;
      letter-spacing: .05em;
      text-transform: uppercase;
      color: #5F6368;
      background: #EDF2F8;
      border: 1px solid #DDE3EC;
      padding: 2px 8px;
      border-radius: 6px;
      font-family: 'SF Mono', ui-monospace, 'Menlo', monospace;
    }
  }

  hr {
    border: none;
    height: 1px;
    background: #E2E7EE;
    margin: 28px 0;
    flex-shrink: 0;
  }

  /* 链接样式 - 指针光标 + hover 效果 */
  a, .md-link {
    color: var(--notes-accent, #3370ff);
    text-decoration: none;
    cursor: pointer;
    transition: color .15s, text-decoration .15s;

    &:hover {
      color: var(--notes-accent, #3370ff);
      text-decoration: underline;
    }

    &:active {
      opacity: 0.7;
    }
  }

  img {
    max-width: 100%;
    border-radius: 10px;
    margin: 12px 0;
  }

  /* 图片行：支持缩放（右下角手柄）与拖动搬移 */
  .img-line {
    position: relative;
    display: inline-block;
    max-width: 100%;
    margin: 12px 0;

    img {
      display: block;
      max-width: 100%;
      border-radius: 10px;
      margin: 0;
      cursor: move;
      -webkit-user-drag: element;
    }

    .img-grip {
      position: absolute;
      right: -7px;
      bottom: -7px;
      width: 15px;
      height: 15px;
      border-radius: 50%;
      background: var(--notes-accent, #3370ff);
      border: 2px solid #fff;
      box-shadow: 0 1px 4px rgba(0, 0, 0, .25);
      cursor: nwse-resize;
      opacity: 0;
      transition: opacity .15s;
      z-index: 2;
    }

    &:hover .img-grip {
      opacity: 1;
    }
  }

  p.img-line, .img-line { display: block; }

  table {
    border-collapse: collapse;
    width: 100%;
    margin: 20px 0;
    border: 1px solid var(--notes-border, #cdd3dc);
    border-radius: 10px;
    overflow: hidden;
    background: var(--notes-tbl-bg, #ffffff);
    box-shadow: 0 1px 3px rgba(20, 24, 30, 0.06);

    th, td {
      border: 1px solid var(--notes-border, #d9dde3);
      padding: 11px 15px;
      text-align: left;
      min-width: 110px;
      line-height: 1.65;
    }

    th {
      background: var(--notes-tbl-head, #f3f5f8);
      font-weight: 700;
      color: var(--notes-fg, #1f2329);
    }

    /* 斑马纹 + hover 行高亮（Office Viewer 观感） */
    tbody tr:nth-child(even) { background: rgba(0, 0, 0, 0.025); }
    tbody tr:hover { background: rgba(51, 112, 255, 0.05); }
  }

  input[type="checkbox"] {
    margin-right: 6px;
    cursor: pointer;
  }
}

/* ─── 响应式 ─────────────────────────────────── */
@media (max-width: 900px) {
  .preview-content,
  .md-textarea {
    padding: 24px 20px;
  }
}
</style>
