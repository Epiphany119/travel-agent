package com.travel.common.http;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 客户端默认超时。
 *
 * <p>所有对外部服务的同步请求都必须有连接和读取上限，避免网络异常时占满业务线程。
 * 8 秒读取窗口给第三方接口留出正常抖动空间，同时不会让行程规划长时间卡住。</p>
 */
public final class HttpClientSupport {

    public static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    public static final int READ_TIMEOUT_MILLIS = 8_000;

    private HttpClientSupport() {
    }

    /**
     * 创建带连接/读取超时的底层请求工厂。
     */
    public static SimpleClientHttpRequestFactory newRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        factory.setReadTimeout(READ_TIMEOUT_MILLIS);
        return factory;
    }

    /**
     * 创建带连接/读取超时的 RestTemplate。
     */
    public static RestTemplate newRestTemplate() {
        return new RestTemplate(newRequestFactory());
    }
}
