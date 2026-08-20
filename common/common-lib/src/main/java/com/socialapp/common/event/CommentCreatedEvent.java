package com.socialapp.common.event;

import java.time.Instant;

public record CommentCreatedEvent(String commentId, String postId, String authorId, String postOwnerId,
                                   String parentCommentId, Instant occurredAt) {
}
