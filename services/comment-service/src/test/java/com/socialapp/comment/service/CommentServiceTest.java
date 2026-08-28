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
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CommentService. The Feign client (post-service) and Kafka
 * publishing are mocked; CurrentUserContext is set/cleared per test via the
 * test-support seam in common-lib rather than a real HTTP request.
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock
    private CommentRepository commentRepository;
    @Mock
    private PostClient postClient;

    private KafkaTemplate<String, Object> kafkaTemplate;
    private CommentService commentService;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        commentService = new CommentService(commentRepository, postClient, kafkaTemplate);
    }

    @AfterEach
    void clearUser() {
        CurrentUserContext.clearForTests();
    }

    private Comment existingComment(String id, String authorId, String postId) {
        return Comment.builder()
                .id(id)
                .postId(postId)
                .authorId(authorId)
                .postOwnerId("post-owner-1")
                .content("original content")
                .build();
    }

    private FeignException notFound() {
        Request request = Request.create(Request.HttpMethod.GET, "/api/posts/missing",
                java.util.Map.of(), null, StandardCharsets.UTF_8, new RequestTemplate());
        return new FeignException.NotFound("not found", request, null, null);
    }

    @Test
    void createComment_topLevel_resolvesOwnerSavesAndPublishesEvent() {
        CurrentUserContext.setForTests("author-1", List.of("USER"));
        CreateCommentRequest request = new CreateCommentRequest("post-1", "Nice post!", null);
        when(postClient.getPost("post-1")).thenReturn(ApiResponse.success(new PostDto("post-1", "post-owner-1")));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        Comment saved = commentService.createComment(request);

        assertThat(saved.getAuthorId()).isEqualTo("author-1");
        assertThat(saved.getPostOwnerId()).isEqualTo("post-owner-1");
        assertThat(saved.getPostId()).isEqualTo("post-1");
        assertThat(saved.getParentCommentId()).isNull();

        ArgumentCaptor<CommentCreatedEvent> captor = ArgumentCaptor.forClass(CommentCreatedEvent.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.COMMENT_CREATED), eq(saved.getId()), captor.capture());
        CommentCreatedEvent event = captor.getValue();
        assertThat(event.commentId()).isEqualTo(saved.getId());
        assertThat(event.postId()).isEqualTo("post-1");
        assertThat(event.authorId()).isEqualTo("author-1");
        assertThat(event.postOwnerId()).isEqualTo("post-owner-1");
        assertThat(event.parentCommentId()).isNull();
    }

    @Test
    void createComment_reply_setsParentCommentIdOnEntityAndEvent() {
        CurrentUserContext.setForTests("author-2", List.of("USER"));
        CreateCommentRequest request = new CreateCommentRequest("post-1", "A reply", "parent-comment-1");
        when(postClient.getPost("post-1")).thenReturn(ApiResponse.success(new PostDto("post-1", "post-owner-1")));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        Comment saved = commentService.createComment(request);

        assertThat(saved.getParentCommentId()).isEqualTo("parent-comment-1");

        ArgumentCaptor<CommentCreatedEvent> captor = ArgumentCaptor.forClass(CommentCreatedEvent.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.COMMENT_CREATED), eq(saved.getId()), captor.capture());
        assertThat(captor.getValue().parentCommentId()).isEqualTo("parent-comment-1");
    }

    @Test
    void createComment_postNotFound_translatesToResourceNotFoundAndNeverSaves() {
        CurrentUserContext.setForTests("author-1", List.of("USER"));
        CreateCommentRequest request = new CreateCommentRequest("missing", "content", null);
        when(postClient.getPost("missing")).thenThrow(notFound());

        assertThatThrownBy(() -> commentService.createComment(request))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(commentRepository, never()).save(any());
        verify(kafkaTemplate, never()).send(any(String.class), any(), any());
    }

    @Test
    void createComment_postClientReturnsNullData_throwsResourceNotFound() {
        CurrentUserContext.setForTests("author-1", List.of("USER"));
        CreateCommentRequest request = new CreateCommentRequest("post-1", "content", null);
        when(postClient.getPost("post-1")).thenReturn(ApiResponse.success(null));

        assertThatThrownBy(() -> commentService.createComment(request))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(commentRepository, never()).save(any());
    }

    @Test
    void createComment_blankPostId_throwsBadRequestAndNeverCallsPostClient() {
        CreateCommentRequest request = new CreateCommentRequest("  ", "content", null);

        assertThatThrownBy(() -> commentService.createComment(request))
                .isInstanceOf(BadRequestException.class);

        verify(postClient, never()).getPost(any());
        verify(commentRepository, never()).save(any());
    }

    @Test
    void createComment_nullPostId_throwsBadRequest() {
        CreateCommentRequest request = new CreateCommentRequest(null, "content", null);

        assertThatThrownBy(() -> commentService.createComment(request))
                .isInstanceOf(BadRequestException.class);

        verify(commentRepository, never()).save(any());
    }

    @Test
    void createComment_blankContent_throwsBadRequest() {
        CreateCommentRequest request = new CreateCommentRequest("post-1", "   ", null);

        assertThatThrownBy(() -> commentService.createComment(request))
                .isInstanceOf(BadRequestException.class);

        verify(postClient, never()).getPost(any());
        verify(commentRepository, never()).save(any());
    }

    @Test
    void createComment_nullContent_throwsBadRequest() {
        CreateCommentRequest request = new CreateCommentRequest("post-1", null, null);

        assertThatThrownBy(() -> commentService.createComment(request))
                .isInstanceOf(BadRequestException.class);

        verify(commentRepository, never()).save(any());
    }

    @Test
    void updateComment_byAuthor_updatesContent() {
        CurrentUserContext.setForTests("author-1", List.of("USER"));
        Comment comment = existingComment("comment-1", "author-1", "post-1");
        when(commentRepository.findById("comment-1")).thenReturn(Optional.of(comment));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        Comment updated = commentService.updateComment("comment-1", new UpdateCommentRequest("edited content"));

        assertThat(updated.getContent()).isEqualTo("edited content");
        assertThat(updated.getUpdatedAt()).isNotNull();
    }

    @Test
    void updateComment_byNonAuthor_throwsForbiddenAndNeverSaves() {
        CurrentUserContext.setForTests("someone-else", List.of("USER"));
        Comment comment = existingComment("comment-1", "author-1", "post-1");
        when(commentRepository.findById("comment-1")).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.updateComment("comment-1", new UpdateCommentRequest("hacked")))
                .isInstanceOf(ForbiddenException.class);

        verify(commentRepository, never()).save(any());
    }

    @Test
    void updateComment_missing_throwsResourceNotFound() {
        when(commentRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.updateComment("missing", new UpdateCommentRequest("x")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateComment_alreadyDeleted_throwsResourceNotFound() {
        Comment comment = existingComment("comment-1", "author-1", "post-1");
        comment.setDeleted(true);
        when(commentRepository.findById("comment-1")).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.updateComment("comment-1", new UpdateCommentRequest("x")))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(commentRepository, never()).save(any());
    }

    @Test
    void deleteComment_byAuthor_softDeletesAndStillSaves() {
        CurrentUserContext.setForTests("author-1", List.of("USER"));
        Comment comment = existingComment("comment-1", "author-1", "post-1");
        when(commentRepository.findById("comment-1")).thenReturn(Optional.of(comment));

        commentService.deleteComment("comment-1");

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(captor.capture());
        assertThat(captor.getValue().isDeleted()).isTrue();
        assertThat(captor.getValue().getId()).isEqualTo("comment-1");
        verify(commentRepository, never()).delete(any(Comment.class));
        verify(commentRepository, never()).deleteById(any());
    }

    @Test
    void deleteComment_byNonAuthor_throwsForbiddenAndNeverSaves() {
        CurrentUserContext.setForTests("someone-else", List.of("USER"));
        Comment comment = existingComment("comment-1", "author-1", "post-1");
        when(commentRepository.findById("comment-1")).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.deleteComment("comment-1"))
                .isInstanceOf(ForbiddenException.class);

        verify(commentRepository, never()).save(any());
    }

    @Test
    void deleteComment_missing_throwsResourceNotFound() {
        when(commentRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.deleteComment("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteComment_alreadyDeleted_throwsResourceNotFound() {
        Comment comment = existingComment("comment-1", "author-1", "post-1");
        comment.setDeleted(true);
        when(commentRepository.findById("comment-1")).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.deleteComment("comment-1"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(commentRepository, never()).save(any());
    }
}
