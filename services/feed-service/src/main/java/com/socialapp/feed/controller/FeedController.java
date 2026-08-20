package com.socialapp.feed.controller;

import com.socialapp.common.dto.ApiResponse;
import com.socialapp.common.exception.UnauthorizedException;
import com.socialapp.common.security.CurrentUserContext;
import com.socialapp.feed.dto.PostDto;
import com.socialapp.feed.service.FeedQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedQueryService feedQueryService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<PostDto>>> getMyFeed(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        String userId = CurrentUserContext.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("Authentication required");
        }

        List<PostDto> feed = feedQueryService.getFeed(userId, page, size);
        return ResponseEntity.ok(ApiResponse.success(feed));
    }
}
