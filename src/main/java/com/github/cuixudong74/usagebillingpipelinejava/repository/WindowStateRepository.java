package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.domain.WindowState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WindowStateRepository extends JpaRepository<WindowState, Long> {

    Optional<WindowState> findByAccountIdAndWindowId(String accountId, String windowId);

    List<WindowState> findByStatus(WindowState.Status status);
}
