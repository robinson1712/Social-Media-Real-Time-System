package com.socialapp.feed.service;

import com.socialapp.feed.client.PostClient;
import com.socialapp.feed.dto.PostDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedQueryService {

    private static final String FEED_KEY_PREFIX = "feed:";

    private final StringRedisTemplate redisTemplate;
    private final PostClient postClient;

    public List<PostDto> getFeed(String userId, int page, int size) {
        String key = FEED_KEY_PREFIX + userId;
        long start = (long) page * size;
        long end = start + size - 1;

        Set<String> postIdSet = redisTemplate.opsForZSet().reverseRange(key, start, end);
        if (postIdSet == null || postIdSet.isEmpty()) {
            return Collections.emptyList();
        }

        // reverseRange already returns elements ordered by descending score.
        List<String> postIds = new ArrayList<>(new LinkedHashSet<>(postIdSet));

        List<PostDto> posts = fetchPosts(postIds);
        Map<String, PostDto> postsById = posts.stream()
                .collect(Collectors.toMap(PostDto::id, p -> p, (a, b) -> a));

        return postIds.stream()
                .map(postsById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<PostDto> fetchPosts(List<String> postIds) {
        try {
            var response = postClient.getBatch(String.join(",", postIds));
            if (response == null || response.data() == null) {
                return Collections.emptyList();
            }
            return response.data();
        } catch (Exception e) {
            log.warn("Failed to hydrate posts {}: {}", postIds, e.getMessage());
            return Collections.emptyList();
        }
    }
}
