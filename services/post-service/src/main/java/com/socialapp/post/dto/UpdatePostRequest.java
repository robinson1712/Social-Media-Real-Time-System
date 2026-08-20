package com.socialapp.post.dto;

import com.socialapp.common.enums.Privacy;

import java.util.List;

public record UpdatePostRequest(
        String content,
        List<String> mediaUrls,
        Privacy privacy
) {
}
