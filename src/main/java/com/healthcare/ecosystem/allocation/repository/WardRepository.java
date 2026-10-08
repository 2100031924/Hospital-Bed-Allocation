package com.healthcare.ecosystem.allocation.repository;

import com.healthcare.ecosystem.allocation.model.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WardRepository extends JpaRepository<Ward, Long> {

    List<Ward> findByHospitalId(Long hospitalId);

    Optional<Ward> findByIdAndHospitalId(Long id, Long hospitalId);
}