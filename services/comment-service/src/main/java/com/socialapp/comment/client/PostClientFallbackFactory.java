package com.socialapp.comment.client;

import com.socialapp.common.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * Invoked by the circuit breaker for anything except a plain 404 from
 * post-service — 404 ("post not found") is a legitimate business outcome and is
 * configured as an ignored exception (see application.yml), so it always
 * propagates to CommentService untouched instead of landing here. Everything
 * that does land here (timeouts, connection failures, an open circuit, 5xx)
 * means post-service itself is unreachable, which is a different failure the
 * caller needs to tell apart from "that post doesn't exist".
 */
@Slf4j
@Component
public class PostClientFallbackFactory implements FallbackFactory<PostClient> {

    @Override
    public PostClient create(Throwable cause) {
        return id -> {
            log.warn("post-service unavailable while resolving post {}: {}", id, cause.getMessage());
            throw new ServiceUnavailableException("post-service is currently unavailable, please try again later");
        };
    }
}
