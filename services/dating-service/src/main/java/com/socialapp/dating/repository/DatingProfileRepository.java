package com.socialapp.dating.repository;

import com.socialapp.dating.entity.DatingProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DatingProfileRepository extends JpaRepository<DatingProfile, String> {

    @Query("SELECT p FROM DatingProfile p WHERE p.active = true AND p.id <> :selfId " +
            "AND p.id NOT IN (SELECT s.targetId FROM Swipe s WHERE s.swiperId = :selfId)")
    Page<DatingProfile> findCandidates(@Param("selfId") String selfId, Pageable pageable);
}
