package com.vyuha.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    List<AppUser> findAllByOrderByIdAsc();

    List<AppUser> findByRoleAndEnabledTrueOrderByFullNameAsc(Role role);
}
