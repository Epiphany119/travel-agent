package com.travel.auth.service;

import com.travel.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 邮箱验证码服务
 *
 * <p>通过 Redis 存储验证码（5 分钟有效期 + 60 秒发送冷却），
 * 使用 {@link JavaMailSender} 发送邮件至任意邮箱地址。</p>
 */
@Slf4j
@Service
public class EmailVerificationService {

    private final StringRedisTemplate redis;
    private final JavaMailSender mailSender;
    private final SecureRandom random = new SecureRandom();

    @Value("${spring.mail.username:}")
    private String sender;

    public EmailVerificationService(StringRedisTemplate redis, JavaMailSender mailSender) {
        this.redis = redis;
        this.mailSender = mailSender;
    }

    /**
     * 发送邮箱验证码到指定邮箱
     *
     * @param email 目标邮箱地址（可以是任意有效的邮箱域名）
     * @throws BusinessException 业务异常（频率限制、发送失败等）
     */
    public void send(String email) {
        // 1. 基础校验
        if (sender == null || sender.isBlank()) {
            log.error("邮箱服务未配置: spring.mail.username 为空");
            throw new BusinessException(500, "邮箱服务未配置，请联系管理员设置邮件账号");
        }

        // 2. 频率限制：60 秒内不能重复发送
        String cooldownKey = "auth:email:code:" + email.toLowerCase() + ":cooldown";
        if (Boolean.TRUE.equals(redis.hasKey(cooldownKey))) {
            Long ttl = redis.getExpire(cooldownKey);
            log.warn("验证码发送过于频繁: email={}, 剩余冷却={}s", email, ttl);
            throw new BusinessException(429, "验证码发送过于频繁，请" + ttl + "秒后再试");
        }

        // 3. 生成 6 位验证码
        String code = String.format("%06d", random.nextInt(1_000_000));
        String codeKey = "auth:email:code:" + email.toLowerCase();

        // 4. 先存 Redis，再发邮件
        redis.opsForValue().set(codeKey, code, Duration.ofMinutes(5));
        redis.opsForValue().set(cooldownKey, "1", Duration.ofSeconds(60));

        // 5. 构建并发送邮件
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(sender);
        message.setTo(email);
        message.setSubject("Roamly 邮箱验证码");
        message.setText("您的验证码是：" + code + "，5 分钟内有效。请勿泄露给他人。");

        try {
            log.info("发送验证码邮件: to={}, from={}", email, sender);
            mailSender.send(message);
            log.info("验证码邮件发送成功: to={}", email);
        } catch (MailException e) {
            // 发送失败时清理 Redis，避免僵尸验证码
            redis.delete(codeKey);
            redis.delete(cooldownKey);
            log.error("验证码邮件发送失败: to={}, 错误={}", email, e.getMessage(), e);
            throw new BusinessException(500, "验证码发送失败：" + e.getMessage() + "，请稍后重试");
        }
    }

    /**
     * 验证邮箱验证码
     *
     * @param email 邮箱地址
     * @param code  用户输入的验证码
     * @return 是否验证成功，成功后验证码立即失效
     */
    public boolean verify(String email, String code) {
        String key = "auth:email:code:" + email.toLowerCase();
        String stored = redis.opsForValue().get(key);

        if (stored != null && stored.equals(code)) {
            redis.delete(key);
            log.info("验证码校验通过: email={}", email);
            return true;
        }

        log.warn("验证码校验失败: email={}", email);
        return false;
    }
}
