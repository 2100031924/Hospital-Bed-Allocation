# Complete README Documentation

## Table of Contents

- Architecture / Flow Diagram
- Database Schema
- REST APIs
- DTOs and Validation
- Service-Layer Business Logic
- Global Exception Handling
- Transaction / Concurrency Implementation
- Unit Tests
- Integration Tests
- Swagger Documentation
- Postman Collection
- Final Walkthrough

---

## Architecture / Flow Diagram

### 1.1 High-Level Architecture

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT / API CONSUMER                                   │
│                    (Swagger UI, Postman, Frontend Application)                        │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           REST CONTROLLER LAYER                                       │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌────────────┐  │
│  │ Hospital     │ │ Ward         │ │ Room         │ │ Bed          │ │ Admission  │  │
│  │ Controller   │ │ Controller   │ │ Controller   │ │ Controller   │ │ Controller │  │
│  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └─────┬──────┘  │
└─────────┼────────────────┼────────────────┼────────────────┼───────────────┼─────────┘
          │                │                │                │               │
          ▼                ▼                ▼                ▼               ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         GLOBAL EXCEPTION HANDLER                                      │
│              (Converts exceptions → HTTP status + ApiResponse envelope)               │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           SERVICE LAYER (Transaction Boundary)                       │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────────┐   │
│  │ BedManagementService │  │ AdmissionService     │  │ WaitingListService       │   │
│  └──────────┬───────────┘  └──────────┬───────────┘  └───────────┬──────────────┘   │
└─────────────┼──────────────────────────┼──────────────────────────┼───────────────────┘
              │                          │                          │
              ▼                          ▼                          ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              DATABASE LAYER                                           │
│                    MySQL 8 / InnoDB  (H2 in MySQL mode for tests)                     │
└─────────────────────────────────────────────────────────────────────────────────────┘


### 1.2 Admission Allocation Flow (Automatic)

CLIENT: POST /api/v1/admissions/request
Body: { patientId, patientGender, requiredWardType, requiredBedType,
        priority, isolationRequired }   ← NO bedId!

        │
        ▼
AdmissionController.requestAdmission()
→ @Valid validates DTO (Bean Validation)

        │
        ▼
AdmissionServiceImpl.createAdmissionRequest()
@Transactional(isolation = READ_COMMITTED)
1. Save new Admission with status = PENDING
2. Call BedAllocationEngine.allocateBed(admission)

        │
        ▼
BedAllocationEngineImpl.allocateBed()
1. findEligibleCandidateBedIds(wardType, bedType, isolationRequired)
2. FOR EACH candidate bed ID:
   a. findByIdWithPessimisticLock(candidateId)  ← PESSIMISTIC_WRITE
   b. IF lock acquired: re-check isBedEligibleForAdmission
   c. IF lock conflict → skip candidate, try next
3. IF no eligible bed found → return Optional.empty()

        │
   ┌────┴────┐
   ▼         ▼
BED FOUND   NO BED FOUND
           waitingListService.enqueue(admission)
           admission.setStatus(WAITING_LIST)


### 1.3 Bed Eligibility Rules (7 Rules in isBedEligibleForAdmission)

RULE 1: Status Check
  bed.status must be AVAILABLE
  (BLOCKED, MAINTENANCE, RESERVED, OCCUPIED → reject)

RULE 2: Existing Reservation / Occupancy Check (Defence in Depth)
  No Admission with status IN (RESERVED, ADMITTED) already references this bed

RULE 3: Required Ward Type Match
  ward.wardType == admission.requiredWardType

RULE 4: Required Bed Type Match
  bed.bedType == admission.requiredBedType

RULE 5: Ward Gender Policy Check
  - MALE_ONLY ward → patient must be MALE
  - FEMALE_ONLY ward → patient must be FEMALE
  - UNISEX ward → all genders allowed

RULE 6: Isolation Compatibility Check
  - isolationRequired = true → room.isIsolationRoom must be true
  - isolationRequired = false → room.isIsolationRoom must be false

RULE 7: Room Gender Compatibility (Multi-bed non-isolation rooms)
  For each active bed in the same room:
    - Find its active admission
    - If activeAdmission.patientGender != newAdmission.patientGender → reject


### 1.4 Full Bed Lifecycle State Machine

BED STATES
AVAILABLE ──► RESERVED ──► OCCUPIED ──► MAINTENANCE
     ▲            │            │              │
     │            ▼            ▼              ▼
     │        AVAILABLE    BLOCKED       AVAILABLE
     │        (expiry)
     └────────────────────────────────────────┘
              MAINTENANCE → AVAILABLE
              BLOCKED → AVAILABLE

Admission → Bed State Mapping:

| Admission Action | Admission Status | Bed Status |
|---|---|---|
| POST /request (bed found) | PENDING → RESERVED | AVAILABLE → RESERVED |
| POST /request (no bed) | PENDING → WAITING_LIST | — |
| POST /{id}/confirm | RESERVED → ADMITTED | RESERVED → OCCUPIED |
| POST /{id}/discharge | ADMITTED → DISCHARGED | OCCUPIED → MAINTENANCE |
| POST /beds/{id}/maintenance/complete | — | MAINTENANCE → AVAILABLE |
| POST /{id}/cancel | Any active → CANCELLED | RESERVED/OCCUPIED → AVAILABLE |
| Scheduler (expiry) | RESERVED → EXPIRED | RESERVED → AVAILABLE |


### 1.5 Waiting List Evaluation Flow

TRIGGER: evaluateWaitingListForBed(releasedBed)
Called from:
  • completeMaintenance()
  • cancelAdmission()
  • processExpiredReservations() (scheduler)
  • Any other path that releases a bed

1. Lock the released bed: findByIdWithPessimisticLock(releasedBed.getId())
2. Verify bed.status == AVAILABLE
3. Query waiting list:
   SELECT w FROM WaitingListEntry w
   JOIN FETCH w.admission a
   WHERE a.status = 'WAITING_LIST'
   ORDER BY w.priorityScore DESC, w.enqueuedAt ASC
4. FOR EACH waiting entry:
   a. IF admission.status != WAITING_LIST → delete stale entry, continue
   b. IF !isBedEligibleForAdmission(bed, admission) → continue
   c. MATCH FOUND:
      - bed.setStatus(RESERVED)
      - admission.setStatus(RESERVED)
      - admission.setAllocatedBed(bed)
      - admission.setReservationExpiresAt(now + 15 min)
      - Delete waiting list entry
      - Save BedStatusLog
      - BREAK


### 1.6 Reservation Expiry Scheduler Flow

@Scheduled(cron = "${allocation.reservation.cleanup-cron:0 */1 * * * *}")
Runs every minute by default

ReservationExpiryScheduler.processExpiredReservations()
@Transactional
1. now = Instant.now()
2. expiredAdmissions = admissionRepository.findExpiredReservations(now)
3. FOR EACH expired admission:
   a. IF allocatedBed == null: set EXPIRED, save, continue
   b. Lock bed: findByIdWithPessimisticLock(bed.getId())
   c. IF bed.status == RESERVED:
      - bed.setStatus(AVAILABLE)
      - admission.setStatus(EXPIRED)
      - admission.setAllocatedBed(null)
      - Save BedStatusLog
      - waitingListService.evaluateWaitingListForBed(bed)


### 1.7 Entity Relationship Diagram

Hospital ──1:N──► Ward ──1:N──► Room ──1:N──► Bed
                                               │
                                               │ 1:N
                                               ▼
                                          Admission
                                               │
                                               │ 1:1
                                               ▼
                                        WaitingListEntry

Bed ──1:N──► BedStatusLog


---

## Database Schema

### 2.1 Schema DDL (MySQL 8)

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
    CONSTRAINT fk_rooms_ward FOREIGN KEY (ward_id) REFERENCES wards(id) ON DELETE CASCADE,
    UNIQUE KEY uk_ward_room (ward_id, room_number),
    INDEX idx_rooms_isolation (is_isolation_room)
) ENGINE=InnoDB;

-- 4. Beds Table
CREATE TABLE IF NOT EXISTS beds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    bed_number VARCHAR(50) NOT NULL,
    bed_type VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE',
    version BIGINT NOT NULL DEFAULT 0,
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


### 2.2 Seed Data

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


### 2.3 Table Relationships

| Parent | Child | Relationship | Foreign Key | On Delete |
|---|---|---|---|---|
| hospitals | wards | 1:N | hospital_id | CASCADE |
| wards | rooms | 1:N | ward_id | CASCADE |
| rooms | beds | 1:N | room_id | CASCADE |
| beds | admissions | 1:N | allocated_bed_id | SET NULL |
| admissions | waiting_list | 1:1 | admission_id | CASCADE |
| beds | bed_status_logs | 1:N | bed_id | CASCADE |


### 2.4 Key Constraints

| Constraint | Type | Table | Columns |
|---|---|---|---|
| Unique hospital code | UNIQUE | hospitals | code |
| Unique ward name per hospital | UNIQUE | wards | (hospital_id, name) |
| Unique room number per ward | UNIQUE | rooms | (ward_id, room_number) |
| Unique bed number per room | UNIQUE | beds | (room_id, bed_number) |
| One waiting entry per admission | UNIQUE | waiting_list | admission_id |
| Optimistic lock (beds) | VERSION | beds | version |
| Optimistic lock (admissions) | VERSION | admissions | version |


### 2.5 Enum Values

| Enum | Values |
|---|---|
| BedStatus | AVAILABLE, RESERVED, OCCUPIED, MAINTENANCE, BLOCKED |
| AdmissionStatus | PENDING, WAITING_LIST, RESERVED, ADMITTED, DISCHARGED, EXPIRED, CANCELLED |
| AdmissionPriority | EMERGENCY (300), URGENT (200), NORMAL (100) |
| WardType | GENERAL, ICU, PEDIATRIC, MATERNITY, SURGICAL |
| BedType | STANDARD, ICU, VENTILATOR, OXYGEN_SUPPORTED, BARIATRIC |
| GenderPolicy | MALE_ONLY, FEMALE_ONLY, UNISEX |
| PatientGender | MALE, FEMALE, OTHER |


---

## REST APIs

### 3.1 Base URL & Conventions

Base URL: http://localhost:8080

Headers:
  Content-Type: application/json
  Accept: application/json

Enum values must use exact uppercase names (e.g., ICU, URGENT, FEMALE).


### 3.2 Response Envelope

Success:
{
  "success": true,
  "message": "Operation successful",
  "data": { }
}

Error:
{
  "success": false,
  "message": "Bed not found: 999",
  "data": null
}


### 3.3 HTTP Status Codes

| HTTP Status | Meaning in this Implementation |
|---|---|
| 200 OK | Successful retrieval or operation |
| 201 Created | Resource created or admission request processed |
| 400 Bad Request | Validation error, malformed JSON, or invalid enum value |
| 404 Not Found | Requested resource does not exist |
| 409 Conflict | Invalid state transition, duplicate/constraint conflict, or lock conflict |
| 422 Unprocessable Entity | Requested bed is unavailable or ineligible for manual reservation |
| 500 Internal Server Error | Unexpected exception |


### 3.4 Hospital APIs

| Method | Path | Purpose |
|---|---|---|
| POST | /api/v1/hospitals | Create a hospital |
| GET | /api/v1/hospitals | List hospitals |
| GET | /api/v1/hospitals/{hospitalId} | Get a hospital |

Create Hospital Body:
{
  "code": "HOSP-01",
  "name": "City General Hospital",
  "address": "1 Main Street, Tirupati"
}

Response (201 Created):
{
  "success": true,
  "message": "Hospital created successfully",
  "data": {
    "id": 1,
    "code": "HOSP-01",
    "name": "City General Hospital",
    "address": "1 Main Street, Tirupati"
  }
}


### 3.5 Ward APIs

| Method | Path | Purpose |
|---|---|---|
| POST | /api/v1/wards | Create a ward |
| GET | /api/v1/wards/{wardId} | Get ward details |
| GET | /api/v1/wards/{wardId}/beds | List beds in a ward |

Create Ward Body:
{
  "hospitalId": 1,
  "name": "General Ward",
  "wardType": "GENERAL",
  "genderPolicy": "UNISEX"
}


### 3.6 Room APIs

| Method | Path | Purpose |
|---|---|---|
| POST | /api/v1/rooms | Create a room |
| GET | /api/v1/rooms/{roomId} | Get room details |
| GET | /api/v1/rooms/ward/{wardId} | List rooms in a ward |

Create Room Body:
{
  "wardId": 1,
  "roomNumber": "G-101",
  "isolationRoom": false
}


### 3.7 Bed APIs

| Method | Path | Purpose |
|---|---|---|
| POST | /api/v1/beds | Create a bed (initial status: AVAILABLE) |
| GET | /api/v1/beds/{bedId} | Get bed details |
| GET | /api/v1/beds/available | Search available beds |
| POST | /api/v1/beds/{bedId}/maintenance/complete | Complete maintenance and evaluate waiting list |
| POST | /api/v1/beds/{bedId}/block | Put a bed on administrative hold |
| GET | /api/v1/beds/stats | Get counts grouped by bed status |

Create Bed Body:
{
  "roomId": 1,
  "bedNumber": "G-101-B1",
  "bedType": "STANDARD"
}


### 3.8 Admission APIs

| Method | Path | Purpose |
|---|---|---|
| POST | /api/v1/admissions/request | Create admission request + automatic allocation |
| GET | /api/v1/admissions/{admissionId} | Get admission details |
| GET | /api/v1/admissions/patient/{patientId} | List active admissions for a patient |
| POST | /api/v1/admissions/{admissionId}/reserve?bedId={bedId} | Manually reserve a specific bed |
| POST | /api/v1/admissions/{admissionId}/confirm | Confirm arrival and occupy the bed |
| POST | /api/v1/admissions/{admissionId}/discharge | Discharge patient, bed → MAINTENANCE |
| POST | /api/v1/admissions/{admissionId}/cancel?reason={reason} | Cancel an admission |


### 3.9 API Flow Sequence

1. POST /api/v1/hospitals                          → Create hospital
2. POST /api/v1/wards                              → Create ward
3. POST /api/v1/rooms                              → Create room
4. POST /api/v1/beds                               → Create bed (AVAILABLE)
5. GET  /api/v1/beds/available                     → Search available beds
6. POST /api/v1/admissions/request                 → Automatic allocation
7. POST /api/v1/admissions/{id}/confirm            → Confirm admission
8. POST /api/v1/admissions/{id}/discharge          → Discharge patient
9. POST /api/v1/beds/{id}/maintenance/complete     → Complete maintenance
10. GET /api/v1/admissions/{id}                    → Check waiting admission


---

## DTOs and Validation

### 4.1 Request DTOs

CreateHospitalRequest:
public record CreateHospitalRequest(
    @NotBlank String code,
    @NotBlank String name,
    @NotBlank String address
) {}

CreateWardRequest:
public record CreateWardRequest(
    @NotNull Long hospitalId,
    @NotBlank String name,
    @NotNull WardType wardType,
    @NotNull GenderPolicy genderPolicy
) {}

CreateRoomRequest:
public record CreateRoomRequest(
    @NotNull Long wardId,
    @NotBlank String roomNumber,
    boolean isolationRoom
) {}

CreateBedRequest:
public record CreateBedRequest(
    @NotNull Long roomId,
    @NotBlank String bedNumber,
    @NotNull BedType bedType
) {}

AdmissionCreationRequest:
public record AdmissionCreationRequest(
    @NotBlank String patientId,
    @NotNull PatientGender patientGender,
    @NotNull WardType requiredWardType,
    @NotNull BedType requiredBedType,
    @NotNull AdmissionPriority priority,
    boolean isolationRequired
) {}


### 4.2 Response DTOs

ApiResponse<T>:
public record ApiResponse<T>(boolean success, String message, T data) {
    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }
    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }
}

HospitalResponse, WardResponse, RoomResponse, BedResponse, AdmissionResponse
— each is a plain Java record with the fields listed in the schema.


### 4.3 Validation Examples

Invalid Request (blank patientId) → HTTP 400 with:
{
  "success": false,
  "message": "Validation Failed",
  "data": { "patientId": "Patient ID is required" }
}

Invalid Enum Value → HTTP 400 with:
"Malformed or unreadable request body. Check enum names and field types."


---

## Service-Layer Business Logic

### 5.1 BedAllocationEngineImpl — The 7 Eligibility Rules

@Override
public boolean isBedEligibleForAdmission(Bed bed, Admission admission) {
    if (bed == null || admission == null) return false;

    // RULE 1: Status check
    if (!BedStatusTransition.isAllowedToReceivePatient(bed.getStatus())) return false;

    Room room = bed.getRoom();
    if (room == null) return false;
    Ward ward = room.getWard();
    if (ward == null) return false;

    // RULE 2: Existing reservation / occupancy check
    boolean bedAlreadyHeld = admissionRepository
        .findByAllocatedBedIdAndStatusIn(bed.getId(), ACTIVE_ADMISSION_STATUSES)
        .isPresent();
    if (bedAlreadyHeld) return false;

    // RULE 3: Required ward check
    if (ward.getWardType() != admission.getRequiredWardType()) return false;

    // RULE 4: Required bed type check
    if (bed.getBedType() != admission.getRequiredBedType()) return false;

    // RULE 5: Ward Gender Policy Check
    if (ward.getGenderPolicy() == GenderPolicy.MALE_ONLY
            && admission.getPatientGender() != PatientGender.MALE) return false;
    if (ward.getGenderPolicy() == GenderPolicy.FEMALE_ONLY
            && admission.getPatientGender() != PatientGender.FEMALE) return false;

    // RULE 6: Isolation Compatibility Check
    if (admission.isIsolationRequired()) {
        if (!room.isIsolationRoom()) return false;
        if (!bedRepository.findActiveBedsInRoom(room.getId()).isEmpty()) return false;
    } else {
        if (room.isIsolationRoom()) return false;
    }

    // RULE 7: Room Gender Compatibility
    for (Bed activeBed : bedRepository.findActiveBedsInRoom(room.getId())) {
        Optional<Admission> activeAdmissionOpt = admissionRepository
            .findByAllocatedBedIdAndStatusIn(activeBed.getId(), ACTIVE_ADMISSION_STATUSES);
        if (activeAdmissionOpt.isPresent()) {
            Admission activeAdmission = activeAdmissionOpt.get();
            if (activeAdmission.getPatientGender() != admission.getPatientGender()) {
                return false;
            }
        }
    }

    return true;
}


### 5.2 BedAllocationEngineImpl — allocateBed()

@Override
@Transactional
public Optional<Bed> allocateBed(Admission admission) {
    List<Long> candidateBedIds = bedRepository.findEligibleCandidateBedIds(
        admission.getRequiredWardType(),
        admission.getRequiredBedType(),
        admission.isIsolationRequired()
    );

    for (Long candidateId : candidateBedIds) {
        Optional<Bed> lockedBedOpt;
        try {
            lockedBedOpt = bedRepository.findByIdWithPessimisticLock(candidateId);
        } catch (ConcurrencyFailureException | PessimisticLockException | LockTimeoutException ex) {
            continue;
        }
        if (lockedBedOpt.isEmpty()) continue;

        Bed lockedBed = lockedBedOpt.get();
        if (isBedEligibleForAdmission(lockedBed, admission)) {
            return Optional.of(lockedBed);
        }
    }
    return Optional.empty();
}


### 5.3 AdmissionServiceImpl — createAdmissionRequest()

@Transactional(isolation = Isolation.READ_COMMITTED)
public AdmissionResponse createAdmissionRequest(AdmissionCreationRequest request) {
    Admission admission = new Admission(...);
    admission = admissionRepository.save(admission);

    Optional<Bed> allocatedBedOpt = bedAllocationEngine.allocateBed(admission);

    if (allocatedBedOpt.isPresent()) {
        Bed bed = allocatedBedOpt.get();
        bed.setStatus(BedStatus.RESERVED);
        bedRepository.save(bed);
        admission.setAllocatedBed(bed);
        admission.setStatus(AdmissionStatus.RESERVED);
        admission.setReservationExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        admission = admissionRepository.save(admission);
        bedStatusLogRepository.save(new BedStatusLog(...));
    } else {
        waitingListService.enqueue(admission);
    }
    return mapToResponse(admission);
}


### 5.4 – 5.11 (confirmAdmission, dischargeAdmission, cancelAdmission,
             enqueue, evaluateWaitingListForBed, completeMaintenance,
             blockBed, ReservationExpiryScheduler)

Each of these follows the same pattern: lock the row, validate state,
update statuses, persist, and record an audit log. Full code shown in
the original file for each method.


### 5.12 Business Logic Summary Table

| Operation | Admission Status Change | Bed Status Change | Waiting List |
|---|---|---|---|
| Create request (bed found) | PENDING → RESERVED | AVAILABLE → RESERVED | — |
| Create request (no bed) | PENDING → WAITING_LIST | — | Enqueue with priority score |
| Confirm | RESERVED → ADMITTED | RESERVED → OCCUPIED | — |
| Discharge | ADMITTED → DISCHARGED | OCCUPIED → MAINTENANCE | — |
| Complete maintenance | — | MAINTENANCE → AVAILABLE | Evaluate + assign if possible |
| Cancel | Any active → CANCELLED | RESERVED/OCCUPIED → AVAILABLE | Evaluate + assign if possible |
| Expiry scheduler | RESERVED → EXPIRED | RESERVED → AVAILABLE | Evaluate + assign if possible |
| Block bed | — | AVAILABLE/RESERVED/MAINTENANCE → BLOCKED | — |
| Manual reserve | PENDING/WAITING_LIST → RESERVED | AVAILABLE → RESERVED | Remove entry |


---

## Global Exception Handling

### 6.1 Custom Exceptions

| Exception | HTTP Status | Trigger |
|---|---|---|
| ResourceNotFoundException | 404 Not Found | Unknown hospital, ward, room, bed, or admission ID |
| InvalidStateTransitionException | 409 Conflict | Invalid admission/bed state transition |
| BedUnavailableException | 422 Unprocessable Entity | Manually selected bed is not eligible |
| BedAllocationConflictException | 409 Conflict | Pessimistic/optimistic locking conflict |


### 6.2 GlobalExceptionHandler

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(ex.getMessage()));
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidState(InvalidStateTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(ex.getMessage()));
    }

    @ExceptionHandler(BedUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleBedUnavailable(BedUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.fail(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationErrors(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(false, "Validation Failed", errors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("Internal server error: " + ex.getMessage()));
    }
}


### 6.3 Error Response Examples

404 Not Found:
{ "success": false, "message": "Bed not found: 999", "data": null }

400 Validation Error:
{ "success": false, "message": "Validation Failed",
  "data": { "patientId": "Patient ID is required" } }

409 Conflict:
{ "success": false,
  "message": "Admission must be in RESERVED state to confirm. Current: WAITING_LIST",
  "data": null }

422 Unprocessable Entity:
{ "success": false,
  "message": "Bed ID 12 is not eligible for this admission request.",
  "data": null }


---

## Transaction / Concurrency Implementation

### 7.1 Locking Strategy Overview

| Mechanism | Where Used | Purpose |
|---|---|---|
| PESSIMISTIC_WRITE | BedRepository.findByIdWithPessimisticLock | Prevents concurrent bed allocation |
| PESSIMISTIC_WRITE | AdmissionRepository.findByIdWithPessimisticLock | Prevents concurrent admission state changes |
| Lock timeout hint | jakarta.persistence.lock.timeout = 0 | Fail fast on lock contention |
| @Version | Bed and Admission entities | Optimistic locking as backup |
| READ_COMMITTED | createAdmissionRequest, reserveBedManually | Transaction isolation |
| Default isolation | Other service methods | READ_COMMITTED (MySQL default) |


### 7.2 Pessimistic Lock Repository Queries

BedRepository:

@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({
    @QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"),
    @QueryHint(name = "jakarta.persistence.cache.retrieveMode",
               value = "jakarta.persistence.cache.CacheRetrieveMode.BYPASS")
})
@Query("SELECT b FROM Bed b WHERE b.id = :id")
Optional<Bed> findByIdWithPessimisticLock(@Param("id") Long id);

AdmissionRepository:

@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
@Query("SELECT a FROM Admission a WHERE a.id = :id")
Optional<Admission> findByIdWithPessimisticLock(@Param("id") Long id);


### 7.3 Optimistic Locking with @Version

Bed entity:      @Version @Column(nullable = false) private Long version = 0L;
Admission entity: @Version @Column(nullable = false) private Long version = 0L;


### 7.4 Transaction Isolation

createAdmissionRequest:  @Transactional(isolation = Isolation.READ_COMMITTED)
reserveBedManually:      @Transactional(isolation = Isolation.READ_COMMITTED)


### 7.5 Concurrency Flow — Automatic Allocation

Thread A                          Thread B
────────                          ────────
allocateBed(admissionA)           allocateBed(admissionB)
  │                                 │
  ├─ findEligibleCandidateBedIds    ├─ findEligibleCandidateBedIds
  │  → [1]                          │  → [1]
  ├─ findByIdWithPessimisticLock(1) │
  │  → LOCK ACQUIRED                │
  │                                 ├─ findByIdWithPessimisticLock(1)
  │                                 │  → LOCK TIMEOUT (0 ms)
  │                                 │  → skip candidate
  │                                 │  → return Optional.empty()
  ├─ isBedEligibleForAdmission      │
  │  → true                         │
  ├─ bed.setStatus(RESERVED)        │
  ├─ admission.setStatus(RESERVED)  │
  ├─ save + commit                  │
  │  → LOCK RELEASED                │
  │                                 ├─ enqueue(admissionB)
  │                                 │  → WAITING_LIST


### 7.6 Concurrency Flow — Manual Reservation Race

Operator A                        Operator B
──────────                        ──────────
reserveBedManually(42, 12)        reserveBedManually(43, 12)
  ├─ lockAdmission(42)              ├─ lockAdmission(43)
  │  → LOCK ACQUIRED                │  → LOCK ACQUIRED (different admission)
  ├─ findByIdWithPessimisticLock(12)│
  │  → LOCK ACQUIRED                ├─ findByIdWithPessimisticLock(12)
  │                                 │  → LOCK TIMEOUT
  │                                 │  → BedAllocationConflictException
  ├─ isBedAlreadyHeld(bed) → false  │
  ├─ isBedEligibleForAdmission → true
  ├─ bed.setStatus(RESERVED)        │
  ├─ admission.setStatus(RESERVED)  │
  ├─ save + commit                  │
  │  → LOCK RELEASED                │


### 7.7 Configuration

application.yml:

spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      idle-timeout: 300000
      connection-timeout: 20000
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  task:
    scheduling:
      pool:
        size: 5

allocation:
  reservation:
    timeout-minutes: 15
    cleanup-cron: "0 */1 * * * *"   # every minute


### 7.8 Known Concurrency Limitations

| # | Limitation | Impact |
|---|---|---|
| 1 | Room-level rules not protected by room lock | Concurrent requests for different beds in same room could both pass isolation/gender checks |
| 2 | Scheduled expiry query does not lock admission rows | Potential race between expiry and manual confirmation |
| 3 | Waiting-list evaluator does not lock each candidate admission | Could assign same bed twice under extreme concurrency |
| 4 | H2 tests do not prove MySQL locking behavior | Production concurrency guarantee should be tested against MySQL |
| 5 | Cancellation can bypass discharge-cleaning workflow | OCCUPIED bed can go directly to AVAILABLE |
| 6 | Blocking a reserved bed can leave active reservation attached | Admission can remain RESERVED on BLOCKED bed |
| 7 | BedStatusTransition enum is not the single enforcement point | Service methods set statuses directly |


---

## Unit Tests

### 8.1 Test Class: BedAllocationEngineTest

Location: src/test/java/com/healthcare/ecosystem/allocation/service/BedAllocationEngineTest.java
Framework: JUnit 5 + Mockito
Purpose: Tests the 7 eligibility rules in isolation using mocked repositories.

Test Cases:

| # | Test Name | Rule Tested | Expected |
|---|---|---|---|
| 1 | shouldRejectWhenGenderMismatchesWardPolicy | Rule 5 | Male patient rejected from FEMALE_ONLY ward |
| 2 | shouldRejectNonIsolationPatientFromIsolationRoom | Rule 6 | Non-isolation patient rejected |
| 3 | shouldApproveIsolationPatientInAvailableIsolationRoom | Rule 6 | Approved for clean isolation room |
| 4 | shouldRejectIsolationPatientWhenRoomOccupied | Rule 6 | Rejected when room occupied |
| 5 | shouldRejectCoedInMultiBedRoom | Rule 7 | Rejected when male in same room |
| 6 | shouldApproveSameGenderCoPlacement | Rule 7 | Same-gender co-placement approved |
| 7 | shouldRejectNonAvailableBed | Rule 1 | MAINTENANCE bed rejected |
| 8 | shouldRejectMismatchedWardOrBedType | Rules 3 & 4 | Mismatch rejected |


### 8.2 Unit Test Coverage Summary

| Component | Tests | Coverage |
|---|---|---|
| BedAllocationEngineImpl | 8 | All 7 eligibility rules + null safety |
| AdmissionServiceImpl | — | Covered by integration tests |
| BedManagementServiceImpl | — | Covered by integration tests |
| WaitingListServiceImpl | — | Covered by integration tests |
| ReservationExpiryScheduler | — | Covered by integration tests |


---

## Integration Tests

### 9.1 Test Classes Overview

| Test Class | Type | Purpose |
|---|---|---|
| BedAllocationApplicationTests | Integration | Context loads |
| BedAllocationConcurrencyTest | Concurrency | 10 threads race for 1 bed |
| ConcurrentManualReservationTest | Concurrency | 2 operators race for same bed |
| ReservationExpiryIntegrationTest | Integration | Expiry scheduler behavior |
| BedAvailabilitySearchTest | Integration | Search filters |
| AdmissionLifecycleIntegrationTest | Integration | Full lifecycle E2E |
| HospitalRoomManagementTest | Integration | Hospital/Room API tests |


### 9.2 BedAllocationConcurrencyTest

Purpose: Proves the double-allocation race is closed.

Expected Results:
- 1 admission RESERVED
- 9 admissions WAITING_LIST
- 1 bed RESERVED
- 9 waiting list entries
- 0 double-bookings


### 9.3 ConcurrentManualReservationTest

Expected Results:
- 1 manual reservation succeeds
- 1 manual reservation fails with conflict
- 1 admission RESERVED
- 1 bed RESERVED
- 1 waiting list entry remains


### 9.4 ReservationExpiryIntegrationTest

| # | Test Name | Scenario | Expected |
|---|---|---|---|
| 1 | expiredReservationIsReclaimed | Backdate expiry, run scheduler | Admission EXPIRED, bed AVAILABLE |
| 2 | expiryIsAudited | Backdate expiry | Audit log actor = EXPIRY_SCHEDULER |
| 3 | liveReservationIsLeftAlone | Don't backdate | Still RESERVED |
| 4 | admittedPatientIsNotExpired | Confirm then sweep | Still ADMITTED |
| 5 | expiredBedIsHandedToWaitingPatient | A expires, B waiting | B gets bed RESERVED |
| 6 | sweepWithNoExpiredReservationsIsNoop | None | No changes |


### 9.5 BedAvailabilitySearchTest

| # | Test Name | Filters | Expected |
|---|---|---|---|
| 1 | noFiltersReturnsAll | None | 4 beds |
| 2 | filterByWardType | ICU | 2 beds |
| 3 | filterByBedType | STANDARD | 1 bed |
| 4 | filterByHospital | hospitalA | 4 beds |
| 5 | filterByIsolation | isolation=true | 2 beds |
| 6 | genderFilterExcludesFemaleOnlyWard | MALE | 3 beds |
| 7 | genderFilterIncludesFemaleOnlyWard | FEMALE | 4 beds |
| 8 | otherGenderOnlySeesUnisexWards | OTHER | 3 beds |
| 9 | combinedFilters | ICU + ICU + isolation + MALE | 2 beds |
| 10 | contradictoryFiltersReturnEmpty | Multiple contradictions | 0 beds |
| 11 | isolationAndGenderCombined | isolation + ICU + MALE | 2 beds |
| 12 | onlyAvailableBedsReturned | One MAINTENANCE | 3 beds |


### 9.6 AdmissionLifecycleIntegrationTest

| # | Test Name | Scenario |
|---|---|---|
| 1 | fullLifecycle | REQUEST → RESERVED → ADMITTED → DISCHARGED → AVAILABLE |
| 2 | waitingListIsServedOnMaintenanceCompletion | Second admission gets bed after maintenance |
| 3 | dischargeFromWrongStateIsRejected | Discharge from RESERVED throws |
| 4 | cancelReleasesBed | Cancel releases bed |
| 5 | manualReservationRejectsIneligibleBed | Wrong bed type throws |
| 6 | auditTrailIsWritten | All 4 bed states appear in logs |
| 7 | isolationPolicyIsEnforcedEndToEnd | Isolation enforced |


### 9.7 HospitalRoomManagementTest

| # | Test Name | Purpose |
|---|---|---|
| 1 | createFullHierarchy | Create hospital → ward → room → bed |
| 2 | duplicateHospitalCodeRejected | Duplicate code throws |
| 3 | duplicateRoomNumberScopedToWard | Same room number in different ward allowed |
| 4 | unknownReferencesThrow | Unknown IDs throw not found |
| 5 | createHospitalEndpoint | POST /hospitals returns 201 |
| 6 | createHospitalValidation | Blank code returns 400 |
| 7 | roomEndpoints | POST /rooms + GET /rooms |
| 8 | wardCreationAgainstApiCreatedHospital | Create ward for API-created hospital |
| 9 | listHospitals | GET /hospitals returns all |
| 10 | availabilityEndpointAcceptsNewFilters | patientGender filter works |


### 9.8 Test Configuration

src/test/resources/application-test.yml:

spring:
  datasource:
    url: jdbc:h2:mem:hospital_test_db;DB_CLOSE_DELAY=-1;MODE=MySQL
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: false
    open-in-view: false
  task:
    scheduling:
      pool:
        size: 2

allocation:
  reservation:
    timeout-minutes: 15
    cleanup-cron: "0 0 0 1 1 *"


### 9.9 Running Tests

mvn clean test
mvn -Dtest=BedAllocationConcurrencyTest test
mvn -Dtest=ConcurrentManualReservationTest test
mvn -Dtest=ReservationExpiryIntegrationTest test
mvn -Dtest=AdmissionLifecycleIntegrationTest test
mvn -Dtest=BedAvailabilitySearchTest test

Expected: 47 tests across 8 classes, all passing.


---

## Swagger Documentation

### 10.1 Access

| Resource | URL |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |


### 10.2 Tagged API Groups

| Tag | Controller | Description |
|---|---|---|
| Hospital Management | HospitalController | Root of the inventory hierarchy |
| Ward Management | WardController | Managing hospital wards |
| Room Management | RoomController | Rooms within a ward |
| Bed Management | BedController | Beds inventory, maintenance, search |
| Admission & Allocation Engine | AdmissionController | Admission orchestration |


### 10.3 Annotated Endpoints

Each endpoint is annotated with @Operation(summary = "...") for Swagger UI display.

Example from BedController:

@PostMapping
@Operation(summary = "Add a new bed to a room")
public ResponseEntity<ApiResponse<BedResponse>> createBed(
        @Valid @RequestBody CreateBedRequest request) { ... }


### 10.4 OpenAPI Configuration

pom.xml dependency:
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.3.0</version>
</dependency>

application.yml:
springdoc:
  swagger-ui:
    path: /swagger-ui.html


### 10.5 Using Swagger UI

1. Start the application: mvn spring-boot:run
2. Open http://localhost:8080/swagger-ui.html
3. Expand any endpoint group
4. Click "Try it out" to execute requests
5. View request/response schemas and example values


---

## Postman Collection

### 11.1 Collection Overview

Hospital Bed Allocation Engine
├── 01 - Hospital
│   ├── Create Hospital
│   ├── List Hospitals
│   └── Get Hospital by ID
├── 02 - Ward
│   ├── Create Ward
│   ├── Get Ward by ID
│   └── List Beds in Ward
├── 03 - Room
│   ├── Create Room
│   ├── Get Room by ID
│   └── List Rooms in Ward
├── 04 - Bed
│   ├── Create Bed
│   ├── Get Bed by ID
│   ├── Search Available Beds
│   ├── Block Bed
│   ├── Complete Maintenance
│   └── Get Bed Statistics
├── 05 - Admission
│   ├── Request Admission (Automatic)
│   ├── Get Admission by ID
│   ├── Get Admissions by Patient
│   ├── Manual Reserve Bed
│   ├── Confirm Admission
│   ├── Discharge Admission
│   └── Cancel Admission
└── 06 - Concurrency Demo
    └── (Newman script for concurrent requests)


### 11.2 Environment Variables

{
  "base_url": "http://localhost:8080",
  "hospital_id": "1",
  "ward_id": "1",
  "room_id": "1",
  "bed_id": "1",
  "admission_id": "101"
}


### 11.3 Sample Postman Requests

Create Hospital:
POST {{base_url}}/api/v1/hospitals
{
  "code": "HOSP-01",
  "name": "City General Hospital",
  "address": "1 Main Street, Tirupati"
}

Create Ward:
POST {{base_url}}/api/v1/wards
{
  "hospitalId": {{hospital_id}},
  "name": "General Ward",
  "wardType": "GENERAL",
  "genderPolicy": "UNISEX"
}

Create Room:
POST {{base_url}}/api/v1/rooms
{
  "wardId": {{ward_id}},
  "roomNumber": "G-101",
  "isolationRoom": false
}

Create Bed:
POST {{base_url}}/api/v1/beds
{
  "roomId": {{room_id}},
  "bedNumber": "G-101-B1",
  "bedType": "STANDARD"
}

Search Available Beds:
GET {{base_url}}/api/v1/beds/available?hospitalId=1&wardId=1&bedType=STANDARD&isolationRequired=false&patientGender=MALE

Request Admission (Automatic):
POST {{base_url}}/api/v1/admissions/request
{
  "patientId": "PAT-1001",
  "patientGender": "MALE",
  "requiredWardType": "GENERAL",
  "requiredBedType": "STANDARD",
  "priority": "NORMAL",
  "isolationRequired": false
}

Confirm Admission:
POST {{base_url}}/api/v1/admissions/{{admission_id}}/confirm

Discharge Admission:
POST {{base_url}}/api/v1/admissions/{{admission_id}}/discharge

Complete Maintenance:
POST {{base_url}}/api/v1/beds/{{bed_id}}/maintenance/complete

Cancel Admission:
POST {{base_url}}/api/v1/admissions/{{admission_id}}/cancel?reason=Patient%20left


### 11.4 Postman Test Scripts

Test: Admission Created Successfully:
pm.test("Admission processed", function () {
    pm.response.to.have.status(201);
    var jsonData = pm.response.json();
    pm.expect(jsonData.success).to.be.true;
    pm.expect(jsonData.data.status).to.be.oneOf(["RESERVED", "WAITING_LIST"]);
    if (jsonData.data.status === "RESERVED") {
        pm.expect(jsonData.data.allocatedBedId).to.not.be.null;
        pm.environment.set("admission_id", jsonData.data.admissionId);
    } else {
        pm.expect(jsonData.data.allocatedBedId).to.be.null;
    }
});

Test: Bed Status After Confirm:
pm.test("Bed is OCCUPIED", function () {
    pm.response.to.have.status(200);
    var jsonData = pm.response.json();
    pm.expect(jsonData.data.status).to.eql("ADMITTED");
});


---

## Final Walkthrough

### 12.1 End-to-End Demonstration Script

Step 1: Start the Application
mysql -u root -p < schema.sql
mvn spring-boot:run

Step 2: Create a Hospital
curl -i -X POST "http://localhost:8080/api/v1/hospitals" \
  -H "Content-Type: application/json" \
  -d '{"code": "HOSP-01", "name": "City General Hospital", "address": "1 Main Street, Tirupati"}'

Step 3: Create a Ward
curl -i -X POST "http://localhost:8080/api/v1/wards" \
  -H "Content-Type: application/json" \
  -d '{"hospitalId": 1, "name": "General Ward", "wardType": "GENERAL", "genderPolicy": "UNISEX"}'

Step 4: Create a Room
curl -i -X POST "http://localhost:8080/api/v1/rooms" \
  -H "Content-Type: application/json" \
  -d '{"wardId": 1, "roomNumber": "G-101", "isolationRoom": false}'

Step 5: Create a Bed
curl -i -X POST "http://localhost:8080/api/v1/beds" \
  -H "Content-Type: application/json" \
  -d '{"roomId": 1, "bedNumber": "G-101-B1", "bedType": "STANDARD"}'

Step 6: Search Available Beds
curl -i "http://localhost:8080/api/v1/beds/available?hospitalId=1&wardId=1&bedType=STANDARD&isolationRequired=false&patientGender=MALE"

Step 7: Request Admission (Automatic Allocation)
curl -i -X POST "http://localhost:8080/api/v1/admissions/request" \
  -H "Content-Type: application/json" \
  -d '{"patientId": "PAT-1001", "patientGender": "MALE", "requiredWardType": "GENERAL",
       "requiredBedType": "STANDARD", "priority": "NORMAL", "isolationRequired": false}'
→ Response: status = RESERVED, allocatedBedId = 1

Step 8: Submit Second Admission (Same Requirements)
curl -i -X POST "http://localhost:8080/api/v1/admissions/request" \
  -H "Content-Type: application/json" \
  -d '{"patientId": "PAT-1002", "patientGender": "MALE", "requiredWardType": "GENERAL",
       "requiredBedType": "STANDARD", "priority": "URGENT", "isolationRequired": false}'
→ Response: status = WAITING_LIST, allocatedBedId = null

Step 9: Confirm First Admission
curl -i -X POST "http://localhost:8080/api/v1/admissions/101/confirm"
→ Admission = ADMITTED, Bed = OCCUPIED

Step 10: Discharge First Admission
curl -i -X POST "http://localhost:8080/api/v1/admissions/101/discharge"
→ Admission = DISCHARGED, Bed = MAINTENANCE

Step 11: Complete Maintenance
curl -i -X POST "http://localhost:8080/api/v1/beds/1/maintenance/complete"
→ Bed status = RESERVED (auto-assigned to PAT-1002)

Step 12: Verify Waiting Patient Now Has the Bed
curl -i "http://localhost:8080/api/v1/admissions/102"
→ status = RESERVED, allocatedBedId = 1

Step 13: Check Bed Statistics
curl -i "http://localhost:8080/api/v1/beds/stats"
→ { "AVAILABLE": 0, "RESERVED": 1, "OCCUPIED": 0, "MAINTENANCE": 0, "BLOCKED": 0 }

Step 14: Verify Audit Trail
mysql -u root -p hospital_allocation_db -e "
  SELECT previous_status, new_status, changed_by, reason, logged_at
  FROM bed_status_logs WHERE bed_id = 1 ORDER BY logged_at ASC;"

Step 15: Run Concurrency Test
mvn -Dtest=BedAllocationConcurrencyTest test
→ 10 concurrent requests for 1 bed → 1 RESERVED, 9 WAITING_LIST


### 12.2 Demonstration Summary

| Step | Action | Admission Status | Bed Status |
|---|---|---|---|
| 1 | Create hospital | — | — |
| 2 | Create ward | — | — |
| 3 | Create room | — | — |
| 4 | Create bed | — | AVAILABLE |
| 5 | Search beds | — | AVAILABLE |
| 6 | Request admission A | RESERVED | RESERVED |
| 7 | Request admission B | WAITING_LIST | RESERVED |
| 8 | Confirm admission A | ADMITTED | OCCUPIED |
| 9 | Discharge admission A | DISCHARGED | MAINTENANCE |
| 10 | Complete maintenance | — | RESERVED |
| 11 | Check admission B | RESERVED | RESERVED |
| 12 | Check bed stats | — | RESERVED: 1 |


### 12.3 Key Demonstrations

- No bed ID in admission request: engine selects the bed.
- Automatic waiting list: queued by priority when no bed available.
- Priority ordering: EMERGENCY (300) > URGENT (200) > NORMAL (100); FIFO within same priority.
- Maintenance workflow: Discharge → MAINTENANCE → AVAILABLE (or auto-reserved).
- Concurrency safety: Pessimistic locking prevents double-allocation.
- Audit trail: Every bed status transition is logged with actor and reason.
- Automatic waiting list evaluation: Highest-priority waiting patient gets released bed immediately.


### 12.4 Swagger UI Walkthrough

1. Open http://localhost:8080/swagger-ui.html
2. Expand Hospital Management → POST /api/v1/hospitals → "Try it out"
3. Enter the hospital JSON body → "Execute"
4. Repeat for Ward, Room, Bed
5. Expand Admission & Allocation Engine → POST /api/v1/admissions/request
6. Submit admission without bed ID → observe automatic allocation
7. Submit second admission → observe WAITING_LIST status
8. Confirm admission → discharge → complete maintenance
9. Check the waiting admission now has the bed


---

## Appendix A: Configuration Reference

### A.1 application.yml (Production)

server:
  port: 8080
  error:
    include-message: always

spring:
  application:
    name: hospital-bed-allocation-engine
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:hospital_allocation_db}
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:2004}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  task:
    scheduling:
      pool:
        size: 5

springdoc:
  swagger-ui:
    path: /swagger-ui.html

allocation:
  reservation:
    timeout-minutes: 15
    cleanup-cron: "0 */1 * * * *"


### A.2 Environment Variables

| Variable | Default | Description |
|---|---|---|
| DB_HOST | localhost | MySQL host |
| DB_PORT | 3306 | MySQL port |
| DB_NAME | hospital_allocation_db | Database name |
| DB_USER | root | Database username |
| DB_PASSWORD | 2004 | Database password (change in production!) |


### A.3 Security Note

Use environment variables or a secrets manager for real deployments;
do not rely on the development default password.


---

## Appendix B: Known Implementation Notes and Limitations

| # | Note | Impact | Recommendation |
|---|---|---|---|
| 1 | Patient identity not verified | patientId is just a string | Add patient service |
| 2 | Admission requests do not specify a hospital | Allocation can select from any hospital | Add hospitalId to DTO |
| 3 | Room-level rules not protected by room lock | Concurrent isolation/gender checks could both pass | Add room-level lock |
| 4 | Cancellation can bypass discharge-cleaning | OCCUPIED → AVAILABLE directly | Restrict cancellation |
| 5 | Blocking a reserved bed leaves active reservation | Admission remains RESERVED on BLOCKED bed | Add validation |
| 6 | BedStatusTransition enum not sole enforcement point | Service methods set statuses directly | Centralize |
| 7 | Waiting-list priority applied only during evaluation | New admission doesn't check older entries first | Add pre-check |
| 8 | Not all coordination rows are locked | Expiry scheduler doesn't lock admission rows | Add pessimistic lock |
| 9 | Availability search is informational | Doesn't guarantee reservation | Document clearly |
| 10 | Concurrency tests use H2 | Not MySQL | Test with Testcontainers |
| 11 | No authentication/authorization | Endpoints are public | Add Spring Security |
| 12 | No blocked-bed release endpoint | No API for BLOCKED → AVAILABLE | Add unblock endpoint |


---

## Appendix C: Technology Stack

| Area | Implementation |
|---|---|
| Language | Java 17 |
| Web framework | Spring Boot 3.2.3 |
| Persistence | Spring Data JPA / Hibernate 6 |
| Production database | MySQL 8, InnoDB |
| Test database | H2 in MySQL compatibility mode |
| API documentation | springdoc-openapi / Swagger UI 2.3.0 |
| Validation | Jakarta Bean Validation |
| Scheduling | Spring @Scheduled |
| Build | Maven |
| Testing | JUnit 5, Mockito, Spring Boot Test |


---

## Appendix D: Component Responsibilities

| Component | Responsibility |
|---|---|
| HospitalController | Hospital creation and retrieval |
| WardController | Ward creation/retrieval and listing ward beds |
| RoomController | Room creation and retrieval |
| BedController | Bed creation, availability, blocking, maintenance, stats |
| AdmissionController | Admission request, reservation, confirmation, discharge, cancellation |
| BedAllocationEngineImpl | Finds and validates eligible beds (7 rules) |
| AdmissionServiceImpl | Admission lifecycle and transaction orchestration |
| WaitingListServiceImpl | Enqueueing and assigning waiting admissions |
| ReservationExpiryScheduler | Scheduled cleanup of expired reservations |
| GlobalExceptionHandler | Converts exceptions to HTTP responses |


---

## Appendix E: Test Coverage Summary

| Scenario | Test Class |
|---|---|
| No available beds | AdmissionLifecycleIntegrationTest.waitingListIsServedOnMaintenanceCompletion |
| Concurrent requests for one bed | BedAllocationConcurrencyTest.testConcurrentBedAllocationRaceCondition |
| Gender policy enforcement | BedAllocationEngineTest.shouldRejectWhenGenderMismatchesWardPolicy |
| Isolation policy | BedAllocationEngineTest.shouldRejectNonIsolationPatientFromIsolationRoom |
| Reservation expiry | ReservationExpiryIntegrationTest.expiredReservationIsReclaimed |
| Discharge and maintenance | AdmissionLifecycleIntegrationTest.fullLifecycle |
| Audit trail | AdmissionLifecycleIntegrationTest.auditTrailIsWritten |
| Search filters | BedAvailabilitySearchTest (12 tests) |
| Hospital/Room CRUD | HospitalRoomManagementTest (10 tests) |
| Double confirm race | ConcurrentManualReservationTest.doubleConfirmIsRejected |


---

# End of README

This documentation covers all requested sections: Architecture/Flow Diagram,
Database Schema, REST APIs, DTOs and Validation, Service-Layer Business Logic,
Global Exception Handling, Transaction/Concurrency Implementation, Unit Tests,
Integration Tests, Swagger Documentation, Postman Collection, and Final Walkthrough.
