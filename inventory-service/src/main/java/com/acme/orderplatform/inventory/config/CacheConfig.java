package com.acme.orderplatform.inventory.config;

import com.acme.orderplatform.inventory.service.InventoryService;
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

@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    @Bean
    RedisCacheManagerBuilderCustomizer stockCacheCustomizer(ObjectMapper mapper,
                                                            @Value("${app.cache.stock-ttl:PT30S}") Duration ttl) {
        var serializer = new Jackson2JsonRedisSerializer<>(mapper, InventoryService.StockView.class);
        return builder -> builder.withCacheConfiguration(InventoryService.STOCK_CACHE, RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .prefixCacheNameWith("inventory-service:v1:")
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer)));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
