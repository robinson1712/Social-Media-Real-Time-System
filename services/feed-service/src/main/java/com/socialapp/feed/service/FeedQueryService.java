package com.socialapp.feed.service;

import com.socialapp.feed.client.PostClient;
import com.socialapp.feed.dto.PostDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads pull a bounded, recency-ordered candidate window out of Redis, then
 * re-rank it by a Hacker-News-style gravity score: engagement (reactions +
 * heavier-weighted comments) pushes a post up, but the boost decays as the
 * post ages, so a post that went viral yesterday doesn't permanently bury
 * everything posted since. Fanout (see FeedFanoutService) still writes pure
 * recency into the ZSET — ranking only happens at read time, which keeps
 * writes cheap and means the ranking formula can change without touching any
 * stored data.
 * <p>
 * PostClient carries its own Resilience4j circuit breaker + fallback (see
 * application.yml and PostClientFallbackFactory) — if post-service is down,
 * fetchPosts() below simply gets an empty list back instead of throwing, so a
 * feed request degrades to "temporarily empty" rather than failing outright.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedQueryService {

    private static final String FEED_KEY_PREFIX = "feed:";

    /** How far back into each user's feed a read is willing to re-rank. */
    private static final int CANDIDATE_POOL_SIZE = 200;

    private static final double REACTION_WEIGHT = 1.0;
    private static final double COMMENT_WEIGHT = 2.0;
    /** Hacker News uses 1.8; a social feed wants engagement to decay a bit slower. */
    private static final double GRAVITY = 1.5;

    private final StringRedisTemplate redisTemplate;
    private final PostClient postClient;

    public List<PostDto> getFeed(String userId, int page, int size) {
        String key = FEED_KEY_PREFIX + userId;

        Set<String> pool = redisTemplate.opsForZSet().reverseRange(key, 0, CANDIDATE_POOL_SIZE - 1);
        if (pool == null || pool.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> postIds = new ArrayList<>(new LinkedHashSet<>(pool));

        List<PostDto> ranked = fetchPosts(postIds).stream()
                .sorted(Comparator.comparingDouble(this::rankScore).reversed())
                .toList();

        int from = Math.min(page * size, ranked.size());
        int to = Math.min(from + size, ranked.size());
        return ranked.subList(from, to);
    }

    private double rankScore(PostDto post) {
        double ageHours = post.createdAt() == null
                ? 0
                : Duration.between(post.createdAt(), Instant.now()).toMinutes() / 60.0;

        // Pure time decay — with zero engagement this alone reduces to a plain
        // recency ordering, so a feed of never-reacted-to posts still reads
        // newest-first exactly like before this feature existed.
        double recencyScore = 1.0 / Math.pow(ageHours + 2, GRAVITY);

        // Multiplicative boost from engagement, comments weighted heavier than
        // reactions since leaving a comment is a stronger signal than a tap.
        double engagement = REACTION_WEIGHT * post.reactionCount() + COMMENT_WEIGHT * post.commentCount();
        double engagementBoost = Math.log(1 + engagement);

        return recencyScore * (1 + engagementBoost);
    }

    private List<PostDto> fetchPosts(List<String> postIds) {
        var response = postClient.getBatch(String.join(",", postIds));
        if (response == null || response.data() == null) {
            return Collections.emptyList();
        }
        return response.data();
    }
}
