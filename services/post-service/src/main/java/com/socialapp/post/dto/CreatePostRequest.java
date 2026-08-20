package com.socialapp.post.dto;

import com.socialapp.common.enums.Privacy;

import java.util.List;

public record CreatePostRequest(
        String content,
        List<String> mediaUrls,
        Privacy privacy,
        String groupId,
        String pageId
) {
}
