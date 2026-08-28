package com.socialapp.feed.service;

import com.socialapp.common.dto.ApiResponse;
import com.socialapp.feed.client.PostClient;
import com.socialapp.feed.dto.PostDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for FeedQueryService — the Redis candidate-pool read, the
 * Hacker-News-style gravity ranking formula (engagement boost + recency
 * decay), and in-memory pagination over the ranked result.
 */
@ExtendWith(MockitoExtension.class)
class FeedQueryServiceTest {

    private static final String KEY = "feed:user1";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private PostClient postClient;

    private FeedQueryService feedQueryService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        feedQueryService = new FeedQueryService(redisTemplate, postClient);
    }

    private void mockPool(List<String> postIds) {
        Set<String> pool = new LinkedHashSet<>(postIds);
        when(zSetOperations.reverseRange(eq(KEY), eq(0L), anyLong())).thenReturn(pool);
    }

    private void mockPosts(List<PostDto> posts) {
        when(postClient.getBatch(anyString())).thenReturn(ApiResponse.success(posts));
    }

    private static PostDto post(String id, Instant createdAt, long reactionCount, long commentCount) {
        return new PostDto(id, "author1", "content", List.of(), "PUBLIC", null, null,
                commentCount, reactionCount, createdAt);
    }

    // ---------- empty pool ----------

    @Test
    void getFeed_nullPool_returnsEmptyListWithoutCallingPostClient() {
        when(zSetOperations.reverseRange(eq(KEY), eq(0L), anyLong())).thenReturn(null);

        List<PostDto> result = feedQueryService.getFeed("user1", 0, 10);

        assertThat(result).isEmpty();
        verify(postClient, never()).getBatch(anyString());
    }

    @Test
    void getFeed_emptyPool_returnsEmptyListWithoutCallingPostClient() {
        when(zSetOperations.reverseRange(eq(KEY), eq(0L), anyLong())).thenReturn(Set.of());

        List<PostDto> result = feedQueryService.getFeed("user1", 0, 10);

        assertThat(result).isEmpty();
        verify(postClient, never()).getBatch(anyString());
    }

    // ---------- ranking ----------

    @Test
    void getFeed_sameAge_higherEngagementRanksFirst() {
        Instant now = Instant.now();
        PostDto lowEngagement = post("low", now.minus(5, ChronoUnit.HOURS), 0, 0);
        PostDto highEngagement = post("high", now.minus(5, ChronoUnit.HOURS), 10, 5);
        mockPool(List.of("low", "high"));
        mockPosts(List.of(lowEngagement, highEngagement));

        List<PostDto> result = feedQueryService.getFeed("user1", 0, 10);

        assertThat(result).extracting(PostDto::id).containsExactly("high", "low");
    }

    @Test
    void getFeed_zeroEngagementForAll_fallsBackToPureRecencyNewestFirst() {
        Instant now = Instant.now();
        PostDto older = post("older", now.minus(10, ChronoUnit.HOURS), 0, 0);
        PostDto newer = post("newer", now.minus(1, ChronoUnit.HOURS), 0, 0);
        mockPool(List.of("older", "newer"));
        mockPosts(List.of(older, newer));

        List<PostDto> result = feedQueryService.getFeed("user1", 0, 10);

        assertThat(result).extracting(PostDto::id).containsExactly("newer", "older");
    }

    @Test
    void getFeed_heavyEngagementCanOutrankAnOlderPostOverANewerOne() {
        // A much older but heavily-engaged post should still be able to beat a
        // very fresh post with zero engagement, demonstrating the engagement
        // boost is not merely a tiebreaker.
        Instant now = Instant.now();
        PostDto viralButOld = post("viral", now.minus(6, ChronoUnit.HOURS), 500, 200);
        PostDto freshNoEngagement = post("fresh", now.minus(5, ChronoUnit.MINUTES), 0, 0);
        mockPool(List.of("fresh", "viral"));
        mockPosts(List.of(viralButOld, freshNoEngagement));

        List<PostDto> result = feedQueryService.getFeed("user1", 0, 10);

        assertThat(result).extracting(PostDto::id).containsExactly("viral", "fresh");
    }

    // ---------- pagination ----------

    @Test
    void getFeed_pagination_slicesRankedOrderNotRedisOrder() {
        Instant now = Instant.now();
        // Deliberately mocked pool order is scrambled relative to actual recency
        // ranking, to prove pagination slices the RANKED list, not pool order.
        PostDto p1 = post("p1", now.minus(1, ChronoUnit.HOURS), 0, 0);
        PostDto p2 = post("p2", now.minus(2, ChronoUnit.HOURS), 0, 0);
        PostDto p3 = post("p3", now.minus(3, ChronoUnit.HOURS), 0, 0);
        PostDto p4 = post("p4", now.minus(4, ChronoUnit.HOURS), 0, 0);
        PostDto p5 = post("p5", now.minus(5, ChronoUnit.HOURS), 0, 0);
        mockPool(List.of("p3", "p1", "p5", "p2", "p4"));
        mockPosts(List.of(p3, p1, p5, p2, p4));

        List<PostDto> firstPage = feedQueryService.getFeed("user1", 0, 2);
        List<PostDto> secondPage = feedQueryService.getFeed("user1", 1, 2);
        List<PostDto> thirdPage = feedQueryService.getFeed("user1", 2, 2);

        assertThat(firstPage).extracting(PostDto::id).containsExactly("p1", "p2");
        assertThat(secondPage).extracting(PostDto::id).containsExactly("p3", "p4");
        assertThat(thirdPage).extracting(PostDto::id).containsExactly("p5");
    }

    @Test
    void getFeed_pageBeyondAvailableResults_returnsEmptyList() {
        Instant now = Instant.now();
        mockPool(List.of("p1"));
        mockPosts(List.of(post("p1", now, 0, 0)));

        List<PostDto> result = feedQueryService.getFeed("user1", 5, 10);

        assertThat(result).isEmpty();
    }

    // ---------- postClient fallback ----------

    @Test
    void getFeed_postClientReturnsNullData_returnsEmptyList() {
        mockPool(List.of("p1"));
        when(postClient.getBatch(anyString())).thenReturn(ApiResponse.error("post-service unavailable"));

        List<PostDto> result = feedQueryService.getFeed("user1", 0, 10);

        assertThat(result).isEmpty();
    }

    @Test
    void getFeed_postClientReturnsNullResponse_returnsEmptyList() {
        mockPool(List.of("p1"));
        when(postClient.getBatch(anyString())).thenReturn(null);

        List<PostDto> result = feedQueryService.getFeed("user1", 0, 10);

        assertThat(result).isEmpty();
    }

    @Test
    void getFeed_postClientReturnsEmptyBatch_returnsEmptyList() {
        mockPool(List.of("p1"));
        mockPosts(List.of());

        List<PostDto> result = feedQueryService.getFeed("user1", 0, 10);

        assertThat(result).isEmpty();
    }
}
