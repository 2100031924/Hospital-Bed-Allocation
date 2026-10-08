-- ============================================================================
-- Hospital Bed & Resource Allocation Engine - MySQL Schema
-- Usage: mysql -u root -p < schema.sql
-- ============================================================================

CREATE DATABASE IF NOT EXISTS hospital_allocation_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE hospital_allocation_db;

-- 1. Hospitals Table
CREATE TABLE IF NOT EXISTS hospitals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(500) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 2. Wards Table
CREATE TABLE IF NOT EXISTS wards (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    hospital_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    ward_type VARCHAR(50) NOT NULL,
    gender_policy VARCHAR(30) NOT NULL DEFAULT 'UNISEX',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_wards_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE,
    UNIQUE KEY uk_ward_name (hospital_id, name),
    INDEX idx_wards_type (ward_type),
    INDEX idx_wards_hospital (hospital_id)
) ENGINE=InnoDB;

-- 3. Rooms Table
CREATE TABLE IF NOT EXISTS rooms (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ward_id BIGINT NOT NULL,
    room_number VARCHAR(50) NOT NULL,
    is_isolation_room BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_rooms_ward FOREIGN KEY (ward_id) REFERENCES wards(id) ON DELETE CASCADE,
    UNIQUE KEY uk_ward_room (ward_id, room_number),
    INDEX idx_rooms_isolation (is_isolation_room)
) ENGINE=InnoDB;

-- 4. Beds Table (with optimistic lock version and status indexing)
CREATE TABLE IF NOT EXISTS beds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    bed_number VARCHAR(50) NOT NULL,
    bed_type VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_beds_room FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,
    UNIQUE KEY uk_room_bed (room_id, bed_number),
    INDEX idx_beds_status_type (status, bed_type),
    INDEX idx_beds_lookup (room_id, status)
) ENGINE=InnoDB;

-- 5. Admissions Table
CREATE TABLE IF NOT EXISTS admissions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id VARCHAR(100) NOT NULL,
    patient_gender VARCHAR(20) NOT NULL,
    required_ward_type VARCHAR(50) NOT NULL,
    required_bed_type VARCHAR(50) NOT NULL,
    priority VARCHAR(30) NOT NULL,
    isolation_required BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(30) NOT NULL,
    allocated_bed_id BIGINT NULL,
    reservation_expires_at TIMESTAMP NULL,
    admission_time TIMESTAMP NULL,
    discharge_time TIMESTAMP NULL,
    cancellation_reason VARCHAR(255) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_admissions_bed FOREIGN KEY (allocated_bed_id) REFERENCES beds(id) ON DELETE SET NULL,
    INDEX idx_admissions_patient (patient_id),
    INDEX idx_admissions_status (status),
    INDEX idx_admissions_reservation (status, reservation_expires_at)
) ENGINE=InnoDB;

-- 6. Waiting List Table
CREATE TABLE IF NOT EXISTS waiting_list (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    admission_id BIGINT NOT NULL UNIQUE,
    priority_score INT NOT NULL,
    enqueued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_waiting_admission FOREIGN KEY (admission_id) REFERENCES admissions(id) ON DELETE CASCADE,
    INDEX idx_waiting_priority (priority_score DESC, enqueued_at ASC)
) ENGINE=InnoDB;

-- 7. Audit & State History
CREATE TABLE IF NOT EXISTS bed_status_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bed_id BIGINT NOT NULL,
    previous_status VARCHAR(30) NOT NULL,
    new_status VARCHAR(30) NOT NULL,
    changed_by VARCHAR(100) NOT NULL DEFAULT 'SYSTEM',
    reason VARCHAR(255),
    logged_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bed_logs_bed FOREIGN KEY (bed_id) REFERENCES beds(id) ON DELETE CASCADE,
    INDEX idx_bed_logs_bed (bed_id, logged_at)
) ENGINE=InnoDB;

-- ============================================================================
-- Baseline seed data for immediate testing.
-- Idempotent: safe to re-run. Rows are located by their natural keys rather
-- than hard-coded surrogate ids, so this never collides with real data.
-- ============================================================================
INSERT IGNORE INTO hospitals (code, name, address)
VALUES ('HOSP-ALPHA', 'St. Jude Metropolitan', '742 Evergreen Terrace');

INSERT IGNORE INTO wards (hospital_id, name, ward_type, gender_policy)
SELECT h.id, 'North ICU', 'ICU', 'UNISEX'
FROM hospitals h
WHERE h.code = 'HOSP-ALPHA';

INSERT IGNORE INTO rooms (ward_id, room_number, is_isolation_room)
SELECT w.id, 'ICU-ROOM-1', FALSE
FROM wards w
JOIN hospitals h ON h.id = w.hospital_id
WHERE h.code = 'HOSP-ALPHA' AND w.name = 'North ICU';

INSERT IGNORE INTO beds (room_id, bed_number, bed_type, status, version)
SELECT r.id, 'BED-01', 'ICU', 'AVAILABLE', 0
FROM rooms r
JOIN wards w ON w.id = r.ward_id
JOIN hospitals h ON h.id = w.hospital_id
WHERE h.code = 'HOSP-ALPHA' AND r.room_number = 'ICU-ROOM-1';