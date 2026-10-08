package com.healthcare.ecosystem.allocation.repository;

import com.healthcare.ecosystem.allocation.model.entity.BedStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BedStatusLogRepository extends JpaRepository<BedStatusLog, Long> {

    List<BedStatusLog> findByBedIdOrderByLoggedAtDesc(Long bedId);

    List<BedStatusLog> findByBedIdOrderByLoggedAtAsc(Long bedId);
}