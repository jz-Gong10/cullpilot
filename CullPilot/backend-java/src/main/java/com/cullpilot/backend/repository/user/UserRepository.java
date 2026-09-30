package com.cullpilot.backend.repository.user;

import com.cullpilot.backend.domain.user.User;
import com.cullpilot.backend.domain.user.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByIdAndStatus(String id, UserStatus status);

    boolean existsByEmailIgnoreCase(String email);
}
