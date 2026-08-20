package com.socialapp.comment.service;

import com.socialapp.comment.client.PostClient;
import com.socialapp.comment.dto.CreateCommentRequest;
import com.socialapp.comment.dto.PostDto;
import com.socialapp.comment.dto.UpdateCommentRequest;
import com.socialapp.comment.entity.Comment;
import com.socialapp.comment.repository.CommentRepository;
import com.socialapp.common.dto.ApiResponse;
import com.socialapp.common.event.CommentCreatedEvent;
import com.socialapp.common.event.KafkaTopics;
import com.socialapp.common.exception.BadRequestException;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.ResourceNotFoundException;
import com.socialapp.common.security.CurrentUserContext;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepository;
    private final PostClient postClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public Comment createComment(CreateCommentRequest request) {
        if (request.postId() == null || request.postId().isBlank()) {
            throw new BadRequestException("postId is required");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new BadRequestException("content must not be blank");
        }

        String postOwnerId = resolvePostOwnerId(request.postId());
        String authorId = CurrentUserContext.getUserId();

        Comment comment = Comment.builder()
                .postId(request.postId())
                .authorId(authorId)
                .postOwnerId(postOwnerId)
                .parentCommentId(request.parentCommentId())
                .content(request.content())
                .build();

        Comment saved = commentRepository.save(comment);

        CommentCreatedEvent event = new CommentCreatedEvent(
                saved.getId(), saved.getPostId(), saved.getAuthorId(), saved.getPostOwnerId(),
                saved.getParentCommentId(), Instant.now());
        kafkaTemplate.send(KafkaTopics.COMMENT_CREATED, saved.getId(), event);

        return saved;
    }

    private String resolvePostOwnerId(String postId) {
        try {
            ApiResponse<PostDto> response = postClient.getPost(postId);
            if (response == null || response.data() == null) {
                throw new ResourceNotFoundException("Post not found: " + postId);
            }
            return response.data().authorId();
        } catch (FeignException.NotFound ex) {
            throw new ResourceNotFoundException("Post not found: " + postId);
        }
    }

    public Page<Comment> getTopLevelComments(String postId, Pageable pageable) {
        return commentRepository.findByPostIdAndParentCommentIdIsNullAndDeletedFalseOrderByCreatedAtDesc(postId, pageable);
    }

    public Page<Comment> getReplies(String parentCommentId, Pageable pageable) {
        return commentRepository.findByParentCommentIdAndDeletedFalseOrderByCreatedAtAsc(parentCommentId, pageable);
    }

    private Comment getOwnedActiveComment(String id) {
        Comment comment = commentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Comment not found: " + id));
        if (comment.isDeleted()) {
            throw new ResourceNotFoundException("Comment not found: " + id);
        }
        return comment;
    }

    public Comment updateComment(String id, UpdateCommentRequest request) {
        Comment comment = getOwnedActiveComment(id);
        String currentUserId = CurrentUserContext.getUserId();
        if (!comment.getAuthorId().equals(currentUserId)) {
            throw new ForbiddenException("Only the author can edit this comment");
        }
        comment.setContent(request.content());
        comment.setUpdatedAt(Instant.now());
        return commentRepository.save(comment);
    }

    public void deleteComment(String id) {
        Comment comment = getOwnedActiveComment(id);
        String currentUserId = CurrentUserContext.getUserId();
        if (!comment.getAuthorId().equals(currentUserId)) {
            throw new ForbiddenException("Only the author can delete this comment");
        }
        comment.setDeleted(true);
        comment.setUpdatedAt(Instant.now());
        commentRepository.save(comment);
    }
}
