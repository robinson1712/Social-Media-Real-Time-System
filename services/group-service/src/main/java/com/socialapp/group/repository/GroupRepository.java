package com.socialapp.group.repository;

import com.socialapp.group.entity.Group;
import com.socialapp.group.entity.GroupPrivacy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupRepository extends JpaRepository<Group, String> {

    Page<Group> findByPrivacy(GroupPrivacy privacy, Pageable pageable);

    Page<Group> findByPrivacyAndNameContainingIgnoreCase(GroupPrivacy privacy, String name, Pageable pageable);
}
