package com.socialapp.post.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public Post createPost(CreatePostRequest request) {
        boolean hasContent = request.content() != null && !request.content().isBlank();
        boolean hasMedia = request.mediaUrls() != null && !request.mediaUrls().isEmpty();
        if (!hasContent && !hasMedia) {
            throw new BadRequestException("A post requires at least content or media");
        }

        String authorId = CurrentUserContext.getUserId();
        Post post = Post.builder()
                .authorId(authorId)
                .content(request.content())
                .mediaUrls(request.mediaUrls() != null ? request.mediaUrls() : Collections.emptyList())
                .privacy(request.privacy() != null ? request.privacy() : com.socialapp.common.enums.Privacy.PUBLIC)
                .groupId(request.groupId())
                .pageId(request.pageId())
                .build();

        Post saved = postRepository.save(post);

        PostCreatedEvent event = new PostCreatedEvent(
                saved.getId(), saved.getAuthorId(), saved.getGroupId(), saved.getPageId(),
                saved.getPrivacy().name(), Instant.now());
        kafkaTemplate.send(KafkaTopics.POST_CREATED, saved.getId(), event);

        return saved;
    }

    public Post getPost(String id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found: " + id));
    }

    public Post updatePost(String id, UpdatePostRequest request) {
        Post post = getPost(id);
        String currentUserId = CurrentUserContext.getUserId();
        if (!post.getAuthorId().equals(currentUserId)) {
            throw new ForbiddenException("Only the author can update this post");
        }
        if (request.content() != null) {
            post.setContent(request.content());
        }
        if (request.mediaUrls() != null) {
            post.setMediaUrls(request.mediaUrls());
        }
        if (request.privacy() != null) {
            post.setPrivacy(request.privacy());
        }
        post.setUpdatedAt(Instant.now());
        return postRepository.save(post);
    }

    public void deletePost(String id) {
        Post post = getPost(id);
        String currentUserId = CurrentUserContext.getUserId();
        if (!post.getAuthorId().equals(currentUserId)) {
            throw new ForbiddenException("Only the author can delete this post");
        }
        postRepository.delete(post);
    }

    public Page<Post> getPostsByAuthor(String authorId, Pageable pageable) {
        return postRepository.findByAuthorIdOrderByCreatedAtDesc(authorId, pageable);
    }

    public Page<Post> getPostsByGroup(String groupId, Pageable pageable) {
        return postRepository.findByGroupIdOrderByCreatedAtDesc(groupId, pageable);
    }

    public Page<Post> getPostsByPage(String pageId, Pageable pageable) {
        return postRepository.findByPageIdOrderByCreatedAtDesc(pageId, pageable);
    }

    public List<Post> getPostsByIds(List<String> ids) {
        return postRepository.findByIdIn(ids);
    }

    public void incrementCommentCount(String postId) {
        postRepository.findById(postId).ifPresent(post -> {
            post.setCommentCount(post.getCommentCount() + 1);
            postRepository.save(post);
        });
    }

    public void applyReactionDelta(String postId, boolean removed) {
        postRepository.findById(postId).ifPresent(post -> {
            int newCount = removed ? post.getReactionCount() - 1 : post.getReactionCount() + 1;
            post.setReactionCount(Math.max(newCount, 0));
            postRepository.save(post);
        });
    }
}
