package com.socialapp.feed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PostDto(
        String id,
        String authorId,
        String content,
        List<String> mediaUrls,
        String privacy,
        String groupId,
        String pageId,
        long commentCount,
        long reactionCount,
        Instant createdAt
) {
}
