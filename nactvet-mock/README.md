# NACTVET mock

A development stand-in for **NACTVET admission verification** (BP-04, FR-ADM-12, FR-INT-02), **student registration**
(FR-REG-07) and **results submission** (FR-EXM-11). CIMS's live adapter
(`LiveNactvetGateway`) talks to it over HTTP exactly as it would talk to NACTVET, so the whole path can be tried without
NACTVET: submit selected applicants, fetch outcomes (some still processing), resolve queries, confirm multiple admissions,
and see what happens when NACTVET is down or refuses the credentials.

It speaks the NOAS style of NACTVET's api-out-service: every call is a JSON POST carrying `authorization` and `api_code`,
answered with HTTP 200 and `{code, message, params}`. **The paths and field names are placeholders** until NACTVET publishes
its admission verification API. CIMS reads them from configuration (`cims.nactvet.verification.*`), so only configuration
changes when the real ones are known. Batches and confirmation codes are saved to `data/nactvet-mock-state.json`
(gitignored) and read back when the mock restarts, so CIMS can still fetch outcomes of earlier batches. **Reset everything**
on the dashboard clears it.

## Run it

```powershell
cd C:\dev\msingi-cims\nactvet-mock
.\mvnw.cmd spring-boot:run
```

It listens on port 8099. Open **http://localhost:8099** for its dashboard: every batch CIMS has submitted with each
candidate's outcome (refreshed every 3 seconds), the confirmation code for multiple admissions with a **Confirm elsewhere**
button, an outage switch and a reset.

Then run CIMS with the `nactvet-mock` profile as well as `dev`:

```powershell
cd C:\dev\msingi-cims\backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev,nactvet-mock"
```

| Setting (environment variable) | Default | Meaning |
|---|---|---|
| `MOCK_PORT` | `8099` | Port |
| `MOCK_NACTE_AUTHORIZATION`, `MOCK_NACTE_API_CODE` | `mock-authorization`, `MOCK` | Credentials CIMS must send (the `nactvet-mock` profile sends these) |
| `MOCK_PROCESSING_SECONDS` | `5` | Seconds before a candidate has an outcome; index numbers ending in 5 take three times as long |
| `MOCK_STATE_FILE` | `data/nactvet-mock-state.json` | Where batches are saved between restarts; empty keeps them in memory only |

## Outcomes

Decided by the last digit of the candidate's CSEE index number, the same rules as CIMS's built-in simulator:

| Last digit | Outcome |
|---|---|
| 7 | `QUERIED` (name does not match NECTA); verified once resubmitted with remarks |
| 9 | `NOT_VERIFIED` (does not meet the minimum entry requirements) |
| 3 | `VERIFIED`, also admitted by another institution: the applicant needs NACTVET's confirmation code to accept |
| 5 | `VERIFIED`, after three times the processing time (try **Fetch outcomes** twice) |
| any other | `VERIFIED` |

Also: a candidate without names, date of birth or programme code is `QUERIED`, and so is the same index number twice in
one batch. An application already decided in an earlier batch gets the same outcome and verification number again.

The mock has no SMS. The confirmation code it "sends" is written to its log, and with the `nactvet-mock` profile CIMS shows
it on the applicant's offer page in development, as it does with the built-in simulator.

## NACTVET endpoints (placeholders)

All under `/nacteapi/index.php/api-out-service`. Body `code`: 200 OK, 401 wrong credentials, 404 not found, 422 invalid request.

| Path | Request (besides `authorization`, `api_code`) | `params` in the answer |
|---|---|---|
| `/admissionverification` | `institution_code`, `candidates`: `application_ref`, `index_number`, `exam_year`, `first_name`, `middle_name`, `surname`, `sex`, `date_of_birth`, `nationality`, `phone`, `programme_code`, `programme_name`, `award_level`, `remarks` | `batch_ref`, `received` |
| `/admissionverificationresults` | `batch_ref` | one entry per candidate: `application_ref`, `index_number`, `programme_code`, `status` (`PROCESSING`, `VERIFIED`, `QUERIED`, `NOT_VERIFIED`), `reason`, `verification_number`, `multiple_admission` (0/1) |
| `/admissionconfirmation` | `institution_code`, `index_number`, `confirmation_code` | `confirmed` (0/1), `reason` (`WRONG_CODE`, `ALREADY_CONFIRMED_ELSEWHERE`) |

## Student registration and results (placeholders)

CIMS's `LiveNactvetSubmissionClient` (profile `gov-mocks` sets `CIMS_NACTVET_SUBMISSION_MODE=live` against this mock).
Same envelope and credentials as above; outcomes appear after the processing time (`PROCESSING` until then).

| Path | Request | `params` in the answer |
|---|---|---|
| `/studentregistration` | `institution_code`, `academic_year`, `students`: `registration_no`, `verification_number`, `index_number`, names, `sex`, `date_of_birth`, `nationality`, `phone`, `programme_code`, `year_of_study` | `batch_ref`, `received` |
| `/studentregistrationstatus` | `batch_ref` | `status` (`PROCESSING`, `COMPLETED`), `items`: `registration_no`, `status` (`REGISTERED`, `QUERIED`), `reason`, `nactvet_number` |
| `/resultsubmission` | `institution_code`, `programme_code`, `academic_year`, `semester`, `year_of_study`, `round`, `results`: `registration_no`, `verification_number`, `modules` (`code`, `grade`), `gpa`, `remark` | `batch_ref`, `received` |
| `/resultsubmissionstatus` | `batch_ref` | `status` (`PROCESSING`, `ACCEPTED`, `QUERIED`), `items` as above |

Rules: a student without a verification number, date of birth or programme code is queried; a results row without a
verification number, or with a module without a grade, is queried, and the sheet is accepted only when no row is.
`GET /mock/submissions` lists them; the dashboard shows them under the verification batches. Kept in memory only.

## Developer endpoints (not at NACTVET)

Index numbers in these paths use `-` for `/` (`S0101-0003`).

| Call | Does |
|---|---|
| `GET /mock/status` | Outage on or off, number of batches, processing time |
| `GET /mock/batches` | Every batch received, with its outcomes so far (and confirmation codes) |
| `GET /mock/confirmation-codes/{index}` | The confirmation code for a multiple admission |
| `POST /mock/confirm-elsewhere/{index}` | The other institution confirms first, so this institution's confirmation is refused |
| `POST /mock/outage?on=true` | Every NACTVET call answers HTTP 503 until `on=false` (CIMS shows "NACTVET could not be reached") |
| `POST /mock/reset` | Forget everything |

```powershell
Invoke-RestMethod http://localhost:8099/mock/batches
Invoke-RestMethod -Method Post "http://localhost:8099/mock/outage?on=true"
```

## Tests

`SubmissionTest`: student registration and results outcomes and credentials.
`NactvetMockTest`: outcomes by index number and processing time, resubmission and repeated decisions, incomplete and
duplicate candidates, confirmation codes and confirmation elsewhere, credentials, unknown batches and outages.
`StatePersistenceTest`: batches, verification numbers and confirmation codes survive a restart; reset clears the file.
