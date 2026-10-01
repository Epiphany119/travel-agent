package com.travel.module.user.biz.api;

import com.travel.common.core.result.ApiResult;
import com.travel.module.user.biz.domain.service.UserBizService;
import com.travel.module.user.biz.domain.service.PlatformRole;
import com.travel.module.user.biz.domain.service.PlatformRoleAuthorizationService;
import com.travel.module.user.biz.api.dto.SaveAiPlanRequest;
import com.travel.module.user.biz.infra.persistence.InspirationPO;
import com.travel.module.user.biz.infra.persistence.JourneyPO;
import com.travel.module.user.biz.infra.persistence.JourneyPointPO;
import com.travel.module.user.biz.infra.persistence.JourneyImagePO;
import com.travel.module.user.biz.infra.persistence.UserPreferencePO;
import com.travel.module.user.biz.infra.persistence.TravelNotePO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户模块 REST 控制器。
 *
 * <p>提供用户偏好、昵称、头像、灵感目的地、旅程记录、旅行笔记、社区社交、
 * 用户搜索、好友申请、地理编码及通用图片上传等接口。
 * 统一返回 {@link ApiResult} 包装结构，路径前缀为 {@code /api/user}。</p>
 *
 * @author Roamly
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserApi {

    private final UserBizService userBizService;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformRoleAuthorizationService roleAuthorization;


    // =====================================================================
    // 一、社区 / 社交
    // =====================================================================

    /**
     * 搜索用户。
     *
     * @param q 搜索关键字（用户名或用户 ID 模糊匹配）
     * @return 匹配的用户列表
     */
    @GetMapping("/users/search")
    public ApiResult<?> searchUsers(@RequestParam String q) {
        return ApiResult.success(userBizService.searchUsers(q));
    }

    /**
     * 获取公开旅行笔记列表（社区广场）。
     *
     * @param page 页码，从 0 开始，默认 0
     * @param size 每页条数，默认 20
     * @return 公开笔记列表
     */
    @GetMapping("/social/notes")
    public ApiResult<?> publicNotes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String ownerId) {
        return ApiResult.success(userBizService.listPublicNotes(page, size, q, tag, ownerId));
    }

    /** 获取用户公开资料；个人主页只返回公开身份信息，不返回旅行偏好。 */
    @GetMapping("/users/{userId}/profile")
    public ApiResult<?> publicUserProfile(@PathVariable String userId) {
        return ApiResult.success(userBizService.getPublicUserProfile(userId));
    }

    /**
     * 获取公开旅行笔记详情。
     *
     * @param id 笔记 ID
     * @return 笔记详情，仅返回可见性为 link 的笔记
     */
    @GetMapping("/social/notes/{id}")
    public ApiResult<?> publicNote(@PathVariable Long id) {
        return ApiResult.success(userBizService.getPublicNote(id));
    }

    /**
     * 对公开笔记表态（点赞 / 收藏），重复调用可取消。
     *
     * @param id     笔记 ID
     * @param type   表态类型
     * @param userId 用户 ID，默认 user_001
     * @return 操作结果 "OK"
     */
    @PostMapping("/social/notes/{id}/reaction")
    public ApiResult<?> react(
            @PathVariable Long id,
            @RequestParam String type,
            HttpServletRequest request) {
        userBizService.reactNote(id, currentUserId(request), type);
        return ApiResult.success("OK");
    }

    /**
     * 获取公开笔记的评论列表。
     *
     * @param id 笔记 ID
     * @return 评论列表
     */
    @GetMapping("/social/notes/{id}/comments")
    public ApiResult<?> comments(@PathVariable Long id) {
        return ApiResult.success(userBizService.listComments(id));
    }

    /**
     * 对公开笔记发表评论。
     *
     * @param id     笔记 ID
     * @param userId 用户 ID，默认 user_001
     * @param body   请求体，含 {"content": "评论内容"}
     * @return 操作结果 "OK"
     */
    @PostMapping("/social/notes/{id}/comments")
    public ApiResult<?> comment(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        userBizService.addComment(id, currentUserId(request), body.getOrDefault("content", ""));
        return ApiResult.success("OK");
    }

    /** 创建复制存档或协作 PR 请求。复制存档不会改变原帖，也不具备公开发布权限。 */
    @PostMapping("/social/notes/{id}/revisions")
    public ApiResult<?> createRevision(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return ApiResult.success(userBizService.createNoteRevision(id, currentUserId(request), body));
    }

    @GetMapping("/social/notes/{id}/revisions")
    public ApiResult<?> revisions(
            @PathVariable Long id,
            HttpServletRequest request) {
        return ApiResult.success(userBizService.listNoteRevisions(id, currentUserId(request)));
    }

    @PutMapping("/social/revisions/{revisionId}/submit")
    public ApiResult<?> submitRevision(
            @PathVariable Long revisionId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return ApiResult.success(userBizService.submitNoteRevision(revisionId, currentUserId(request), body));
    }

    /** 原作者审核协作版本；合并时才会更新 social_note 的公开内容和状态码。 */
    @PutMapping("/social/revisions/{revisionId}")
    public ApiResult<?> reviewRevision(
            @PathVariable Long revisionId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        return ApiResult.success(userBizService.reviewNoteRevision(revisionId, currentUserId(request), body.getOrDefault("status", "rejected"), body.getOrDefault("message", "")));
    }

    /** 原作者举报疑似侵权帖子；Agent 会先做相似度判定，高相似度自动下架，中高相似度进入平台队列。 */
    @PostMapping("/social/notes/{id}/reports")
    public ApiResult<?> reportSocialNote(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        return ApiResult.success(userBizService.reportSocialNote(id, currentUserId(request), body == null ? new HashMap<>() : body));
    }

    /** 获取当前用户信誉分。 */
    @GetMapping("/reputation")
    public ApiResult<?> reputation(HttpServletRequest request) {
        return ApiResult.success(userBizService.getReputation(currentUserId(request)));
    }

    /** 平台后台审核队列中的帖子；普通用户端不调用此接口。 */
    @PutMapping("/social/notes/{id}/platform-review")
    public ApiResult<?> platformReview(
            @PathVariable Long id,
            @RequestParam(defaultValue = "platform") String reviewerId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String currentUserId = currentUserId(request);
        roleAuthorization.requireRole(currentUserId, PlatformRole.MODERATOR);        return ApiResult.success(userBizService.reviewPlatformNote(id, currentUserId, body.getOrDefault("status", "rejected"), body.getOrDefault("message", "")));
    }

    /**
     * 发送好友申请。
     *
     * @param id   被申请用户 ID
     * @param from 申请方用户 ID，默认 user_001
     * @param body 请求体（可选），含 {"message": "申请附言"}
     * @return 操作结果 "OK"
     */
    @PostMapping("/users/{id}/friend-request")
    public ApiResult<?> friend(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        String message = body == null ? "" : body.getOrDefault("message", "");
        userBizService.requestFriend(currentUserId(request), id, message);
        return ApiResult.success("OK");
    }

    /**
     * 发布一篇社区旅行笔记。
     *
     * @param body 请求体，含 userId / title / content / coverUrl 等
     * @return 发布后的笔记信息
     */
    @PostMapping("/social/notes")
    public ApiResult<?> publishSocialNote(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ApiResult.success(userBizService.publishSocialNote(body, currentUserId(request)));
    }

    /** 更新自己发布的社区笔记，保持“打开即编辑”的交互闭环。 */
    @PutMapping("/social/notes/{id}")
    public ApiResult<?> updateSocialNote(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return ApiResult.success(userBizService.updateSocialNote(id, currentUserId(request), body));
    }

    /** 将社区笔记复制成当前用户的私有旅行笔记。 */
    @PostMapping("/social/notes/{id}/copy")
    public ApiResult<?> copySocialNote(
            @PathVariable Long id,
            HttpServletRequest request) {
        return ApiResult.success(userBizService.copySocialNote(id, currentUserId(request)));
    }

    // =====================================================================
    // 二、旅行笔记（个人）
    // =====================================================================

    /**
     * 获取当前用户的全部旅行笔记。
     *
     * @param userId 用户 ID，默认 user_001
     * @return 该用户的旅行笔记列表
     */
    @GetMapping("/travel-notes")
    public ApiResult<?> listTravelNotes(
            HttpServletRequest request) {
        return ApiResult.success(userBizService.listTravelNotes(currentUserId(request)));
    }

    /**
     * 获取旅行笔记详情。
     *
     * @param id 笔记 ID
     * @return 笔记详情
     */
    @GetMapping("/travel-notes/{id}")
    public ApiResult<?> getTravelNote(@PathVariable Long id, HttpServletRequest request) {
        return ApiResult.success(userBizService.getTravelNote(id, currentUserId(request)));
    }

    /**
     * 新增或更新旅行笔记。
     *
     * @param note 笔记实体（id 存在则更新，否则新增）
     * @return 保存后的笔记
     */
    @PostMapping("/travel-notes")
    public ApiResult<?> saveTravelNote(@RequestBody TravelNotePO note, HttpServletRequest request) {
        note.setUserId(currentUserId(request));
        return ApiResult.success(userBizService.saveTravelNote(note));
    }

    @PostMapping("/ai-plans/save")
    public ApiResult<?> saveAiPlan(@RequestBody SaveAiPlanRequest request, HttpServletRequest http) {
        return ApiResult.success(userBizService.saveAiPlan(request, currentUserId(http)));
    }

    /**
     * 复制旅行笔记为副本。
     *
     * @param id     源笔记 ID
     * @param userId 目标用户 ID，默认 user_001
     * @return 复制后的副本笔记
     */
    @PostMapping("/travel-notes/{id}/copy")
    public ApiResult<?> copyTravelNote(
            @PathVariable Long id,
            HttpServletRequest request) {
        return ApiResult.success(userBizService.copyTravelNote(id, currentUserId(request)));
    }

    /**
     * 通过分享令牌查看笔记（无需登录）。
     *
     * @param token 分享令牌
     * @return 笔记信息及是否公开标记
     */
    @GetMapping("/travel-notes/share/{token}")
    public ApiResult<?> getSharedTravelNote(@PathVariable String token) {
        return ApiResult.success(userBizService.getSharedTravelNote(token));
    }

    /**
     * 删除旅行笔记。
     *
     * @param id 笔记 ID
     * @return 操作结果 "OK"
     */
    @DeleteMapping("/travel-notes/{id}")
    public ApiResult<?> deleteTravelNote(@PathVariable Long id, HttpServletRequest request) {
        userBizService.deleteTravelNote(id, currentUserId(request));
        return ApiResult.success("OK");
    }

    // =====================================================================
    // 三、灵感目的地 CRUD
    // =====================================================================

    /**
     * 获取用户的灵感目的地列表。
     *
     * @param userId 用户 ID，默认 user_001
     * @return 灵感目的地列表
     */
    @GetMapping("/inspirations")
    public ApiResult<?> listInspirations(
            HttpServletRequest request) {
        return ApiResult.success(userBizService.listInspirations(currentUserId(request)));
    }

    /**
     * 新增一个灵感目的地。
     *
     * @param po 灵感实体；userId 为空时默认 user_001
     * @return 新增后的灵感记录
     */
    @PostMapping("/inspirations")
    public ApiResult<?> addInspiration(@RequestBody InspirationPO po, HttpServletRequest request) {
        po.setUserId(currentUserId(request));
        return ApiResult.success(userBizService.addInspiration(po));
    }

    /**
     * 更新灵感目的地。
     *
     * @param id 灵感 ID
     * @param po 更新后的灵感实体
     * @return 操作结果 "OK"
     */
    @PutMapping("/inspirations/{id}")
    public ApiResult<?> updateInspiration(
            @PathVariable Long id,
            @RequestBody InspirationPO po,
            HttpServletRequest request) {
        po.setId(id);
        userBizService.updateInspiration(po, currentUserId(request));
        return ApiResult.success("OK");
    }

    /**
     * 删除灵感目的地。
     *
     * @param id 灵感 ID
     * @return 操作结果 "OK"
     */
    @DeleteMapping("/inspirations/{id}")
    public ApiResult<?> deleteInspiration(@PathVariable Long id, HttpServletRequest request) {
        userBizService.deleteInspiration(id, currentUserId(request));
        return ApiResult.success("OK");
    }

    // =====================================================================
    // 四、旅程记录 CRUD
    // =====================================================================

    /**
     * 获取用户的全部旅程（含途经地点与图片详情）。
     *
     * @param userId 用户 ID，默认 user_001
     * @return 旅程详情列表
     */
    @GetMapping("/journeys")
    public ApiResult<?> listJourneys(
            HttpServletRequest request) {
        return ApiResult.success(userBizService.listJourneyDetails(currentUserId(request)));
    }

    /**
     * 获取单个旅程完整详情（含途经地点与图片）。
     *
     * @param id 旅程 ID
     * @return 旅程详情
     */
    @GetMapping("/journeys/{id}")
    public ApiResult<?> getJourney(@PathVariable Long id, HttpServletRequest request) {
        return ApiResult.success(userBizService.getJourneyDetail(id, currentUserId(request)));
    }

    /**
     * 新增一段旅程记录。
     *
     * @param po 旅程实体；userId 为空时默认 user_001
     * @return 新增后的旅程
     */
    @PostMapping("/journeys")
    public ApiResult<?> addJourney(@RequestBody JourneyPO po, HttpServletRequest request) {
        po.setUserId(currentUserId(request));
        return ApiResult.success(userBizService.addJourney(po));
    }

    /**
     * 更新旅程记录。
     *
     * @param id 旅程 ID
     * @param po 更新后的旅程实体
     * @return 操作结果 "OK"
     */
    @PutMapping("/journeys/{id}")
    public ApiResult<?> updateJourney(
            @PathVariable Long id,
            @RequestBody JourneyPO po,
            HttpServletRequest request) {
        po.setId(id);
        userBizService.updateJourney(po, currentUserId(request));
        return ApiResult.success("OK");
    }

    /**
     * 删除旅程记录（同时删除其途经地点与图片）。
     *
     * @param id 旅程 ID
     * @return 操作结果 "OK"
     */
    @DeleteMapping("/journeys/{id}")
    public ApiResult<?> deleteJourney(@PathVariable Long id, HttpServletRequest request) {
        userBizService.deleteJourney(id, currentUserId(request));
        return ApiResult.success("OK");
    }

    /**
     * 获取旅程的途经地点列表。
     *
     * @param id 旅程 ID
     * @return 途经地点列表
     */
    @GetMapping("/journeys/{id}/points")
    public ApiResult<?> listJourneyPoints(@PathVariable Long id, HttpServletRequest request) {
        return ApiResult.success(userBizService.listJourneyPoints(id, currentUserId(request)));
    }

    /**
     * 保存旅程的途经地点（整段覆盖更新）。
     *
     * @param id     旅程 ID
     * @param points 途经地点数组
     * @return 操作结果 "OK"
     */
    @PostMapping("/journeys/{id}/points")
    public ApiResult<?> saveJourneyPoints(
            @PathVariable Long id,
            @RequestBody List<JourneyPointPO> points,
            HttpServletRequest request) {
        userBizService.saveJourneyPoints(id, points, currentUserId(request));
        return ApiResult.success("OK");
    }

    /**
     * 获取旅程的图片列表。
     *
     * @param id 旅程 ID
     * @return 图片列表
     */
    @GetMapping("/journeys/{id}/images")
    public ApiResult<?> listJourneyImages(@PathVariable Long id, HttpServletRequest request) {
        return ApiResult.success(userBizService.listJourneyImages(id, currentUserId(request)));
    }

    /**
     * 保存旅程的图片列表（整段覆盖更新）。
     *
     * @param id     旅程 ID
     * @param images 图片数组
     * @return 操作结果 "OK"
     */
    @PostMapping("/journeys/{id}/images")
    public ApiResult<?> saveJourneyImages(
            @PathVariable Long id,
            @RequestBody List<JourneyImagePO> images,
            HttpServletRequest request) {
        userBizService.saveJourneyImages(id, images, currentUserId(request));
        return ApiResult.success("OK");
    }

    /**
     * 健康/连通性自检接口。
     *
     * @return {"message": "OK"}
     */
    @GetMapping("/test")
    public ApiResult<?> test() {
        return ApiResult.success(Map.of("message", "OK"));
    }

    // =====================================================================
    // 五、用户偏好（合并 auth_account 邮箱信息）
    // =====================================================================

    /**
     * 获取用户旅行偏好，并合并认证表中的邮箱与用户名信息。
     *
     * @param userId 用户 ID，默认 user_001
     * @return 偏好信息（含 email / username / name 字段）
     */
    @GetMapping("/preferences")
    public ApiResult<?> getPreferences(HttpServletRequest request) {
        String userId = currentUserId(request);
        UserPreferencePO pref = userBizService.getPreference(userId);

        // 查询 auth_account 表获取邮箱和用户名
        Map<String, Object> authInfo = getUserAuthInfo(userId);

        // 构造返回结果（Map 形式，包含偏好数据 + 认证信息）
        Map<String, Object> result = new LinkedHashMap<>();
        if (pref != null) {
            result.put("id", pref.getId());
            result.put("userId", pref.getUserId());
            result.put("phone", pref.getPhone());
            result.put("preferenceType", pref.getPreferenceType());
            result.put("preferenceName", pref.getPreferenceName());
            result.put("favoriteDestinations", pref.getFavoriteDestinations());
            result.put("preferredSeason", pref.getPreferredSeason());
            result.put("budgetLevel", pref.getBudgetLevel());
            result.put("dailyBudgetMin", pref.getDailyBudgetMin());
            result.put("dailyBudgetMax", pref.getDailyBudgetMax());
            result.put("travelStyle", pref.getTravelStyle());
            result.put("interests", pref.getInterests());
            result.put("dietaryRequirements", pref.getDietaryRequirements());
            result.put("preferredCuisines", pref.getPreferredCuisines());
            result.put("accommodationType", pref.getAccommodationType());
            result.put("accommodationRequirements", pref.getAccommodationRequirements());
            result.put("transportationPreference", pref.getTransportationPreference());
            result.put("travelCompanion", pref.getTravelCompanion());
            result.put("hasChildren", pref.getHasChildren());
            result.put("childrenAges", pref.getChildrenAges());
            result.put("activityLevel", pref.getActivityLevel());
            result.put("pacePreference", pref.getPacePreference());
            result.put("mobilityRequirements", pref.getMobilityRequirements());
            result.put("specialRequests", pref.getSpecialRequests());
            result.put("systemThemeJson", pref.getSystemThemeJson());
        }

        // 合并 auth 信息（邮箱 + 用户名）
        result.put("email", authInfo.get("email"));
        result.put("username", authInfo.get("username"));
        result.put("name", authInfo.get("username"));

        return ApiResult.success(result);
    }

    /**
     * 根据 userId 从 auth_account 表查询邮箱和用户名。
     *
     * @param userId 用户 ID
     * @return 包含 email / username 的 Map；未查询到或异常时为 null 值
     */
    private Map<String, Object> getUserAuthInfo(String userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("email", null);
        result.put("username", null);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT email, username FROM auth_account WHERE user_id = ? LIMIT 1",
                    userId);
            if (!rows.isEmpty()) {
                result.put("email", rows.get(0).get("email"));
                result.put("username", rows.get(0).get("username"));
                log.debug("查询用户认证信息: userId={}, email={}, username={}",
                        userId, rows.get(0).get("email"), rows.get(0).get("username"));
            }
        } catch (Exception e) {
            log.warn("查询用户认证信息失败: userId={}, error={}", userId, e.getMessage());
        }
        return result;
    }

    /**
     * 保存用户旅行偏好。
     *
     * @param po 偏好实体；userId 为空时默认 user_001
     * @return 操作结果 "OK"
     */
    @PutMapping("/preferences")
    public ApiResult<?> savePreferences(@RequestBody UserPreferencePO po, HttpServletRequest request) {
        po.setUserId(currentUserId(request));
        userBizService.savePreference(po);
        return ApiResult.success("OK");
    }

    // =====================================================================
    // 六、用户昵称
    // =====================================================================

    /**
     * 获取用户昵称（未设置时回退为 userId）。
     *
     * @param userId 用户 ID，默认 user_001
     * @return {"nickname": "..."}
     */
    @GetMapping("/nickname")
    public ApiResult<?> getNickname(HttpServletRequest request) {
        return ApiResult.success(Map.of("nickname", userBizService.getUserNickname(currentUserId(request))));
    }

    /**
     * 更新用户昵称。
     *
     * @param userId 用户 ID，默认 user_001
     * @param body   请求体，含 {"nickname": "新昵称"}
     * @return 操作结果 "OK"；昵称为空时返回错误
     */
    @PutMapping("/nickname")
    public ApiResult<?> updateNickname(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String nickname = body.get("nickname");
        if (nickname == null || nickname.isBlank()) {
            return ApiResult.error("昵称不能为空");
        }
        userBizService.updateNickname(currentUserId(request), nickname);
        return ApiResult.success("OK");
    }

    // =====================================================================
    // 七、头像
    // =====================================================================

    /**
     * 上传用户头像（multipart/form-data）。
     *
     * @param file   头像图片文件
     * @param userId 用户 ID，默认 user_001
     * @return {"avatar": "相对路径"}；失败返回错误
     */
    @PostMapping("/avatar")
    public ApiResult<?> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        try {
            String avatarUrl = userBizService.uploadAvatar(file, currentUserId(request));
            return ApiResult.success(Map.of("avatar", avatarUrl));
        } catch (Exception e) {
            return ApiResult.error("上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取用户头像。
     *
     * @param userId 用户 ID，默认 user_001
     * @return {"avatar": "相对路径"}；无头像时为空串
     */
    @GetMapping("/avatar")
    public ApiResult<?> getAvatar(HttpServletRequest request) {
        String userId = currentUserId(request);
        String avatar = userBizService.getUserProfile(userId);
        return ApiResult.success(Map.of("avatar", avatar != null ? avatar : ""));
    }

    // =====================================================================
    // 八、地理编码 / 通用上传
    // =====================================================================

    /**
     * 根据地址进行地理编码（当前为占位实现，坐标恒为 0）。
     *
     * @param address 地址文本
     * @return {latitude, longitude, address}
     */
    @GetMapping("/geocode")
    public ApiResult<?> geocode(@RequestParam String address) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("latitude", 0.0);
            result.put("longitude", 0.0);
            result.put("address", address);
            return ApiResult.success(result);
        } catch (Exception e) {
            return ApiResult.error("地理编码失败: " + e.getMessage());
        }
    }

    /**
     * 通用图片上传（multipart/form-data）。
     *
     * @param file     图片文件
     * @param category 图片分类标签，默认 general
     * @return {"url": "图片访问路径"}；失败返回错误
     */
    @PostMapping("/upload")
    public ApiResult<?> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "general") String category) {
        try {
            Set<String> allowedCategories = Set.of("general", "inspiration", "journey");
            if (!allowedCategories.contains(category)) {
                return ApiResult.error("不支持的图片分类");
            }
            String url = userBizService.uploadImage(file, category);
            return ApiResult.success(Map.of("url", url));
        } catch (Exception e) {
            return ApiResult.error("上传失败: " + e.getMessage());
        }
    }

    private String currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute("authenticatedUserId");
        if (value == null || value.toString().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return value.toString();
    }
}
