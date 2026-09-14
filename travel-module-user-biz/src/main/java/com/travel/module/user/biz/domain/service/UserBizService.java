package com.travel.module.user.biz.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travel.common.http.HttpClientSupport;
import com.travel.module.user.biz.infra.persistence.*;
import com.travel.module.user.biz.infra.storage.ImageStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserBizService {

    private final InspirationMapper inspirationMapper;
    private final JourneyMapper journeyMapper;
    private final JourneyPointMapper journeyPointMapper;
    private final JourneyImageMapper journeyImageMapper;
    private final UserPreferenceMapper userPreferenceMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserTravelPreferenceMapper userTravelPreferenceMapper;
    private final TravelNoteMapper travelNoteMapper;
    private final ImageStorageService imageStorageService;
    private final JdbcTemplate jdbcTemplate;

    private final RestTemplate restTemplate = HttpClientSupport.newRestTemplate();

    public List<TravelNotePO> listTravelNotes(String userId) {
        LambdaQueryWrapper<TravelNotePO> w = new LambdaQueryWrapper<>();
        w.eq(TravelNotePO::getUserId, userId).orderByDesc(TravelNotePO::getUpdatedAt);
        return travelNoteMapper.selectList(w);
    }

    public TravelNotePO getTravelNote(Long id) { return travelNoteMapper.selectById(id); }

    public TravelNotePO getSharedTravelNote(String token) {
        return travelNoteMapper.selectOne(new LambdaQueryWrapper<TravelNotePO>().eq(TravelNotePO::getShareToken, token).eq(TravelNotePO::getVisibility, "link"));
    }

    @Transactional
    public TravelNotePO saveTravelNote(TravelNotePO note) {
        if (note.getUserId() == null) note.setUserId("user_001");
        if (note.getTemplateVersion() == null) note.setTemplateVersion(1);
        if (note.getNoteType() == null) note.setNoteType("inspiration");
        if (note.getSourceType() == null) note.setSourceType("manual");
        if (note.getStatus() == null) note.setStatus("draft");
        if (note.getVisibility() == null) note.setVisibility("private");
        if (note.getContentJson() == null || note.getContentJson().isBlank()) note.setContentJson("{\"overview\":{},\"days\":[],\"budget\":{\"items\":[]},\"strategies\":[],\"reminders\":[]}");
        if (note.getShareToken() == null && "link".equals(note.getVisibility())) note.setShareToken(UUID.randomUUID().toString().replace("-", ""));
        if (note.getId() == null) travelNoteMapper.insert(note); else travelNoteMapper.updateById(note);
        return note;
    }

    @Transactional
    public TravelNotePO copyTravelNote(Long id, String userId) {
        TravelNotePO src = travelNoteMapper.selectById(id);
        if (src == null) return null;
        src.setId(null); src.setUserId(userId == null ? "user_001" : userId); src.setSourceType("copy"); src.setStatus("draft"); src.setVisibility("private"); src.setShareToken(null); src.setTitle(src.getTitle() + " · 副本");
        return saveTravelNote(src);
    }

    public void deleteTravelNote(Long id) { travelNoteMapper.deleteById(id); }

    public List<Map<String,Object>> searchUsers(String q) {
        String key = q == null ? "" : q.trim();
        return jdbcTemplate.queryForList("SELECT public_id, COALESCE(NULLIF(nickname,''),user_id) nickname, COALESCE(NULLIF(avatar,''),NULLIF(avatar_url,''),'') avatar FROM user_profile WHERE public_id LIKE ? OR nickname LIKE ? OR user_id LIKE ? LIMIT 20", "%" + key + "%", "%" + key + "%", "%" + key + "%");
    }
    public List<Map<String,Object>> listPublicNotes(int page, int size) { return listPublicNotes(page, size, null, null, null); }
    public List<Map<String,Object>> listPublicNotes(int page, int size, String q, String tag) { return listPublicNotes(page, size, q, tag, null); }
    public List<Map<String,Object>> listPublicNotes(int page, int size, String q, String tag, String ownerId) {
        int safeSize = Math.max(1, Math.min(size, 50));
        int offset = Math.max(0, page) * safeSize;
        StringBuilder sql = new StringBuilder("SELECT n.id,n.user_id,n.source_note_id,n.title,n.content,n.cover_url,n.destination,n.tags,n.state_code,n.moderation_status,n.moderation_score,n.report_count,n.like_count,n.comment_count,n.favorite_count,n.created_at,n.updated_at, COALESCE(NULLIF(n.author_name,''),NULLIF(p.nickname,''),NULLIF(tp.name,''),'旅行者') author, COALESCE(NULLIF(n.author_avatar,''),NULLIF(p.avatar,''),NULLIF(p.avatar_url,''),'') author_avatar FROM social_note n LEFT JOIN user_profile p ON p.public_id=n.user_id OR p.user_id=n.user_id LEFT JOIN user_travel_preference tp ON tp.user_id=n.user_id AND tp.preference_type='default' WHERE n.visibility='public' AND n.status='published' AND COALESCE(n.moderation_status,'approved')='approved'");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (q != null && !q.isBlank()) { sql.append(" AND (n.title LIKE ? OR n.content LIKE ? OR n.destination LIKE ?)"); String key="%"+q.trim()+"%"; args.add(key); args.add(key); args.add(key); }
        if (tag != null && !tag.isBlank()) { sql.append(" AND JSON_CONTAINS(n.tags, JSON_QUOTE(?))"); args.add(tag.trim()); }
        if (ownerId != null && !ownerId.isBlank()) { sql.append(" AND (n.user_id=? OR n.user_id=(SELECT user_id FROM user_profile WHERE public_id=? LIMIT 1))"); args.add(ownerId.trim()); args.add(ownerId.trim()); }
        sql.append(" ORDER BY n.created_at DESC LIMIT ? OFFSET ?"); args.add(safeSize); args.add(offset);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }
    public Map<String,Object> getPublicNote(Long id) {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("SELECT n.*, COALESCE(NULLIF(n.author_name,''),NULLIF(p.nickname,''),NULLIF(tp.name,''),'旅行者') author, COALESCE(NULLIF(n.author_avatar,''),NULLIF(p.avatar,''),NULLIF(p.avatar_url,''),'') author_avatar FROM social_note n LEFT JOIN user_profile p ON p.public_id=n.user_id OR p.user_id=n.user_id LEFT JOIN user_travel_preference tp ON tp.user_id=n.user_id AND tp.preference_type='default' WHERE n.id=? AND n.visibility='public' AND n.status='published' AND COALESCE(n.moderation_status,'approved')='approved'", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String,Object> getPublicUserProfile(String publicId) {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("SELECT p.public_id,p.user_id,COALESCE(NULLIF(p.nickname,''),NULLIF(tp.name,''),p.user_id) nickname,COALESCE(NULLIF(p.avatar,''),NULLIF(p.avatar_url,''),'') avatar,COALESCE(p.bio,'') bio FROM user_profile p LEFT JOIN user_travel_preference tp ON tp.user_id=p.user_id AND tp.preference_type='default' WHERE p.public_id=? OR p.user_id=? LIMIT 1", publicId, publicId);
        if (!rows.isEmpty()) return rows.get(0);
        Map<String,Object> fallback = new LinkedHashMap<>();
        fallback.put("public_id", publicId); fallback.put("user_id", publicId); fallback.put("nickname", "旅行者"); fallback.put("avatar", ""); fallback.put("bio", "");
        return fallback;
    }

    private String randomStateCode() {
        final String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder code = new StringBuilder(8);
        UUID seed = UUID.randomUUID();
        long value = seed.getMostSignificantBits() ^ seed.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) { value = value * 6364136223846793005L + 1442695040888963407L; code.append(alphabet.charAt((int) Math.floorMod(value, alphabet.length()))); }
        return code.toString();
    }

    private int nextRevisionNo(Long sourceNoteId, String contributorId, String sourceType) {
        Integer max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(revision_no),0) FROM social_note_revision WHERE source_note_id=? AND contributor_id=? AND source_type=?", Integer.class, sourceNoteId, contributorId, sourceType);
        return (max == null ? 0 : max) + 1;
    }

    private Long asLong(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "null".equalsIgnoreCase(String.valueOf(value))) return null;
        try { return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private String valueOr(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() || "null".equalsIgnoreCase(text) ? fallback : text;
    }

    @Transactional
    public Map<String,Object> createNoteRevision(Long sourceNoteId, String contributorId, Map<String,Object> body) {
        Map<String,Object> note = getPublicNote(sourceNoteId);
        if (note == null) return Map.of("created", false);
        String contributor = valueOr(contributorId, "user_001");
        String ownerId = valueOr(note.get("user_id"), "");
        String sourceType = "copy".equals(String.valueOf(body.getOrDefault("sourceType", "invite"))) ? "copy" : "invite";
        String revisionCode = randomStateCode();
        int revisionNo = nextRevisionNo(sourceNoteId, contributor, sourceType);
        String status = "copy".equals(sourceType) ? "archived" : "requested";
        String title = valueOr(body.get("title"), valueOr(note.get("title"), "旅行笔记"));
        String content = valueOr(body.get("content"), "copy".equals(sourceType) ? valueOr(note.get("content"), "") : "");
        String cover = valueOr(body.get("coverUrl"), valueOr(note.get("cover_url"), ""));
        String destination = valueOr(body.get("destination"), valueOr(note.get("destination"), ""));
        String tags = body.get("tags") instanceof Collection ? new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body.get("tags")).toString() : valueOr(body.get("tags"), valueOr(note.get("tags"), "[]"));
        String message = valueOr(body.get("message"), "");
        Long privateNoteId = asLong(body.get("privateNoteId"));
        jdbcTemplate.update("INSERT INTO social_note_revision(source_note_id,owner_id,contributor_id,private_note_id,published_note_id,revision_code,revision_no,source_type,status,title,content,cover_url,destination,tags,message) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", sourceNoteId, ownerId, contributor, privateNoteId, null, revisionCode, revisionNo, sourceType, status, title, content, cover, destination, tags, message);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("created", true); result.put("sourceNoteId", sourceNoteId); result.put("revisionCode", revisionCode); result.put("revisionNo", revisionNo); result.put("status", status);
        return result;
    }

    public List<Map<String,Object>> listNoteRevisions(Long sourceNoteId, String userId) {
        return jdbcTemplate.queryForList("SELECT r.*, COALESCE(NULLIF(p.nickname,''),NULLIF(tp.name,''),'协作者') contributor_name, COALESCE(NULLIF(p.avatar,''),NULLIF(p.avatar_url,''),'') contributor_avatar FROM social_note_revision r LEFT JOIN user_profile p ON p.public_id=r.contributor_id OR p.user_id=r.contributor_id LEFT JOIN user_travel_preference tp ON tp.user_id=r.contributor_id AND tp.preference_type='default' WHERE r.source_note_id=? AND (r.owner_id=? OR r.contributor_id=?) ORDER BY r.revision_no DESC, r.created_at DESC", sourceNoteId, userId, userId);
    }

    @Transactional
    public Map<String,Object> submitNoteRevision(Long revisionId, String userId, Map<String,Object> body) {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("SELECT * FROM social_note_revision WHERE id=? LIMIT 1", revisionId);
        if (rows.isEmpty()) return Map.of("updated", false);
        Map<String,Object> revision = rows.get(0);
        if (!Objects.equals(valueOr(revision.get("contributor_id"), ""), userId)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "只有协作者可以提交自己的版本");
        if (!Set.of("approved", "submitted").contains(valueOr(revision.get("status"), ""))) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "当前协作状态不能提交");
        String code = randomStateCode();
        jdbcTemplate.update("UPDATE social_note_revision SET title=?,content=?,cover_url=?,destination=?,tags=?,revision_code=?,revision_no=revision_no+1,status='submitted',updated_at=CURRENT_TIMESTAMP WHERE id=?", valueOr(body.get("title"), valueOr(revision.get("title"), "")), valueOr(body.get("content"), valueOr(revision.get("content"), "")), valueOr(body.get("coverUrl"), valueOr(revision.get("cover_url"), "")), valueOr(body.get("destination"), valueOr(revision.get("destination"), "")), valueOr(body.get("tags"), valueOr(revision.get("tags"), "[]")), code, revisionId);
        return Map.of("updated", true, "status", "submitted", "revisionCode", code, "revisionId", revisionId);
    }

    @Transactional
    public Map<String,Object> reviewNoteRevision(Long revisionId, String userId, String status, String message) {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("SELECT * FROM social_note_revision WHERE id=? LIMIT 1", revisionId);
        if (rows.isEmpty()) return Map.of("updated", false);
        Map<String,Object> revision = rows.get(0);
        if (!Objects.equals(valueOr(revision.get("owner_id"), ""), userId)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "只有原作者可以审核协作版本");
        if (!Set.of("approved", "rejected", "merged").contains(status)) throw new IllegalArgumentException("不支持的审核状态");
        jdbcTemplate.update("UPDATE social_note_revision SET status=?,message=?,reviewed_at=CURRENT_TIMESTAMP,merged_at=CASE WHEN ?='merged' THEN CURRENT_TIMESTAMP ELSE merged_at END WHERE id=?", status, valueOr(message, ""), status, revisionId);
        if ("merged".equals(status)) {
            jdbcTemplate.update("UPDATE social_note SET title=?,content=?,cover_url=?,destination=?,tags=?,state_code=?,moderation_status='approved',review_required=0,updated_at=CURRENT_TIMESTAMP WHERE id=?", revision.get("title"), revision.get("content"), revision.get("cover_url"), revision.get("destination"), revision.get("tags"), revision.get("revision_code"), revision.get("source_note_id"));
            jdbcTemplate.update("UPDATE social_note_revision SET published_note_id=source_note_id WHERE id=?", revisionId);
        }
        return Map.of("updated", true, "status", status, "revisionCode", revision.get("revision_code"));
    }
    @Transactional
    public Map<String,Object> updateSocialNote(Long id, String userId, Map<String,Object> body) {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("SELECT user_id FROM social_note WHERE id=?", id);
        if (rows.isEmpty()) return Map.of("updated", false);
        String owner = String.valueOf(rows.get(0).get("user_id"));
        if (!Objects.equals(owner, userId)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "无权修改该帖子");
        String tags = body.get("tags") instanceof Collection ? new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body.get("tags")).toString() : String.valueOf(body.getOrDefault("tags", "[]"));
        jdbcTemplate.update("UPDATE social_note SET title=?,content=?,cover_url=?,destination=?,tags=?,author_name=?,author_avatar=?,state_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                String.valueOf(body.getOrDefault("title", "旅行笔记")), String.valueOf(body.getOrDefault("content", "")), String.valueOf(body.getOrDefault("coverUrl", "")), String.valueOf(body.getOrDefault("destination", "")), tags,
                String.valueOf(body.getOrDefault("authorName", "")), String.valueOf(body.getOrDefault("authorAvatar", "")), randomStateCode(), id);
        return getPublicNote(id);
    }
    @Transactional
    public TravelNotePO copySocialNote(Long id, String userId) {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("SELECT * FROM social_note WHERE id=? AND visibility='public'", id);
        if (rows.isEmpty()) return null;
        Map<String,Object> source = rows.get(0);
        String copier = valueOr(userId, "user_001");
        Map<String,Object> copyLog = new LinkedHashMap<>();
        copyLog.put("sourceType", "copy"); copyLog.put("title", source.get("title")); copyLog.put("content", source.get("content"));
        copyLog.put("coverUrl", source.get("cover_url")); copyLog.put("destination", source.get("destination")); copyLog.put("tags", source.get("tags"));
        Object linkedId = source.get("travel_note_id");
        if (linkedId instanceof Number) {
            TravelNotePO linked = copyTravelNote(((Number) linkedId).longValue(), copier);
            createNoteRevision(id, copier, copyLog);
            if (linked != null) return linked;
        }
        TravelNotePO copy = new TravelNotePO();
        copy.setUserId(copier);
        copy.setTitle(String.valueOf(source.getOrDefault("title", "旅行笔记")) + " · 副本");
        copy.setDestination(String.valueOf(source.getOrDefault("destination", "")));
        copy.setNoteType("inspiration");
        copy.setSourceType("copy");
        copy.setStatus("draft");
        copy.setVisibility("private");
        copy.setCoverUrl(String.valueOf(source.getOrDefault("cover_url", "")));
        try {
            Map<String,Object> document = new LinkedHashMap<>();
            document.put("version", 1);
            document.put("format", "html");
            document.put("origin", "copy");
            document.put("blocks", List.of(Map.of("id", "body", "type", "rich-text", "html", String.valueOf(source.getOrDefault("content", "")))));
            copy.setContentJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(document));
        } catch (Exception e) {
            copy.setContentJson("{\"version\":1,\"format\":\"html\",\"blocks\":[{\"id\":\"body\",\"type\":\"rich-text\",\"html\":\"\"}]}");
        }
        TravelNotePO saved = saveTravelNote(copy);
        createNoteRevision(id, copier, copyLog);
        return saved;
    }
    @Transactional public void reactNote(Long noteId, String userId, String type) {
        int exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM social_reaction WHERE note_id=? AND user_id=? AND reaction_type=?", Integer.class, noteId, userId, type);
        String count = "like".equals(type) ? "like_count" : "favorite_count";
        if (exists == 0) { jdbcTemplate.update("INSERT INTO social_reaction(note_id,user_id,reaction_type) VALUES(?,?,?)", noteId,userId,type); jdbcTemplate.update("UPDATE social_note SET " + count + "=" + count + "+1 WHERE id=?", noteId); }
        else { jdbcTemplate.update("DELETE FROM social_reaction WHERE note_id=? AND user_id=? AND reaction_type=?", noteId,userId,type); jdbcTemplate.update("UPDATE social_note SET " + count + "=GREATEST(" + count + "-1,0) WHERE id=?", noteId); }
    }
    public void addComment(Long noteId, String userId, String content) { jdbcTemplate.update("INSERT INTO social_comment(note_id,user_id,content) VALUES(?,?,?)", noteId,userId,content); jdbcTemplate.update("UPDATE social_note SET comment_count=comment_count+1 WHERE id=?", noteId); }
    public List<Map<String,Object>> listComments(Long noteId) { return jdbcTemplate.queryForList("SELECT c.*, COALESCE(NULLIF(p.nickname,''),NULLIF(tp.name,''),'旅行者') nickname, COALESCE(NULLIF(p.avatar,''),NULLIF(p.avatar_url,''),'') avatar FROM social_comment c LEFT JOIN user_profile p ON p.public_id=c.user_id OR p.user_id=c.user_id LEFT JOIN user_travel_preference tp ON tp.user_id=c.user_id AND tp.preference_type='default' WHERE c.note_id=? ORDER BY c.created_at ASC", noteId); }
    public void requestFriend(String from, String to, String message) { jdbcTemplate.update("INSERT INTO social_friend_request(requester_id,receiver_id,message) VALUES(?,?,?) ON DUPLICATE KEY UPDATE status='pending',message=VALUES(message)", from,to,message == null ? "" : message); }
    public Map<String,Object> publishSocialNote(Map<String,Object> body) {
        String userId = valueOr(body.get("userId"), "user_001");
        String tags = body.get("tags") instanceof java.util.Collection ? new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body.get("tags")).toString() : valueOr(body.get("tags"), "[]");
        String title = valueOr(body.get("title"), "旅行笔记");
        String content = valueOr(body.get("content"), "");
        String cover = valueOr(body.get("coverUrl"), "");
        String destination = valueOr(body.get("destination"), "");
        Long sourceNoteId = asLong(body.get("sourceNoteId"));
        Long privateNoteId = asLong(body.get("privateNoteId"));
        Map<String,Object> privateNote = findPrivateNote(privateNoteId, userId);
        Long privateSourceNoteId = privateNote == null ? null : asLong(privateNote.get("source_social_note_id"));
        if (privateSourceNoteId != null) {
            if (sourceNoteId != null && !Objects.equals(sourceNoteId, privateSourceNoteId)) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "发布来源与私有笔记记录不一致");
            }
            sourceNoteId = privateSourceNoteId;
        }
        if (sourceNoteId != null && findSocialNote(sourceNoteId) == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "复制来源帖子不存在");
        }

        ensureReputation(userId);
        int reputation = reputationScore(userId);
        Map<String,Object> check = runAgentCopyrightCheck(sourceNoteId, title, content, cover, destination, tags);
        double similarity = ((Number) check.getOrDefault("similarityScore", 0D)).doubleValue();
        String decision = String.valueOf(check.getOrDefault("decision", "allow"));
        String reason = valueOr(check.get("reason"), "");
        if (reputation < 60 && "allow".equals(decision)) {
            decision = "manual_review";
            reason = "当前信誉分低于 60，发布前需要平台人工审核";
        }

        boolean published = "allow".equals(decision);
        boolean manualReview = "manual_review".equals(decision);
        String stateCode = randomStateCode();
        String noteStatus = published ? "published" : manualReview ? "pending_review" : "rejected";
        String visibility = published ? "public" : "private";
        String moderationStatus = published ? "approved" : manualReview ? "pending_review" : "rejected";
        int reviewRequired = manualReview ? 1 : 0;
        jdbcTemplate.update("INSERT INTO social_note(user_id,travel_note_id,source_note_id,title,content,cover_url,destination,tags,author_name,author_avatar,state_code,visibility,status,moderation_status,moderation_score,moderation_reason,review_required) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                userId, body.get("travelNoteId"), sourceNoteId, title, content, cover, destination, tags, valueOr(body.get("authorName"), ""), valueOr(body.get("authorAvatar"), ""), stateCode, visibility, noteStatus, moderationStatus, similarity, reason, reviewRequired);
        Long noteId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Long moderationId = recordModeration(noteId, sourceNoteId, userId, "publish", similarity, decision, manualReview ? "pending" : published ? "completed" : "rejected", reason, manualReview);
        if (manualReview) {
            jdbcTemplate.update("INSERT INTO social_platform_review(note_id,moderation_id,applicant_id,status,reason) VALUES(?,?,?,?,?)", noteId, moderationId, userId, "pending", reason);
        }
        if (published && sourceNoteId != null) {
            linkPublishedCopy(sourceNoteId, privateNoteId, userId, noteId);
        }

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("published", published); result.put("id", noteId); result.put("stateCode", stateCode); result.put("reputationScore", reputation);
        result.put("moderationDecision", decision); result.put("similarityScore", similarity); result.put("reviewRequired", manualReview); result.put("sourceNoteId", sourceNoteId);
        if (published) result.put("message", "已发布到我的圈子");
        else if (manualReview) result.put("message", "已提交平台人工审核，审核通过后才会公开");
        else result.put("message", "Agent 检测到与来源帖子高度相似，已自动退回，暂不允许发布");
        return result;
    }

    private Map<String,Object> findSocialNote(Long noteId) {
        if (noteId == null) return null;
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("SELECT * FROM social_note WHERE id=? LIMIT 1", noteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 发布时校验私有笔记确实属于当前用户，并读取其复制来源。 */
    private Map<String,Object> findPrivateNote(Long noteId, String userId) {
        if (noteId == null) return null;
        String owner = valueOr(userId, "user_001");
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("SELECT id,user_id,source_social_note_id FROM note_document WHERE id=? LIMIT 1", noteId);
        if (rows.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "待发布的私有笔记不存在");
        }
        if (!Objects.equals(valueOr(rows.get(0).get("user_id"), ""), owner)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "无权发布这篇私有笔记");
        }
        return rows.get(0);
    }

    /** 沿复制链回溯到最初的原创帖子，支持多次复制后的原作者举报。 */
    private Map<String,Object> originalSocialNote(Long noteId) {
        Map<String,Object> current = findSocialNote(noteId);
        Set<Long> visited = new HashSet<>();
        while (current != null) {
            Long currentId = asLong(current.get("id"));
            if (currentId != null && !visited.add(currentId)) return current;
            Long parentId = asLong(current.get("source_note_id"));
            if (parentId == null) return current;
            Map<String,Object> parent = findSocialNote(parentId);
            if (parent == null) return current;
            current = parent;
        }
        return null;
    }

    private void ensureReputation(String userId) {
        String safeUserId = valueOr(userId, "user_001");
        jdbcTemplate.update("INSERT INTO social_user_reputation(user_id,score) VALUES(?,100) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)", safeUserId);
    }

    public Map<String,Object> getReputation(String userId) {
        String safeUserId = valueOr(userId, "user_001");
        ensureReputation(safeUserId);
        Integer score = jdbcTemplate.queryForObject("SELECT score FROM social_user_reputation WHERE user_id=?", Integer.class, safeUserId);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("userId", safeUserId); result.put("score", score == null ? 100 : score); result.put("publishReviewThreshold", 60);
        return result;
    }

    private int reputationScore(String userId) {
        ensureReputation(userId);
        Integer score = jdbcTemplate.queryForObject("SELECT score FROM social_user_reputation WHERE user_id=?", Integer.class, valueOr(userId, "user_001"));
        return score == null ? 100 : score;
    }

    /**
     * Agent 版权预检：当前采用可解释的文本指纹预筛，后续可将 agent-guard-v1 替换为真正的向量/LLM 服务，
     * 但发布门槛和审核记录格式保持不变。
     */
    private Map<String,Object> runAgentCopyrightCheck(Long sourceNoteId, Object title, Object content, Object cover, Object destination, Object tags) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("similarityScore", 0D);
        result.put("decision", "allow");
        result.put("reason", sourceNoteId == null ? "没有复制来源，按原创内容进入发布流程" : "来源帖子不存在，未进行相似度判定");
        if (sourceNoteId == null) return result;
        Map<String,Object> source = originalSocialNote(sourceNoteId);
        if (source == null) return result;
        String candidateText = copyrightText(title, content, destination, tags);
        String sourceText = copyrightText(source.get("title"), source.get("content"), source.get("destination"), source.get("tags"));
        double score = textSimilarity(candidateText, sourceText);
        String decision = score >= 0.90D ? "auto_reject" : score >= 0.70D ? "manual_review" : "allow";
        String reason = "auto_reject".equals(decision)
                ? "Agent 判定与来源帖子高度相似，自动退回"
                : "manual_review".equals(decision)
                ? "Agent 判定与来源帖子相似度较高，转人工审核"
                : "Agent 判定相似度未达到人工审核阈值";
        result.put("similarityScore", score); result.put("decision", decision); result.put("reason", reason);
        return result;
    }

    private String copyrightText(Object title, Object content, Object destination, Object tags) {
        return normalizeCopyrightText(valueOr(title, "") + " " + valueOr(content, "") + " " + valueOr(destination, "") + " " + valueOr(tags, ""));
    }

    private String normalizeCopyrightText(String value) {
        return value.replaceAll("<[^>]*>", " ")
                .replaceAll("https?://\\S+", " ")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private double textSimilarity(String left, String right) {
        if (left.isBlank() || right.isBlank()) return 0D;
        if (left.equals(right)) return 1D;
        if (left.contains(right) || right.contains(left)) return 0.96D;
        Set<String> leftShingles = shingles(left);
        Set<String> rightShingles = shingles(right);
        if (leftShingles.isEmpty() || rightShingles.isEmpty()) return 0D;
        Set<String> intersection = new HashSet<>(leftShingles);
        intersection.retainAll(rightShingles);
        Set<String> union = new HashSet<>(leftShingles);
        union.addAll(rightShingles);
        return union.isEmpty() ? 0D : (double) intersection.size() / union.size();
    }

    private Set<String> shingles(String value) {
        Set<String> result = new HashSet<>();
        if (value.length() <= 3) { result.add(value); return result; }
        for (int i = 0; i <= value.length() - 3; i++) result.add(value.substring(i, i + 3));
        return result;
    }

    private Long recordModeration(Long noteId, Long sourceNoteId, String userId, String triggerType, double similarity, String decision, String status, String reason, boolean humanNotified) {
        jdbcTemplate.update("INSERT INTO social_note_moderation(note_id,source_note_id,user_id,trigger_type,ai_engine,similarity_score,decision,status,reason,human_notified) VALUES(?,?,?,?,?,?,?,?,?,?)", noteId, sourceNoteId, valueOr(userId, "user_001"), triggerType, "agent-guard-v1", similarity, decision, status, valueOr(reason, ""), humanNotified ? 1 : 0);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void linkPublishedCopy(Long sourceNoteId, Long privateNoteId, String userId, Long publishedNoteId) {
        List<Map<String,Object>> rows;
        if (privateNoteId != null) {
            rows = jdbcTemplate.queryForList("SELECT id FROM social_note_revision WHERE source_note_id=? AND contributor_id=? AND source_type='copy' AND private_note_id=? ORDER BY id DESC LIMIT 1", sourceNoteId, userId, privateNoteId);
        } else {
            rows = jdbcTemplate.queryForList("SELECT id FROM social_note_revision WHERE source_note_id=? AND contributor_id=? AND source_type='copy' AND published_note_id IS NULL ORDER BY id DESC LIMIT 1", sourceNoteId, userId);
        }
        if (!rows.isEmpty()) jdbcTemplate.update("UPDATE social_note_revision SET published_note_id=? WHERE id=?", publishedNoteId, rows.get(0).get("id"));
    }

    private int deductReputation(String userId, Long noteId, Long reportId, boolean confirmed, String reason) {
        String safeUserId = valueOr(userId, "user_001");
        ensureReputation(safeUserId);
        Integer beforeValue = jdbcTemplate.queryForObject("SELECT score FROM social_user_reputation WHERE user_id=? FOR UPDATE", Integer.class, safeUserId);
        int before = beforeValue == null ? 100 : beforeValue;
        int after = Math.max(0, before - 5);
        jdbcTemplate.update("UPDATE social_user_reputation SET score=?,report_count=report_count+1,confirmed_report_count=confirmed_report_count+? WHERE user_id=?", after, confirmed ? 1 : 0, safeUserId);
        jdbcTemplate.update("INSERT INTO social_reputation_event(user_id,note_id,report_id,event_type,delta,score_before,score_after,reason) VALUES(?,?,?,?,?,?,?,?)", safeUserId, noteId, reportId, "copyright_report", -5, before, after, valueOr(reason, "收到版权举报"));
        return after;
    }

    @Transactional
    public Map<String,Object> reportSocialNote(Long noteId, String reporterId, Map<String,Object> body) {
        String reporter = valueOr(reporterId, "user_001");
        Map<String,Object> note = findSocialNote(noteId);
        if (note == null) return Map.of("reported", false);
        String owner = valueOr(note.get("user_id"), "");
        if (Objects.equals(owner, reporter)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "不能举报自己的帖子");
        Long sourceNoteId = asLong(note.get("source_note_id"));
        Long requestedSourceId = asLong(body.get("sourceNoteId"));
        if (sourceNoteId == null) sourceNoteId = requestedSourceId;
        if (sourceNoteId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "该帖子没有可核验的来源帖子");
        Map<String,Object> source = originalSocialNote(sourceNoteId);
        if (source == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "来源帖子不存在");
        sourceNoteId = asLong(source.get("id"));
        if (!Objects.equals(valueOr(source.get("user_id"), ""), reporter)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "只有来源帖子作者可以提交版权举报");
        List<Map<String,Object>> duplicates = jdbcTemplate.queryForList("SELECT id FROM social_note_report WHERE reporter_id=? AND note_id=? AND report_type='copyright' LIMIT 1", reporter, noteId);
        if (!duplicates.isEmpty()) {
            Map<String,Object> result = new LinkedHashMap<>(); result.put("reported", false); result.put("duplicate", true); result.put("reportId", duplicates.get(0).get("id")); result.put("reputationScore", reputationScore(owner)); return result;
        }

        String reason = valueOr(body.get("reason"), "疑似复制或未经授权转载");
        String evidence = valueOr(body.get("evidence"), "");
        Map<String,Object> check = runAgentCopyrightCheck(sourceNoteId, note.get("title"), note.get("content"), note.get("cover_url"), note.get("destination"), note.get("tags"));
        double similarity = ((Number) check.getOrDefault("similarityScore", 0D)).doubleValue();
        String decision = String.valueOf(check.getOrDefault("decision", "allow"));
        String reportStatus = "auto_reject".equals(decision) ? "ai_confirmed" : "manual_review".equals(decision) ? "manual_review" : "dismissed";
        jdbcTemplate.update("INSERT INTO social_note_report(note_id,source_note_id,reporter_id,note_owner_id,report_type,reason,evidence,status,ai_engine,ai_similarity,ai_decision,ai_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", noteId, sourceNoteId, reporter, owner, "copyright", reason, evidence, reportStatus, "agent-guard-v1", similarity, decision, valueOr(check.get("reason"), ""));
        Long reportId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if ("manual_review".equals(decision)) jdbcTemplate.update("UPDATE social_note_report SET human_notified_at=CURRENT_TIMESTAMP WHERE id=?", reportId);
        int score = deductReputation(owner, noteId, reportId, "auto_reject".equals(decision), reason);
        jdbcTemplate.update("UPDATE social_note SET report_count=report_count+1 WHERE id=?", noteId);
        Long moderationId = recordModeration(noteId, sourceNoteId, owner, "report", similarity, decision, "auto_reject".equals(decision) ? "rejected" : "manual_review".equals(decision) ? "pending" : "completed", valueOr(check.get("reason"), ""), "manual_review".equals(decision));
        if ("auto_reject".equals(decision)) {
            jdbcTemplate.update("UPDATE social_note SET visibility='private',status='rejected',moderation_status='rejected',moderation_score=?,moderation_reason=?,review_required=0 WHERE id=?", similarity, valueOr(check.get("reason"), ""), noteId);
        } else if ("manual_review".equals(decision)) {
            jdbcTemplate.update("UPDATE social_note SET visibility='private',status='pending_review',moderation_status='pending_review',moderation_score=?,moderation_reason=?,review_required=1 WHERE id=?", similarity, valueOr(check.get("reason"), ""), noteId);
            jdbcTemplate.update("INSERT INTO social_platform_review(note_id,moderation_id,report_id,applicant_id,status,reason) VALUES(?,?,?,?,?,?)", noteId, moderationId, reportId, owner, "pending", valueOr(check.get("reason"), ""));
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("reported", true); result.put("reportId", reportId); result.put("reputationScore", score); result.put("similarityScore", similarity); result.put("aiDecision", decision); result.put("status", reportStatus);
        result.put("message", "auto_reject".equals(decision) ? "举报成立，Agent 已自动下架该帖子" : "manual_review".equals(decision) ? "举报已转交人工审核" : "举报已记录，Agent 暂未判定为高度相似");
        return result;
    }

    /** 平台后台审核信誉分不足或 Agent 转人工的帖子。 */
    @Transactional
    public Map<String,Object> reviewPlatformNote(Long noteId, String reviewerId, String status, String message) {
        if (!Set.of("approved", "rejected").contains(status)) throw new IllegalArgumentException("不支持的平台审核状态");
        Map<String,Object> note = findSocialNote(noteId);
        if (note == null) return Map.of("updated", false);
        boolean approved = "approved".equals(status);
        jdbcTemplate.update("UPDATE social_note SET visibility=?,status=?,moderation_status=?,review_required=0,moderation_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", approved ? "public" : "private", approved ? "published" : "rejected", approved ? "approved" : "rejected", valueOr(message, approved ? "平台审核通过" : "平台审核退回"), noteId);
        List<Map<String,Object>> moderation = jdbcTemplate.queryForList("SELECT id FROM social_note_moderation WHERE note_id=? ORDER BY id DESC LIMIT 1", noteId);
        if (!moderation.isEmpty()) jdbcTemplate.update("UPDATE social_note_moderation SET status=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?", approved ? "approved" : "rejected", valueOr(reviewerId, "platform"), moderation.get(0).get("id"));
        jdbcTemplate.update("UPDATE social_platform_review SET status=?,reviewer_id=?,reason=?,reviewed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE note_id=? AND status='pending'", status, valueOr(reviewerId, "platform"), valueOr(message, ""), noteId);
        Map<String,Object> result = new LinkedHashMap<>(); result.put("updated", true); result.put("noteId", noteId); result.put("status", status); result.put("published", approved); return result;
    }

    @Value("${travel.amap.api-key:}")
    private String amapApiKey;

    // ========== 地理编码（景点名称 → 经纬度） ==========
    public Map<String, BigDecimal> geocodeAddress(String address) {
        if (amapApiKey == null || amapApiKey.isBlank()) {
            // API Key 未配置时返回空结果
            return Map.of();
        }
        try {
            String url = UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/geocode/geo")
                    .queryParam("key", amapApiKey)
                    .queryParam("address", address)
                    .build()
                    .toUriString();
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response == null) return Map.of();
            String status = String.valueOf(response.get("status"));
            if (!"1".equals(status)) return Map.of();
            var geocodes = (java.util.List<Map<String, Object>>) response.get("geocodes");
            if (geocodes == null || geocodes.isEmpty()) return Map.of();
            String location = (String) geocodes.get(0).get("location");
            if (location == null || location.isBlank()) return Map.of();
            String[] parts = location.split(",");
            if (parts.length != 2) return Map.of();
            BigDecimal lng = new BigDecimal(parts[0].trim());
            BigDecimal lat = new BigDecimal(parts[1].trim());
            return Map.of("longitude", lng, "latitude", lat);
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ========== 用户偏好 ==========
    public UserPreferencePO getPreference(String userId) {
        LambdaQueryWrapper<UserPreferencePO> w = new LambdaQueryWrapper<>();
        w.eq(UserPreferencePO::getUserId, userId);
        w.eq(UserPreferencePO::getPreferenceType, "default");
        UserPreferencePO existing = userPreferenceMapper.selectOne(w);
        if (existing == null) {
            // 首次访问自动创建一条默认记录，保证 nickname 有持久化位置
            existing = new UserPreferencePO();
            existing.setUserId(userId);
            existing.setPreferenceType("default");
            userPreferenceMapper.insert(existing);
        }
        return existing;
    }

    @Transactional
    public void savePreference(UserPreferencePO po) {
        UserPreferencePO existing = getPreference(po.getUserId());
        if (existing != null) {
            po.setId(existing.getId());
            po.setCreatedAt(existing.getCreatedAt());
            userPreferenceMapper.updateById(po);
        } else {
            po.setPreferenceType("default");
            userPreferenceMapper.insert(po);
        }
    }

    // ========== 灵感目的地 ==========
    public List<InspirationPO> listInspirations(String userId) {
        LambdaQueryWrapper<InspirationPO> w = new LambdaQueryWrapper<>();
        w.eq(InspirationPO::getUserId, userId);
        w.orderByDesc(InspirationPO::getSortOrder).orderByDesc(InspirationPO::getCreatedAt);
        return inspirationMapper.selectList(w);
    }

    @Transactional
    public InspirationPO addInspiration(InspirationPO po) {
        if (po.getUserId() == null || po.getUserId().isBlank()) po.setUserId("user_001");
        if (po.getName() == null) po.setName("");
        if (po.getImageUrl() == null) po.setImageUrl("");
        if (po.getQuote() == null) po.setQuote("");
        if (po.getBestSeason() == null) po.setBestSeason("");
        if (po.getStatus() == null) po.setStatus(1);
        if (po.getPriority() == null) po.setPriority(0);
        if (po.getEstimatedBudget() == null) po.setEstimatedBudget(0);
        if (po.getSortOrder() == null) po.setSortOrder(0);
        inspirationMapper.insert(po);
        return po;
    }

    @Transactional
    public void updateInspiration(InspirationPO po) {
        inspirationMapper.updateById(po);
    }

    @Transactional
    public void deleteInspiration(Long id) {
        inspirationMapper.deleteById(id);
    }

    // ========== 旅程记录 ==========
    public List<JourneyPO> listJourneys(String userId) {
        LambdaQueryWrapper<JourneyPO> w = new LambdaQueryWrapper<>();
        w.eq(JourneyPO::getUserId, userId);
        w.orderByDesc(JourneyPO::getStartDate);
        return journeyMapper.selectList(w);
    }

    public JourneyPO getJourney(Long id) {
        return journeyMapper.selectById(id);
    }

    /**
     * 获取用户所有的旅程进度（含途经地点与图片），供前端详情展示
     */
    public List<JourneyDetailVO> listJourneyDetails(String userId) {
        List<JourneyPO> journeys = listJourneys(userId);
        return journeys.stream().map(j -> buildDetail(j.getId())).collect(java.util.stream.Collectors.toList());
    }

    /**
     * 获取单个旅程完整详情（含途经地点与图片）
     */
    public JourneyDetailVO getJourneyDetail(Long id) {
        JourneyPO j = journeyMapper.selectById(id);
        if (j == null) return null;
        return buildDetail(id);
    }

    private JourneyDetailVO buildDetail(Long id) {
        return JourneyDetailVO.builder()
                .journey(journeyMapper.selectById(id))
                .points(listJourneyPoints(id))
                .images(listJourneyImages(id))
                .build();
    }

    @Transactional
    public JourneyPO addJourney(JourneyPO po) {
        // 为必填字段设置默认值，避免 NOT NULL 报错
        if (po.getSummary() == null) po.setSummary("");
        if (po.getTravelType() == null) po.setTravelType("");
        if (po.getCompanions() == null) po.setCompanions("");
        if (po.getWeatherInfo() == null) po.setWeatherInfo("");
        if (po.getStatus() == null) po.setStatus(1);
        if (po.getStartDate() == null) po.setStartDate(LocalDate.now());
        if (po.getEndDate() == null) po.setEndDate(LocalDate.now());
        if (po.getTotalDays() == null) po.setTotalDays(0);
        if (po.getTotalCost() == null) po.setTotalCost(0);
        if (po.getRating() == null) po.setRating(0);
        journeyMapper.insert(po);
        return po;
    }

    @Transactional
    public void updateJourney(JourneyPO po) {
        journeyMapper.updateById(po);
    }

    @Transactional
    public void deleteJourney(Long id) {
        journeyPointMapper.delete(new LambdaQueryWrapper<JourneyPointPO>().eq(JourneyPointPO::getJourneyId, id));
        journeyImageMapper.delete(new LambdaQueryWrapper<JourneyImagePO>().eq(JourneyImagePO::getJourneyId, id));
        journeyMapper.deleteById(id);
    }

    // ========== 途经地点 ==========
    public List<JourneyPointPO> listJourneyPoints(Long journeyId) {
        LambdaQueryWrapper<JourneyPointPO> w = new LambdaQueryWrapper<>();
        w.eq(JourneyPointPO::getJourneyId, journeyId);
        w.orderByAsc(JourneyPointPO::getSortOrder);
        return journeyPointMapper.selectList(w);
    }

    @Transactional
    public void saveJourneyPoints(Long journeyId, List<JourneyPointPO> points) {
        journeyPointMapper.delete(new LambdaQueryWrapper<JourneyPointPO>().eq(JourneyPointPO::getJourneyId, journeyId));
        for (JourneyPointPO p : points) {
            p.setJourneyId(journeyId);
            // 为必填字段设置默认值，避免 NOT NULL 报错
            if (p.getLatitude() == null) p.setLatitude(java.math.BigDecimal.ZERO);
            if (p.getLongitude() == null) p.setLongitude(java.math.BigDecimal.ZERO);
            if (p.getVisitDate() == null) p.setVisitDate(LocalDate.now());
            if (p.getDescription() == null) p.setDescription("");
            if (p.getSortOrder() == null) p.setSortOrder(0);
            journeyPointMapper.insert(p);
        }
    }

    // ========== 旅程照片 ==========
    public List<JourneyImagePO> listJourneyImages(Long journeyId) {
        LambdaQueryWrapper<JourneyImagePO> w = new LambdaQueryWrapper<>();
        w.eq(JourneyImagePO::getJourneyId, journeyId);
        w.orderByAsc(JourneyImagePO::getSortOrder);
        return journeyImageMapper.selectList(w);
    }

    @Transactional
    public void saveJourneyImages(Long journeyId, List<JourneyImagePO> images) {
        journeyImageMapper.delete(new LambdaQueryWrapper<JourneyImagePO>().eq(JourneyImagePO::getJourneyId, journeyId));
        for (JourneyImagePO img : images) {
            img.setJourneyId(journeyId);
            journeyImageMapper.insert(img);
        }
    }

    // ========== 用户头像 ==========
    public String getUserProfile(String userId) {
        UserProfilePO profile = userProfileMapper.findByUserId(userId);
        return profile != null ? profile.getAvatar() : null;
    }

    public String uploadAvatar(MultipartFile file, String userId) throws Exception {
        // 保存文件到用户家目录下的 travel-agent-uploads 目录
        String fileName = file.getOriginalFilename();
        String ext = fileName != null && fileName.contains(".") 
            ? fileName.substring(fileName.lastIndexOf(".")) 
            : ".jpg";
        String newFileName = UUID.randomUUID().toString().replace("-", "") + ext;
        
        String uploadDir = System.getProperty("user.home") + "/travel-agent-uploads/avatar/";
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        File dest = new File(uploadDir + newFileName);
        file.transferTo(dest);
        
        // 生成访问URL
        String avatarUrl = "/uploads/avatar/" + newFileName;
        
        // 更新数据库
        UserProfilePO profile = new UserProfilePO();
        profile.setUserId(userId);
        profile.setUsername(userId);
        profile.setAvatar(avatarUrl);
        profile.setAvatarUrl(avatarUrl);
        saveUserProfile(profile);
        
        return avatarUrl;
    }

    /**
     * 通用图片上传
     * @param file 上传的文件
     * @param category 分类目录: inspiration, journey, general
     * @return 图片访问 URL
     */
    public String uploadImage(MultipartFile file, String category) {
        return imageStorageService.store(file, category);
    }

    public void saveUserProfile(UserProfilePO po) {
        UserProfilePO existing = userProfileMapper.findByUserId(po.getUserId());
        if (existing != null) {
            po.setId(existing.getId());
            po.setCreatedAt(existing.getCreatedAt());
            userProfileMapper.updateByUserId(po);
        } else {
            userProfileMapper.insert(po);
        }
    }

    // ========== 用户昵称（从 user_travel_preference.name 读取） ==========
    public String getUserNickname(String userId) {
        UserTravelPreferencePO pref = userTravelPreferenceMapper.findByUserIdAndType(userId, "default");
        if (pref != null && pref.getName() != null && !pref.getName().isBlank()) {
            return pref.getName();
        }
        return "旅人";
    }

    /**
     * 更新 user_travel_preference.name（用于侧边栏昵称持久化）
     */
    public void updateNickname(String userId, String nickname) {
        UserTravelPreferencePO pref = userTravelPreferenceMapper.findByUserIdAndType(userId, "default");
        if (pref == null) {
            pref = new UserTravelPreferencePO();
            pref.setUserId(userId);
            pref.setPreferenceType("default");
            pref.setName(nickname);
            userTravelPreferenceMapper.insert(pref);
        } else {
            pref.setName(nickname);
            userTravelPreferenceMapper.update(pref);
        }
    }
}
