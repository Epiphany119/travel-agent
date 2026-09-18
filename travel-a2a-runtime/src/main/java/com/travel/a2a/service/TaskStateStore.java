package com.travel.a2a.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Map;

/** Durable-store seam for A2A task state. Replace backing map with Redis without changing callers. */
@Component
public class TaskStateStore {
    private static final java.time.Duration TTL = java.time.Duration.ofHours(24);
    private final StringRedisTemplate redis;
    public TaskStateStore(StringRedisTemplate redis) { this.redis = redis; }
    private String key(String id) { return "a2a:task:" + id; }

    public boolean create(String taskId) { Boolean ok=redis.opsForHash().putIfAbsent(key(taskId), "status", "PENDING"); if(Boolean.TRUE.equals(ok)){ String now=Instant.now().toString(); redis.opsForHash().put(key(taskId),"progress","0"); redis.opsForHash().put(key(taskId),"createdAt",now); redis.opsForHash().put(key(taskId),"updatedAt",now); redis.expire(key(taskId),TTL); } return Boolean.TRUE.equals(ok); }
    public void running(String taskId, int progress) { update(taskId, "RUNNING", progress, null); }
    public void succeed(String taskId) { update(taskId, "SUCCEEDED", 100, null); }
    public void fail(String taskId, String error) { update(taskId, "FAILED", 0, error); }
    public TaskState get(String taskId) { Map<Object,Object> m=redis.opsForHash().entries(key(taskId)); if(m.isEmpty()) return null; return new TaskState(taskId,String.valueOf(m.get("status")),Integer.parseInt(String.valueOf(m.getOrDefault("progress","0"))),m.get("error")==null?null:String.valueOf(m.get("error")),Instant.parse(String.valueOf(m.get("updatedAt")))); }
    public void cancel(String id) { update(id, "CANCELLED", 0, "cancelled by user"); }
    private void update(String id, String status, int progress, String error) {
        if(Boolean.TRUE.equals(redis.hasKey(key(id)))) { String current=String.valueOf(redis.opsForHash().get(key(id),"status")); if("SUCCEEDED".equals(current)||"FAILED".equals(current)||"CANCELLED".equals(current)) return; redis.opsForHash().put(key(id),"status",status); redis.opsForHash().put(key(id),"progress",String.valueOf(progress)); redis.opsForHash().put(key(id),"updatedAt",Instant.now().toString()); if(error!=null) redis.opsForHash().put(key(id),"error",error); redis.expire(key(id),TTL); }
    }
    public record TaskState(String taskId, String status, int progress, String error, Instant updatedAt) {}
}
