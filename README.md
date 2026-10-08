# Hospital Bed & Resource Allocation Engine

Core inpatient resource engine for a telemedicine/healthcare ecosystem. Handles ward/room/bed
inventory, admission requests, intelligent bed allocation, reservation holds, a priority waiting
list, and a full audit trail — safely under concurrent load.

- **Stack:** Java 17, Spring Boot 3.2.3, Spring Data JPA, MySQL 8, springdoc-openapi, H2 (tests)
- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **API base:** `/api/v1`

---

## 1. Database initialisation

The schema is committed at the repo root as `schema.sql` (DDL **plus** a baseline seed row).
It is idempotent for the database, so re-running is safe.

```bash
mysql -u root -p < schema.sql
```

Baseline data it creates: hospital `HOSP-ALPHA`, ward `North ICU`, room `ICU-ROOM-1`, bed `BED-01`.

`schema.sql` is idempotent — every table is `CREATE TABLE IF NOT EXISTS` and the seed rows are
`INSERT IGNORE` resolved through natural keys, so re-running never duplicates or collides.

> PowerShell has no stdin redirect. Pipe it instead:
> `Get-Content schema.sql -Raw | mysql -u root -p`

**If startup fails with `Access denied for user 'root'@'localhost'`** — the credentials are wrong,
not the code. Verify directly with
`mysql -u root -h 127.0.0.1 -P 3306 -e "SELECT 1"`, then set `DB_PASSWORD` to match.

Connection settings are environment-overridable (defaults shown):

| Env var      | Default                  |
|--------------|--------------------------|
| `DB_HOST`    | `localhost`              |
| `DB_PORT`    | `3306`                   |
| `DB_NAME`    | `hospital_allocation_db` |
| `DB_USER`    | `root`                   |
| `DB_PASSWORD`| `2004`                   |

> The default password is baked into `application.yml` for local convenience. **Do not commit a
> real credential** — set `DB_PASSWORD` as an environment variable (or use a `.gitignore`d
> `application-local.yml`) for anything shared.

Hibernate runs with `ddl-auto: validate`, so a schema/entity mismatch fails fast at boot.

### Why every enum field carries `@JdbcTypeCode(SqlTypes.VARCHAR)`

Hibernate 6.4's `MySQLDialect` overrides `getEnumTypeDeclaration(...)` to emit a **native MySQL
`ENUM`** column for any `@Enumerated(EnumType.STRING)` field. There is no global setting to turn
this off (Hibernate 6.4 exposes no enum-related property), so a plain `@Enumerated` field expects
`enum ('male','female','other')` and `ddl-auto: validate` rejects a `VARCHAR` column:

```
wrong column type encountered in column [patient_gender] in table [admissions];
found [varchar (Types#VARCHAR)], but expecting [enum (...) (Types#ENUM)]
```

This project stores enums as `VARCHAR` on purpose — it keeps the schema readable and lets new enum
constants ship without an `ALTER TABLE`. Each enum mapping therefore pins its JDBC type:

```java
@Enumerated(EnumType.STRING)
@JdbcTypeCode(SqlTypes.VARCHAR)
@Column(name = "patient_gender", nullable = false, length = 20)
private PatientGender patientGender;
```

All 11 enum fields across `Ward`, `Bed`, `Admission` and `BedStatusLog` are annotated this way.
**If you add a new enum field, annotate it the same way** or validation will fail on it.

(The alternative — declaring native `ENUM` columns — also passes validation, but hardcodes the
value list into the DDL and requires an `ALTER TABLE` for every new enum constant.)

```bash
mvn spring-boot:run
```

---

## 2. Domain model

```
Hospital 1─* Ward 1─* Room 1─* Bed
Bed 1─* BedStatusLog                       (audit trail)
Bed 1─* Admission                          (FK ON DELETE SET NULL)
Admission 1─1 WaitingListEntry             (FK ON DELETE CASCADE)
```

### Bed status machine

```
AVAILABLE ──▶ RESERVED ──▶ OCCUPIED ──▶ MAINTENANCE ──▶ AVAILABLE
    │             │            │
    └──▶ BLOCKED ◀┴────────────┘
```

`BedStatusTransition` encodes the legal edges; illegal moves surface as HTTP 409.

### Admission status machine

```
PENDING ─┬─▶ RESERVED ─┬─▶ ADMITTED ─▶ DISCHARGED
         │             ├─▶ EXPIRED      (reservation hold elapsed, scheduled reclaim)
         └─▶ WAITING_LIST ─▶ RESERVED  (bed freed + hand-off)
                        └─▶ CANCELLED
```

---

## 3. Allocation eligibility rules

`BedAllocationEngineImpl.isBedEligibleForAdmission` rejects a bed unless **all** hold:

1. `bed.status == AVAILABLE`
2. ward type **and** bed type match the request exactly (no fallback / downgrades)
3. ward gender policy admits the patient (`MALE_ONLY` / `FEMALE_ONLY` / `UNISEX`)
4. isolation:
   - isolation patient ⇒ room **must** be an isolation room, and that room must hold
     **zero** `RESERVED`/`OCCUPIED` beds (isolation rooms are never shared)
   - non-isolation patient ⇒ must **not** be placed in an isolation room
5. multi-bed co-occupancy: every active admission in the room must be the same patient gender
   (prevents co-ed placement)

---

## 4. Concurrency strategy

The race under test: N simultaneous admission requests, M physical beds.

| Concern | Mechanism |
|---|---|
| Two requests grabbing one bed | `SELECT … FOR UPDATE` per candidate bed (`PESSIMISTIC_WRITE`) + re-validation of the freshly read row |
| Lock-queue pile-up | `jakarta.persistence.lock.timeout=0` — a contended bed is lost fast instead of blocking behind InnoDB's lock queue |
| Stale row from the 1st-level cache | candidates are fetched as **IDs** (`findEligibleCandidateBedIds`), so the locking read is the first time the `Bed` enters the persistence context; `retrieveMode=BYPASS` is a second line of defence |
| Optimistic drift | `@Version` on `beds` and `admissions` |
| Whole-queue lock serialisation | the waiting list is **not** locked as a set; writes happen inside the transaction that already holds the bed's row lock |
| Expired holds | `ReservationExpiryScheduler` reclaims stale reservations every minute and re-offers the bed |

Losing a race is a normal outcome, not an error: the request falls through to the waiting list.
Lock conflicts map to HTTP 409 with a retry hint.

---

## 5. Endpoints

### Wards — `/api/v1/wards`
| Method | Path | Purpose |
|---|---|---|
| POST | `/` | create ward |
| GET | `/{wardId}` | ward detail |
| GET | `/{wardId}/beds` | beds in a ward |

### Hospitals — `/api/v1/hospitals`
| Method | Path | Purpose |
|---|---|---|
| POST | `/` | register hospital (`code` normalised to uppercase; duplicates rejected) |
| GET | `/` | list hospitals |
| GET | `/{hospitalId}` | hospital detail |

### Rooms — `/api/v1/rooms`
| Method | Path | Purpose |
|---|---|---|
| POST | `/` | create room in a ward (`isolationRoom` flag) |
| GET | `/{roomId}` | room detail (includes `bedCount`) |
| GET | `/ward/{wardId}` | rooms in a ward |

### Beds — `/api/v1/beds`
| Method | Path | Purpose |
|---|---|---|
| POST | `/` | add bed to a room |
| GET | `/{bedId}` | bed detail |
| GET | `/available` | search — see below |
| POST | `/{bedId}/maintenance/complete` | `MAINTENANCE → AVAILABLE`, then re-evaluate waiting list |
| POST | `/{bedId}/block` | administrative hold |
| GET | `/stats` | counts per status |

`GET /beds/available` — every filter optional, all combinable:

| Param | Values |
|---|---|
| `hospitalId` | numeric id |
| `wardId` | numeric id |
| `bedType` | `STANDARD` `ICU` `VENTILATOR` `OXYGEN_SUPPORTED` `BARIATRIC` |
| `wardType` | `GENERAL` `ICU` `PEDIATRIC` `MATERNITY` `SURGICAL` |
| `isolationRequired` | `true` / `false` |
| `patientGender` | `MALE` `FEMALE` `OTHER` — matched against the ward's gender policy |

`patientGender` returns beds whose ward policy admits that gender: `UNISEX` wards always match,
`MALE_ONLY` matches only `MALE`, `FEMALE_ONLY` only `FEMALE`, and `OTHER` only sees `UNISEX` wards.

```bash
curl "localhost:8080/api/v1/beds/available?wardType=MATERNITY&patientGender=FEMALE"
```

### Admissions — `/api/v1/admissions`
| Method | Path | Purpose |
|---|---|---|
| POST | `/request` | create admission, run allocation engine |
| GET | `/{admissionId}` | detail |
| GET | `/patient/{patientId}` | active admissions for a patient |
| POST | `/{admissionId}/reserve?bedId=` | manual reservation (pending/waiting only) |
| POST | `/{admissionId}/confirm` | `RESERVED → OCCUPIED` |
| POST | `/{admissionId}/discharge` | `ADMITTED → DISCHARGED`, bed → `MAINTENANCE` |
| POST | `/{admissionId}/cancel?reason=` | cancel and release the bed immediately |

All responses use the envelope `{ "success": boolean, "message": string, "data": … }`.

Status codes: `404` not found · `409` invalid state transition or lock conflict ·
`422` bed not eligible · `400` validation · `500` unexpected.

---

See **[ARCHITECTURE.md](ARCHITECTURE.md)** for the full layer map, data model, concurrency
design, and flow diagrams.

---

## 6. Configuration

```yaml
allocation:
  reservation:
    timeout-minutes: 15      # reservation hold window
    cleanup-cron: "0 */1 * * * *"   # expiry sweep cadence
```

---

## 7. Tests

```bash
mvn test
```

| Test | Tests | Scope |
|---|---|---|
| `BedAllocationEngineTest` | 8 | Mockito unit tests over every eligibility rule (gender policy, isolation exclusivity, co-ed prevention, type mismatch, non-AVAILABLE) |
| `BedAllocationConcurrencyTest` | 1 | 10 threads released by a latch against **1** ICU bed ⇒ exactly 1 `RESERVED`, 9 `WAITING_LIST`, bed still `RESERVED` |
| `ConcurrentManualReservationTest` | 2 | two operators race for the same bed via `/reserve`; double-confirm rejected |
| `ReservationExpiryIntegrationTest` | 6 | expiry sweep reclaims lapsed holds, audit actor, live hold survives, occupied bed never swept, reclaimed bed serves the waiting list |
| `BedAvailabilitySearchTest` | 11 | availability filters incl. **patient gender**, ward type, contradictory filters, non-AVAILABLE exclusion |
| `AdmissionLifecycleIntegrationTest` | 7 | full lifecycle, waiting-list hand-off, cancel-releases-bed, audit trail, isolation end-to-end |
| `HospitalRoomManagementTest` | 10 | hospital/room CRUD, validation, duplicate detection, MockMvc status codes |

**46 tests total.** Tests run on H2 in MySQL mode with `ddl-auto: create-drop`; the expiry cron
is parked at `0 0 0 1 1 *` so the scheduler cannot interfere with assertions. The expiry and
manual-reservation tests invoke `ReservationExpiryScheduler.processExpiredReservations()`
directly rather than waiting on wall-clock time.

`Hospital_Bed_Engine.postman_collection.json` covers manual/load testing against a live MySQL
instance.

---

## 8. Tuning notes

- `spring.jpa.open-in-view: false` is on — all lazy loading must happen inside a service
  transaction. New read paths need `@Transactional(readOnly = true)` on the service method.
- Hikari pool is 20; the allocation path holds at most 2 locks at a time (admission row, bed row),
  so the pool is not the throughput ceiling — row contention on a scarce bed type is.
- `waiting_list.priority_score` is seeded from `AdmissionPriority.getWeight()` (300/200/100);
  ties break FIFO on `enqueued_at`.
- `GET /beds/available` builds a single query with `(:param IS NULL OR col = :param)`. It is
  correct but the optimiser cannot use `idx_beds_status_type` for the nullable predicates — for a
  hot search endpoint, switch to `JpaSpecificationExecutor` so omitted filters drop out of the
  `WHERE` clause entirely.
- `BedStatusLog` is append-only and unindexed on `(bed_id, logged_at)` in older installs; the
  committed `schema.sql` adds that index.