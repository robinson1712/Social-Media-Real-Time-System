package com.socialapp.post.service;

import com.socialapp.common.enums.Privacy;
import com.socialapp.common.event.KafkaTopics;
import com.socialapp.common.event.PostCreatedEvent;
import com.socialapp.common.exception.BadRequestException;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.ResourceNotFoundException;
import com.socialapp.common.security.CurrentUserContext;
import com.socialapp.post.dto.CreatePostRequest;
import com.socialapp.post.dto.UpdatePostRequest;
import com.socialapp.post.entity.Post;
import com.socialapp.post.repository.PostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

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
 * Unit tests for PostService. CurrentUserContext is set/cleared per test via
 * the test-support seam in common-lib (setForTests/clearForTests) rather than
 * going through a real HTTP request and HeaderAuthFilter.
 */
@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    private PostRepository postRepository;

    private KafkaTemplate<String, Object> kafkaTemplate;
    private PostService postService;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        postService = new PostService(postRepository, kafkaTemplate);
    }

    @AfterEach
    void clearUser() {
        CurrentUserContext.clearForTests();
    }

    private Post existingPost(String id, String authorId) {
        return Post.builder()
                .id(id)
                .authorId(authorId)
                .content("original content")
                .mediaUrls(List.of())
                .privacy(Privacy.PUBLIC)
                .commentCount(0)
                .reactionCount(0)
                .build();
    }

    @Test
    void createPost_withContent_savesAndPublishesEvent() {
        CurrentUserContext.setForTests("author-1", List.of("USER"));
        CreatePostRequest request = new CreatePostRequest("Hello world", null, Privacy.PUBLIC, null, null);
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        Post saved = postService.createPost(request);

        assertThat(saved.getAuthorId()).isEqualTo("author-1");
        assertThat(saved.getContent()).isEqualTo("Hello world");

        ArgumentCaptor<PostCreatedEvent> captor = ArgumentCaptor.forClass(PostCreatedEvent.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.POST_CREATED), eq(saved.getId()), captor.capture());
        assertThat(captor.getValue().authorId()).isEqualTo("author-1");
        assertThat(captor.getValue().privacy()).isEqualTo("PUBLIC");
    }

    @Test
    void createPost_mediaOnlyNoContent_isAllowed() {
        CurrentUserContext.setForTests("author-1", List.of("USER"));
        CreatePostRequest request = new CreatePostRequest(null, List.of("http://img/1.png"), null, null, null);
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        Post saved = postService.createPost(request);

        assertThat(saved.getMediaUrls()).containsExactly("http://img/1.png");
        // privacy wasn't specified — service must default it to PUBLIC.
        assertThat(saved.getPrivacy()).isEqualTo(Privacy.PUBLIC);
    }

    @Test
    void createPost_noContentAndNoMedia_throwsBadRequest() {
        CurrentUserContext.setForTests("author-1", List.of("USER"));
        CreatePostRequest request = new CreatePostRequest("   ", List.of(), Privacy.PUBLIC, null, null);

        assertThatThrownBy(() -> postService.createPost(request))
                .isInstanceOf(BadRequestException.class);

        verify(postRepository, never()).save(any());
    }

    @Test
    void createPost_profaneContent_throwsBadRequestAndNeverSaves() {
        CurrentUserContext.setForTests("author-1", List.of("USER"));
        CreatePostRequest request = new CreatePostRequest("what the fuck is this", null, Privacy.PUBLIC, null, null);

        assertThatThrownBy(() -> postService.createPost(request))
                .isInstanceOf(BadRequestException.class);

        verify(postRepository, never()).save(any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void updatePost_editingInProfanity_throwsBadRequestAndNeverSaves() {
        CurrentUserContext.setForTests("author-1", List.of("USER"));
        when(postRepository.findById("post-1")).thenReturn(Optional.of(existingPost("post-1", "author-1")));

        assertThatThrownBy(() -> postService.updatePost("post-1", new UpdatePostRequest("you bitch", null, null)))
                .isInstanceOf(BadRequestException.class);

        verify(postRepository, never()).save(any());
    }

    @Test
    void removeForModeration_existingPost_deletesIt() {
        Post post = existingPost("post-1", "author-1");
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));

        postService.removeForModeration("post-1");

        verify(postRepository).delete(post);
    }

    @Test
    void removeForModeration_missingPost_isNoOp() {
        when(postRepository.findById("missing")).thenReturn(Optional.empty());

        postService.removeForModeration("missing");

        verify(postRepository, never()).delete(any(Post.class));
    }

    @Test
    void getPost_found_returnsIt() {
        when(postRepository.findById("post-1")).thenReturn(Optional.of(existingPost("post-1", "author-1")));

        Post found = postService.getPost("post-1");

        assertThat(found.getId()).isEqualTo("post-1");
    }

    @Test
    void getPost_missing_throwsResourceNotFound() {
        when(postRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.getPost("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatePost_byAuthor_updatesFields() {
        CurrentUserContext.setForTests("author-1", List.of("USER"));
        Post post = existingPost("post-1", "author-1");
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        Post updated = postService.updatePost("post-1", new UpdatePostRequest("edited content", null, null));

        assertThat(updated.getContent()).isEqualTo("edited content");
        assertThat(updated.getUpdatedAt()).isNotNull();
    }

    @Test
    void updatePost_byNonAuthor_throwsForbidden() {
        CurrentUserContext.setForTests("someone-else", List.of("USER"));
        when(postRepository.findById("post-1")).thenReturn(Optional.of(existingPost("post-1", "author-1")));

        assertThatThrownBy(() -> postService.updatePost("post-1", new UpdatePostRequest("hacked", null, null)))
                .isInstanceOf(ForbiddenException.class);

        verify(postRepository, never()).save(any());
    }

    @Test
    void deletePost_byAuthor_deletes() {
        CurrentUserContext.setForTests("author-1", List.of("USER"));
        Post post = existingPost("post-1", "author-1");
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));

        postService.deletePost("post-1");

        verify(postRepository).delete(post);
    }

    @Test
    void deletePost_byNonAuthor_throwsForbiddenAndNeverDeletes() {
        CurrentUserContext.setForTests("someone-else", List.of("USER"));
        when(postRepository.findById("post-1")).thenReturn(Optional.of(existingPost("post-1", "author-1")));

        assertThatThrownBy(() -> postService.deletePost("post-1"))
                .isInstanceOf(ForbiddenException.class);

        verify(postRepository, never()).delete(any(Post.class));
    }

    @Test
    void incrementCommentCount_existingPost_incrementsAndSaves() {
        Post post = existingPost("post-1", "author-1");
        post.setCommentCount(2);
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));

        postService.incrementCommentCount("post-1");

        assertThat(post.getCommentCount()).isEqualTo(3);
        verify(postRepository).save(post);
    }

    @Test
    void incrementCommentCount_missingPost_isNoOp() {
        when(postRepository.findById("missing")).thenReturn(Optional.empty());

        postService.incrementCommentCount("missing");

        verify(postRepository, never()).save(any());
    }

    @Test
    void applyReactionDelta_added_incrementsCount() {
        Post post = existingPost("post-1", "author-1");
        post.setReactionCount(4);
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));

        postService.applyReactionDelta("post-1", false);

        assertThat(post.getReactionCount()).isEqualTo(5);
    }

    @Test
    void applyReactionDelta_removed_decrementsCount() {
        Post post = existingPost("post-1", "author-1");
        post.setReactionCount(4);
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));

        postService.applyReactionDelta("post-1", true);

        assertThat(post.getReactionCount()).isEqualTo(3);
    }

    @Test
    void applyReactionDelta_removedAtZero_clampsAtZeroInsteadOfGoingNegative() {
        Post post = existingPost("post-1", "author-1");
        post.setReactionCount(0);
        when(postRepository.findById("post-1")).thenReturn(Optional.of(post));

        postService.applyReactionDelta("post-1", true);

        assertThat(post.getReactionCount()).isZero();
    }
}
