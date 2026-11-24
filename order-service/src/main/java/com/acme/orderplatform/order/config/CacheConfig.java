package com.acme.orderplatform.order.config;

import com.acme.orderplatform.order.api.OrderDtos;
import com.acme.orderplatform.order.service.OrderCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Cache-aside on Redis (ElastiCache in AWS) for the hot read path GET /orders/{id}.
 * Postgres stays the system of record; Redis being down only costs latency, never correctness.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    @Bean
    RedisCacheManagerBuilderCustomizer orderCacheCustomizer(ObjectMapper mapper,
                                                            @Value("${app.cache.orders-ttl:PT10M}") Duration ttl) {
        // Typed serializer: no polymorphic type info in Redis (safer, smaller, language-neutral).
        var serializer = new Jackson2JsonRedisSerializer<>(mapper, OrderDtos.OrderResponse.class);
        return builder -> builder.withCacheConfiguration(OrderCache.ORDERS, RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                // Version the key space: a deploy that changes the DTO shape never reads old entries.
                .prefixCacheNameWith("order-service:v1:")
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer)));
    }

    /** Redis errors are logged and treated as cache misses instead of failing the request. */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
