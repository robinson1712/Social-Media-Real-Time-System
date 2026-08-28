package com.socialapp.comment.client;

import com.socialapp.common.dto.ApiResponse;
import com.socialapp.comment.dto.PostDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "post-service", fallbackFactory = PostClientFallbackFactory.class)
public interface PostClient {

    @GetMapping("/api/posts/{id}")
    ApiResponse<PostDto> getPost(@PathVariable("id") String id);
}
