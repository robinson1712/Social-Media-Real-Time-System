package com.socialapp.comment.dto;

public record CreateCommentRequest(String postId, String content, String parentCommentId) {
}
