<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useContentTabsStore } from '@/stores/contentTabs'
import { createNoteRevision, listPublicNotes } from '@/api/user'
import { createNote } from '@/api/note'
import { ElMessage } from 'element-plus'
const router = useRouter(); const query = ref(''); const active = ref('为你推荐'); const tabs = ['为你推荐','城市灵感','AI 路线','避坑经验']; const notes = ref<any[]>([]); const loading = ref(false)
// 点击卡片 → 打开浏览器式卡片标签（覆盖中间查看，底层页面不受影响）
const contentTabs = useContentTabsStore()
function openNote(note: any) {
  contentTabs.open({
    kind: 'explore-note',
    title: `发现灵感-${note.city || note.title.slice(0, 6)}`,
    data: {
      // 帖子标题不是唯一键，必须使用数据库帖子 ID，避免同标题或旧标签串到别的帖子。
      keyId: note.id,
      id: note.id,
      socialNoteId: note.id ? Number(note.id) : undefined,
      travelNoteId: note.travel_note_id || note.travelNoteId,
      sourceType: 'social',
      title: note.title,
      content: note.content || note.title,
      image: note.image || '',
      author: note.author || '',
      userId: note.user_id || note.userId || '',
      authorAvatar: note.author_avatar || note.authorAvatar || '',
      city: note.city || '',
      tags: note.tags || [],
      likes: note.likes || ''
    }
  })
  router.push({ path: '/card-detail', query: { noteId: String(note.id) } })
}
function openUserProfile(userId?: string) {
  if (userId) router.push(`/users/${encodeURIComponent(userId)}`)
}
async function saveFeedNote(note: any) {
  try {
    if (note.id) {
      const copied = await createNote({
        title: `${note.title || '旅行笔记'} · 副本`, content: note.content || '',
        destination: note.destination || note.city || '', coverUrl: note.cover_url || note.coverUrl || note.image || '',
        visibility: 'private', sourceSocialNoteId: Number(note.id)
      })
      await createNoteRevision(Number(note.id), {
        sourceType: 'copy', privateNoteId: copied.id, title: note.title, content: note.content || '',
        coverUrl: note.cover_url || note.coverUrl || note.image || '',
        destination: note.destination || note.city || '', tags: note.tags || []
      })
      ElMessage.success('已复制到我的笔记，发布时会进行版权检测')
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '保存失败，请稍后重试')
  }
}
function normalizeImageUrl(value: unknown): string {
  const raw = String(value || '').trim()
  if (!raw) return ''
  if (/^(https?:|data:|blob:)/i.test(raw)) return raw
  const base = String(import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
  return `${base}${raw.startsWith('/') ? raw : `/${raw}`}`
}
function extractFirstImage(content: unknown): string {
  const source = String(content || '')
  const html = source.match(/<img[^>]+src=["']([^"']+)["']/i)?.[1]
  if (html) return html
  const markdown = source.match(/!\[[^\]]*\]\(([^)\s]+)(?:\s+[^)]*)?\)/i)?.[1]
  return markdown || ''
}
function normalizeNote(note: any) {
  const tags = Array.isArray(note.tags) ? note.tags : typeof note.tags === 'string' ? (() => { try { return JSON.parse(note.tags) } catch { return note.tags.split(',').filter(Boolean) } })() : []
  const image = normalizeImageUrl(note.coverUrl || note.cover_url || extractFirstImage(note.content))
  return { ...note, author: note.author || note.authorName || note.author_name || '旅行者', city: note.destination || '', image, imageFailed: false, likes: note.likeCount ?? note.like_count ?? 0, tags }
}
const visibleNotes = computed(() => {
  const q = query.value.trim().toLowerCase()
  return notes.value.filter(note => {
    const text = [note.title, note.author, note.city, note.content, ...(note.tags || [])].join(' ').toLowerCase()
    const matchesQuery = !q || text.includes(q)
    const matchesTab = active.value === '为你推荐' || active.value === '城市灵感' || (active.value === 'AI 路线' && /路线|自驾|规划|行程/.test(text)) || (active.value === '避坑经验' && /避坑|建议|注意|经验/.test(text))
    return matchesQuery && matchesTab
  })
})
onMounted(async()=>{
  loading.value = true
  try { notes.value = ((await listPublicNotes()).data || []).map(normalizeNote) } catch { notes.value = [] }
  finally { loading.value = false }
})
</script>
<template>
 <main class="explore-page"><header class="explore-head"><div><p class="eyebrow">DISCOVER YOUR NEXT ROAM</p><h1>发现下一段旅程</h1><p class="sub">真实经验、可执行路线，以及可以直接交给 Roamly 的灵感。</p></div><div class="head-actions"><button class="profile-pill" @click="router.push('/profile')">我的主页 <span>→</span></button><button class="create-note-btn" @click="router.push('/publish')">＋ 发布笔记</button></div></header>
 <div class="search-bar"><span>⌕</span><input v-model="query" placeholder="搜索目的地、旅行笔记或路线"/><kbd>⌘ K</kbd></div>
 <nav class="feed-tabs"><button v-for="tab in tabs" :key="tab" :class="{active:active===tab}" @click="active=tab">{{tab}}</button></nav>
 <section class="ai-callout"><div class="ai-symbol">✦</div><div><span class="eyebrow">ROAMLY AI PLANNER</span><h2>不知道去哪玩？把一句想法变成完整行程。</h2><p>“8 月，想去海边，预算 5000，两个人，节奏别太赶。”</p></div><button @click="router.push('/')">开始规划 <span>→</span></button></section>
 <section class="section-head"><div><span class="eyebrow">TRAVEL NOTES</span><h2>今日热门旅行</h2></div><button class="text-btn">查看全部 →</button></section>
 <section class="feed-grid" v-loading="loading"><article v-for="note in visibleNotes" :key="note.id" class="note-card" @click="openNote(note)"><div class="note-cover"><img v-if="note.image && !note.imageFailed" :src="note.image" :alt="note.city" loading="lazy" @error="note.imageFailed = true"/><span v-else class="note-cover-placeholder">{{ note.city || 'ROAMLY' }}</span><button class="save" title="复制到我的笔记" @click.stop="saveFeedNote(note)">☆</button><div class="hover-open">查看并编辑 →</div></div><div class="note-body"><div class="note-author"><button class="author-link" @click.stop="openUserProfile(note.user_id || note.userId)"><span class="avatar"><img v-if="normalizeImageUrl(note.author_avatar || note.authorAvatar)" :src="normalizeImageUrl(note.author_avatar || note.authorAvatar)" alt="" /><span v-else>{{note.author.charAt(0)}}</span></span><span>{{note.author}}</span></button><span v-if="note.city" class="dot">·</span><span>{{note.city}}</span></div><h3>{{note.title}}</h3><div class="tag-row"><span v-for="tag in note.tags" :key="tag">{{tag}}</span></div><div class="note-meta"><span>♡ {{note.likes}}</span><span>◌ 可编辑笔记</span></div></div></article><p v-if="!loading && !visibleNotes.length" class="empty-feed">暂时还没有已发布的旅行笔记，发布第一篇吧。</p></section>
 <section class="creator-strip"><div><span class="eyebrow">MAKE IT YOURS</span><h2>你的旅行，值得被认真记录。</h2><p>生成一份计划，出发后变成一篇可分享的旅行笔记。</p></div><button @click="router.push({ path: '/chat', query: { mode: 'agent' } })">和 Agent 一起开始 <span>→</span></button></section></main>
</template>
<style scoped>
.explore-page {
  width: min(1160px, calc(100% - 48px));
  margin: 0 auto;
  padding: 28px 0 72px;
  color: #1F2329;
}

.explore-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 20px;
  margin-bottom: 20px;
  padding: 24px 28px;
  border: 1px solid #E5E9F0;
  border-radius: 20px;
  background: #FFFFFF;
}

.eyebrow {
  color: #0B57D0;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

h1 {
  margin: 6px 0;
  font: 700 30px/1.2 Manrope, "Noto Sans SC", -apple-system, sans-serif;
  color: #1F2329;
  letter-spacing: -0.025em;
}

.sub {
  color: #5F6368;
  font-size: 13.5px;
  line-height: 1.6;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.profile-pill,
.text-btn {
  border: 1px solid #DADCE0;
  background: #FFFFFF;
  color: #3C4043;
  padding: 8px 16px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.profile-pill:hover,
.text-btn:hover {
  background: #F0F4F9;
  border-color: #C7D2E0;
}

.create-note-btn {
  border: 0;
  border-radius: 999px;
  padding: 9px 18px;
  background: #0B57D0;
  color: #FFFFFF;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, transform 0.15s ease;
}

.create-note-btn:hover {
  background: #0842A0;
  transform: translateY(-1px);
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 18px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: #F0F4F9;
  transition: background 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease;
}

.search-bar:hover {
  background: #E8EFF7;
}

.search-bar:focus-within {
  background: #FFFFFF;
  border-color: #0B57D0;
  box-shadow: 0 0 0 3px rgba(11, 87, 208, 0.12);
}

.search-bar span {
  font-size: 18px;
  color: #5F6368;
}

.search-bar input {
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  font: 500 14px inherit;
  color: #1F2329;
}

.search-bar input::placeholder {
  color: #8C959F;
}

.search-bar kbd {
  color: #5F6368;
  font-size: 11px;
  border: 1px solid #DADCE0;
  background: #FFFFFF;
  padding: 2px 7px;
  border-radius: 6px;
}

.feed-tabs {
  display: flex;
  gap: 8px;
  margin: 18px 0 22px;
}

.feed-tabs button {
  padding: 7px 16px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: transparent;
  color: #5F6368;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.feed-tabs button:hover {
  background: #F0F4F9;
  color: #1F2329;
}

.feed-tabs button.active {
  background: #E8F0FE;
  color: #0B57D0;
}

.ai-callout,
.creator-strip {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 24px;
  border: 1px solid #D2E3FC;
  border-radius: 18px;
  background: #F7FAFF;
  margin-bottom: 32px;
}

.ai-symbol {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: #E8F0FE;
  color: #0B57D0;
  font-size: 20px;
  flex-shrink: 0;
}

.ai-callout h2,
.creator-strip h2 {
  margin: 4px 0;
  font-size: 17px;
  font-weight: 700;
  color: #1F2329;
  letter-spacing: -0.015em;
}

.ai-callout p,
.creator-strip p {
  margin: 0;
  color: #5F6368;
  font-size: 12.5px;
}

.ai-callout button,
.creator-strip button {
  margin-left: auto;
  flex: none;
  border: 0;
  border-radius: 999px;
  padding: 10px 18px;
  background: #0B57D0;
  color: #FFFFFF;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, transform 0.15s ease;
}

.ai-callout button:hover,
.creator-strip button:hover {
  background: #0842A0;
  transform: translateY(-1px);
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 16px;
}

.section-head h2 {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 700;
  color: #1F2329;
  letter-spacing: -0.02em;
}

.feed-grid {
  columns: 3 260px;
  column-gap: 18px;
}

.empty-feed {
  column-span: all;
  padding: 48px 0;
  color: #8C959F;
  text-align: center;
  font-size: 13.5px;
}

.note-card {
  break-inside: avoid;
  margin: 0 0 18px;
  border: 1px solid #E5E9F0;
  border-radius: 16px;
  overflow: hidden;
  background: #FFFFFF;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
  cursor: pointer;
}

.note-card:hover {
  transform: translateY(-2px);
  border-color: #D2E3FC;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.07);
}

.note-cover {
  position: relative;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background: #F0F4F9;
}

.note-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform 0.3s ease;
}

.note-card:hover .note-cover img {
  transform: scale(1.03);
}

.note-cover-placeholder {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  padding: 20px;
  background: #F0F4F9;
  color: #5F6368;
  font: 700 18px Manrope, "Noto Sans SC", sans-serif;
  text-align: center;
}

.save {
  position: absolute;
  right: 10px;
  top: 10px;
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.92);
  color: #3C4043;
  font-size: 17px;
  cursor: pointer;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
}

.save:hover {
  color: #0B57D0;
}

.hover-open {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 16px 14px 10px;
  background: linear-gradient(to top, rgba(15, 23, 42, 0.55), transparent);
  color: #FFFFFF;
  font-size: 12.5px;
  font-weight: 600;
  opacity: 0;
  transition: opacity 0.18s ease;
  text-align: center;
}

.note-card:hover .hover-open {
  opacity: 1;
}

.note-body {
  padding: 14px 16px 15px;
}

.note-author,
.note-meta {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #5F6368;
  font-size: 11.5px;
}

.author-link {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

.author-link:hover {
  color: #0B57D0;
}

.avatar {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #E8F0FE;
  color: #0B57D0;
  font-weight: 700;
  font-size: 11px;
  overflow: hidden;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.dot {
  color: #8C959F;
}

.note-body h3 {
  margin: 9px 0;
  font-size: 15px;
  font-weight: 650;
  line-height: 1.45;
  color: #1F2329;
}

.tag-row {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.tag-row span {
  padding: 3px 9px;
  border-radius: 999px;
  background: #F0F4F9;
  color: #3C4043;
  font-size: 11px;
  font-weight: 500;
}

.note-meta {
  justify-content: space-between;
  padding-top: 10px;
  border-top: 1px solid #EEF1F5;
  color: #8C959F;
}

.creator-strip {
  margin-top: 36px;
  margin-bottom: 0;
}

@media (max-width: 700px) {
  .explore-page { width: calc(100% - 24px); padding-top: 18px; }
  .explore-head { flex-direction: column; align-items: flex-start; padding: 18px; }
  .profile-pill { display: none; }
  h1 { font-size: 24px; }
  .ai-callout, .creator-strip { flex-wrap: wrap; }
  .ai-callout button, .creator-strip button { margin-left: 0; }
  .feed-grid { columns: 2 150px; column-gap: 12px; }
}
</style>
