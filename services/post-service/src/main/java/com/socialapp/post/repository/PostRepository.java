package com.socialapp.post.repository;

import com.socialapp.post.entity.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PostRepository extends JpaRepository<Post, String> {

    Page<Post> findByAuthorIdOrderByCreatedAtDesc(String authorId, Pageable pageable);

    Page<Post> findByGroupIdOrderByCreatedAtDesc(String groupId, Pageable pageable);

    Page<Post> findByPageIdOrderByCreatedAtDesc(String pageId, Pageable pageable);

    List<Post> findByIdIn(List<String> ids);
}
