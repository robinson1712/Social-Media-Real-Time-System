package com.socialapp.user.repository;

import com.socialapp.user.entity.Block;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BlockRepository extends JpaRepository<Block, String> {
    Optional<Block> findByBlockerIdAndBlockedId(String blockerId, String blockedId);
    boolean existsByBlockerIdAndBlockedId(String blockerId, String blockedId);
    Page<Block> findByBlockerId(String blockerId, Pageable pageable);

    default boolean existsEitherDirection(String userA, String userB) {
        return existsByBlockerIdAndBlockedId(userA, userB) || existsByBlockerIdAndBlockedId(userB, userA);
    }
}
