# Architecture & Flow

Hospital Bed & Resource Allocation Engine — layer map, concurrency design, and workflow diagrams.

---

## 1. Layered architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  PRESENTATION                                                                 │
│  AdmissionController   BedController   WardController                        │
│  RoomController        HospitalController                                    │
│  ── @Valid DTO binding · ApiResponse<T> envelope · GlobalExceptionHandler    │
└──────────────────────────────────────────────────────────────────────────────┘
                                    │
┌──────────────────────────────────────────────────────────────────────────────┐
│  SERVICE  (business logic + transaction boundaries)                          │
│                                                                              │
│  AdmissionService      Reservation lifecycle, cancel, discharge              │
│  BedAllocationEngine   ★ Eligibility rules + candidate locking               │
│  WaitingListService    Priority queue, bed hand-off                          │
│  BedManagementService  Hospital/Ward/Room/Bed inventory, maintenance        │
│  ReservationExpiryScheduler  Reclaims lapsed holds (cron)                   │
│                                                                              │
│  ── @Transactional(READ_COMMITTED) on allocation write paths                 │
│  ── @Transactional(readOnly = true) on all read paths                        │
└──────────────────────────────────────────────────────────────────────────────┘
                                    │
┌──────────────────────────────────────────────────────────────────────────────┐
│  PERSISTENCE                                                                 │
│  HospitalRepository  WardRepository  RoomRepository                          │
│  BedRepository       AdmissionRepository                                    │
│  WaitingListRepository  BedStatusLogRepository                              │
│                                                                              │
│  ── PESSIMISTIC_WRITE for contended rows · @Version for drift detection     │
└──────────────────────────────────────────────────────────────────────────────┘
                                    │
┌──────────────────────────────────────────────────────────────────────────────┐
│  MySQL 8 · InnoDB · hospital_allocation_db                                   │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Package layout

| Package | Responsibility |
|---|---|
| `model.entity` | JPA entities; `@Version` on `Bed` and `Admission` |
| `model.enums` | `BedStatus`, `BedStatusTransition`, `BedType`, `WardType`, `GenderPolicy`, `PatientGender`, `AdmissionPriority`, `AdmissionStatus` |
| `dto.request` | Records with Bean Validation annotations |
| `dto.response` | Records + `ApiResponse<T>` envelope |
| `repository` | Spring Data interfaces, JPQL, pessimistic locks |
| `service` / `service.impl` | Business logic, transactions |
| `scheduler` | Cron-driven reservation expiry |
| `controller` | REST endpoints |
| `exception` | Typed exceptions + `@RestControllerAdvice` |

---

## 2. Data model

```
Hospital ──1:N──▶ Ward ──1:N──▶ Room ──1:N──▶ Bed
                                              │
                    ┌─────────────────────────┼─────────────────────────┐
                    │                         │                         │
              BedStatusLog              Admission              (status, version)
                (audit trail)                 │
                                          1:1
                                    WaitingListEntry
```

| Table | Purpose | Key indexes |
|---|---|---|
| `hospitals` | Root of inventory | `code` UNIQUE |
| `wards` | Care unit with gender policy | `uk_ward_name(hospital_id,name)`, `idx_wards_type` |
| `rooms` | Isolation flag lives here | `uk_ward_room(ward_id,room_number)`, `idx_rooms_isolation` |
| `beds` | Allocatable unit | `uk_room_bed`, `idx_beds_status_type(status,bed_type)`, `idx_beds_lookup(room_id,status)` |
| `admissions` | Patient stay / request | `idx_admissions_patient`, `idx_admissions_reservation(status,reservation_expires_at)` |
| `waiting_list` | Priority queue | `admission_id` UNIQUE, `idx_waiting_priority(priority_score DESC, enqueued_at ASC)` |
| `bed_status_logs` | Full transition history | `idx_bed_logs_bed(bed_id, logged_at)` |

`admissions.allocated_bed_id` is `ON DELETE SET NULL` — deleting a bed never destroys clinical
history. `waiting_list.admission_id` and `bed_status_logs.bed_id` cascade.

---

## 3. Admission & allocation flow

```
POST /api/v1/admissions/request
          │
          ▼
┌─────────────────────────────┐
│ @Transactional(READ_COMMITTED)
└─────────────────────────────┘
          │
          ▼
   persist Admission (PENDING)
          │
          ▼
┌───────────────────────────────────────────────────────────┐
│ BedAllocationEngine.allocateBed()                         │
│                                                           │
│ 1. SELECT b.id … WHERE wardType=? AND bedType=?           │
│      AND isolationRoom=? AND status=AVAILABLE             │
│    → IDs only, so no stale entity enters the 1st-level    │
│      cache before the locking read                        │
│ 2. for each candidate id:                                  │
│      SELECT … FOR UPDATE   (lock.timeout=0 → NOWAIT)      │
│        ├─ lock unavailable ──▶ skip (lost race)           │
│        └─ locked ──▶ re-run all 7 eligibility rules       │
└───────────────────────────────────────────────────────────┘
          │
     ┌────┴────┐
     │         │
   FOUND    EMPTY
     │         │
     ▼         ▼
  bed         WaitingListService.enqueue()
  →RESERVED   status=WAITING_LIST
  admission   priority_score = priority.weight
  →RESERVED
  expiresAt   break
  =now+15m         │
     │             ▼
     │      HTTP 201 { status: WAITING_LIST }
     ▼
  HTTP 201 { status: RESERVED, allocatedBedId, reservationExpiresAt }
```

### The 7 eligibility rules

Evaluated against the freshly locked row, in `BedAllocationEngineImpl`:

| # | Rule | Rejects when |
|---|---|---|
| 1 | **Bed status** | not `AVAILABLE` (via `BedStatusTransition.isAllowedToReceivePatient`) |
| 2 | **Existing reservation** | an admission in `RESERVED`/`ADMITTED` already holds this bed |
| 3 | **Required ward** | ward type ≠ requested ward type |
| 4 | **Required bed type** | bed type ≠ requested bed type |
| 5 | **Gender compatibility** | ward `MALE_ONLY`/`FEMALE_ONLY` vs patient gender |
| 6 | **Isolation** | isolation patient needs an isolation room with **zero** active beds; non-isolation patient must **never** be placed in one |
| 7 | **Co-occupancy** | another patient of a different gender is active in the same room |

Rule 2 is defence-in-depth: even if the status column were wrong, a bed with a live admission
attached cannot be re-issued.

---

## 4. Concurrency design

### The race being closed

N simultaneous admission requests, M physical beds. Without locking, all N read `status=AVAILABLE`
and N-1 over-allocate.

```
 t0   T1        T2        T3
      │ SELECT id=1 (AVAILABLE)
      │          SELECT id=1 (AVAILABLE)
      │                   SELECT id=1 (AVAILABLE)
 t1   │ FOR UPDATE ✓ lock
      │ set RESERVED
 t2   │ COMMIT ──────────────▶ lock released
      │                     FOR UPDATE ✓ (blocks on InnoDB queue)
 t3   │                     reads RESERVED → rule 1 fails → skip
```

### Defence layers

| Layer | Mechanism | Prevents |
|---|---|---|
| 1. Row lock | `SELECT … FOR UPDATE` per candidate | two writers mutating one bed |
| 2. NOWAIT | `jakarta.persistence.lock.timeout=0` | InnoDB lock-queue pile-up → deadlock |
| 3. Fresh read | candidates fetched as **IDs**, not entities | Hibernate 1st-level cache serving a stale `AVAILABLE` snapshot to a loser |
| 4. Re-validation | all 7 rules re-run on the locked row | allocating on pre-lock assumptions |
| 5. Optimistic | `@Version` on `beds` + `admissions` | undetected drift if a lock is ever bypassed |
| 6. Admission lock | `FOR UPDATE` on the admission row | double-confirm / concurrent cancel |

Losing a race is a **normal outcome**, not an error: the request falls through to the waiting
list. Contention maps to HTTP 409 with a retry hint.

### Why the waiting list is not locked

Locking the whole queue per allocation would serialise unrelated admissions and is the main
deadlock risk in this schema. Every write happens inside the transaction that already holds the
bed's row lock, which is sufficient — the bed is the contended resource, not the queue.

---

## 5. Reservation lifecycle

```
                    ┌──────────────────────────────────────────┐
                    │                                          │
   AVAILABLE ──▶ RESERVED ──▶ OCCUPIED ──▶ MAINTENANCE ──▶ AVAILABLE
       │            │                          ▲              │
       │            │ expiry sweep              │              │
       │            │ (cron, 1 min)             │              │
       └────────────┴──────────────────────────┴──────────────┘
                    │
                    └──▶ EXPIRED (admission)  → bed re-offered to waiting list
```

| Transition | Trigger | Endpoint / actor |
|---|---|---|
| `AVAILABLE → RESERVED` | allocation engine, manual reserve, waiting-list hand-off | `POST /admissions/request`, `POST /admissions/{id}/reserve` |
| `RESERVED → OCCUPIED` | patient arrival | `POST /admissions/{id}/confirm` |
| `RESERVED → AVAILABLE` | hold lapsed (>15 min, no arrival) | `ReservationExpiryScheduler` |
| `OCCUPIED → MAINTENANCE` | discharge | `POST /admissions/{id}/discharge` |
| `MAINTENANCE → AVAILABLE` | housekeeping complete | `POST /beds/{id}/maintenance/complete` |
| `* → BLOCKED` | administrative hold (repair, bed bug) | `POST /beds/{id}/block` |
| `BLOCKED → AVAILABLE` | cleared | re-release |

**Discharge never auto-releases.** A discharged bed goes to `MAINTENANCE` and stays
unallocatable until housekeeping explicitly completes cleaning. This is deliberate: the
requirement forbids blindly making a discharged bed available.

---

## 6. Waiting list hand-off

```
 bed released (expiry sweep | maintenance complete | cancel)
          │
          ▼
 ┌────────────────────────────────┐
 │ lock bed FOR UPDATE            │
 │ if status != AVAILABLE: return │
 └────────────────────────────────┘
          │
          ▼
 SELECT … ORDER BY priority_score DESC, enqueued_at ASC
          │
          ▼
 for each queued admission (highest priority first):
   ├─ status != WAITING_LIST ──▶ drop stale entry, continue
   ├─ fails 7 eligibility rules ──▶ continue to next
   └─ eligible ──▶ RESERVE for this admission
                   bed → RESERVED
                   admission → RESERVED, expiresAt = now + 15m
                   dequeue
                   audit log
                   BREAK  (one bed, one patient)
```

Priority score comes from `AdmissionPriority.getWeight()`: `EMERGENCY=300`, `URGENT=200`,
`NORMAL=100`. Ties break FIFO on `enqueued_at`.

---

## 7. Admission state machine

```
                     ┌──────────────┐
   POST /request ───▶│   PENDING    │
                     └──────┬───────┘
              ┌─────────────┴─────────────┐
        bed found                     no bed
              ▼                           ▼
       ┌────────────┐             ┌──────────────┐
       │  RESERVED  │             │ WAITING_LIST │
       └──┬───┬───┬──┘             └──────┬───────┘
          │   │   │                       │ bed freed
   confirm│   │   │cancel            ┌─────┘
          │   │   └──────────────────▶│
          ▼   │                      ▼
   ┌──────────┐│               ┌───────────┐
   │ ADMITTED ││               │ RESERVED  │
   └────┬─────┘│               └───────────┘
        │      │
   discharge  expiry sweep
        │      │
        ▼      ▼
   ┌──────────────┐
   │  DISCHARGED  │        ┌───────────┐
   │   EXPIRED    │───────▶│ CANCELLED │
   └──────────────┘        └───────────┘
```

Illegal transitions throw `InvalidStateTransitionException` → **HTTP 409**.

---

## 8. Error mapping

| Exception | HTTP | Meaning |
|---|---|---|
| `ResourceNotFoundException` | 404 | unknown hospital/ward/room/bed/admission |
| `InvalidStateTransitionException` | 409 | workflow violation, e.g. discharge before admit |
| `BedAllocationConflictException` | 409 | lost a concurrency race — **retryable** |
| `ConcurrencyFailureException` / `PessimisticLockException` / `OptimisticLockingFailureException` | 409 | lock timeout or version conflict |
| `BedUnavailableException` | 422 | bed genuinely ineligible for this admission |
| `DataIntegrityViolationException` | 409 | duplicate code / bad FK |
| `MethodArgumentNotValidException` | 400 | Bean Validation failure |
| `HttpMessageNotReadableException` | 400 | malformed body / bad enum name |

Note: Spring translates a `@Version` mismatch into `ObjectOptimisticLockingFailureException`,
which is **not** a `jakarta.persistence.OptimisticLockException` — both are mapped explicitly,
otherwise every lost race would surface as an opaque 500.

---

## 9. Test map

| Test class | Tests | Covers |
|---|---|---|
| `BedAllocationEngineTest` | 8 | all eligibility rules, pure Mockito |
| `BedAllocationConcurrencyTest` | 1 | 10 threads → 1 bed ⇒ exactly 1 `RESERVED` |
| `ConcurrentManualReservationTest` | 2 | two operators race one bed; double-confirm rejected |
| `ReservationExpiryIntegrationTest` | 6 | expiry sweep, live-hold safety, occupied-bed safety, hand-off |
| `BedAvailabilitySearchTest` | 11 | availability filters incl. patient gender |
| `AdmissionLifecycleIntegrationTest` | 7 | full lifecycle, waiting list, cancel, audit, isolation |
| `HospitalRoomManagementTest` | 10 | hospital/room CRUD, validation, duplicates, MockMvc |

**46 tests.** The expiry scheduler and manual-reservation races were previously untested paths.