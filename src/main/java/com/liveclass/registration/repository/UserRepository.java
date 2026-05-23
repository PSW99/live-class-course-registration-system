package com.liveclass.registration.repository;

import com.liveclass.registration.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
