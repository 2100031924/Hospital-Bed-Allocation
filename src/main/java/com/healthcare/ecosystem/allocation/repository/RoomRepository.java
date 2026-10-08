package com.healthcare.ecosystem.allocation.repository;

import com.healthcare.ecosystem.allocation.model.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByWardId(Long wardId);

    Optional<Room> findByWardIdAndRoomNumber(Long wardId, String roomNumber);


    @Query("SELECT r FROM Room r " +
           "JOIN FETCH r.ward w " +
           "WHERE w.hospital.id = :hospitalId " +
           "AND r.isolationRoom = true " +
           "AND NOT EXISTS (SELECT b FROM Bed b " +
           "               WHERE b.room = r " +
           "               AND b.status IN (com.healthcare.ecosystem.allocation.model.enums.BedStatus.RESERVED, " +
           "                                   com.healthcare.ecosystem.allocation.model.enums.BedStatus.OCCUPIED))")
    List<Room> findFreeIsolationRooms(@Param("hospitalId") Long hospitalId);
}