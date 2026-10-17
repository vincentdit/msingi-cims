# NACTVET mock

A development stand-in for **NACTVET admission verification** (BP-04, FR-ADM-12, FR-INT-02), **student registration**
(FR-REG-07) and **results submission** (FR-EXM-11). CIMS's live adapter
(`LiveNactvetGateway`) talks to it over HTTP exactly as it would talk to NACTVET, so the whole path can be tried without
NACTVET: submit selected applicants, fetch outcomes (some still processing), resolve queries, confirm multiple admissions,
and see what happens when NACTVET is down or refuses the credentials.

Admission verification follows **NOAS's integration with NACTVET**: selected applicants are uploaded one programme at a
time to `/nacteapi/index.php/api/upload`, and outcomes are pulled from NACTVET's verification feedback
`/nacteapi/index.php/api-out-service/verifiedresultsall` by application year and intake. The confirmation of a multiple
admission is not part of NOAS; its path stays a placeholder. CIMS reads all three paths from configuration
(`cims.nactvet.verification.*`). Uploads and confirmation codes are saved to `data/nactvet-mock-state.json` (gitignored)
and read back when the mock restarts, so CIMS can still fetch outcomes of earlier uploads. **Reset everything** on the
dashboard clears it. (A state file from before uploads by programme is not read back: the mock starts empty.)

## Run it

```powershell
cd C:\dev\msingi-cims\nactvet-mock
.\mvnw.cmd spring-boot:run
```

It listens on port 8099. Open **http://localhost:8099** for its dashboard: every upload CIMS has made (programme, intake,
payment reference) with each applicant's outcome (refreshed every 3 seconds), the confirmation code for multiple admissions with a **Confirm elsewhere**
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
| `MOCK_STATE_FILE` | `data/nactvet-mock-state.json` | Where uploads are saved between restarts; empty keeps them in memory only |

## Outcomes

Decided by the last digit of the applicant's form four index number, the same rules as CIMS's built-in simulator. In the
feedback they appear as NACTVET writes them:

| Last digit | Outcome | `verification_status` / `eligibility` in the feedback |
|---|---|---|
| 7 | Queried (name does not match NECTA); verified when uploaded again | `Returned for correction` |
| 9 | Not verified (does not meet the minimum entry requirements) | `Not Approved` / `Not Eligible` |
| 3 | Verified, also admitted by another institution: the applicant needs NACTVET's confirmation code to accept | `Verified and Approved` / `Eligible`, `multiple_selection` = `Multiple selection` |
| 5 | Verified, after three times the processing time (try **Fetch outcomes** twice) | `Pending` until then |
| any other | Verified | `Verified and Approved` / `Eligible` |

Also: an applicant without names, date of birth or form four index number is queried, and so is the same index number
twice in one upload. An applicant already decided in an earlier upload for the same programme gets the same outcome and
verification number again. The institution is whoever holds the mock's credential (NACTVET knows it from the token).

The mock has no SMS. The confirmation code it "sends" is written to its log, and with the `nactvet-mock` profile CIMS shows
it on the applicant's offer page in development, as it does with the built-in simulator.

## NACTVET admission verification endpoints (as NOAS uses them)

Answers are HTTP 200 with a `code`: 200 OK, 401 wrong credentials, 404 not found, 422 invalid request; HTTP 503 during a
simulated outage.

| Path | Request | Answer |
|---|---|---|
| `/nacteapi/index.php/api/upload` | A JSON **array** wrapping one object: `heading` (`authorization`, `intake` SEPTEMBER or MARCH, `programme_id`, `application_year`, `level`, `payment_reference_number`) and `students`, each `{particulars}`: `firstname`, `secondname`, `surname`, `DOB` (d-MM-yyyy), `gender`, `impairement`, `form_four_indexnumber`, `form_four_year`, `form_six_indexnumber`, `form_six_year`, `NTA4_reg`, `NTA4_grad_year`, `NTA5_reg`, `NTA5_grad_year`, `email_address`, `mobile_number`, `address`, `region`, `district`, `nationality`, `next_kin_name`, `next_kin_address`, `next_kin_email_address`, `next_kin_phone`, `next_kin_region`, `next_kin_relation` | `{code, message, params: {received, reference}}` |
| `/nacteapi/index.php/api-out-service/verifiedresultsall` | `authorization`, `api_code`, `year`, `intake`, `page_number`, `page_size` | `{code, page_number, page_size, total_records, total_pages, params}`; each row: `user_id` (the form four index number in the mock), `student_verification_id`, `first_name`, `second_name`, `last_name`, `gender`, `date_of_birth`, `disability`, `programme_id`, `institution_id`, `verification_status`, `multiple_selection`, `academic_year`, `intake`, `eligibility`, `remarks` |
| `/nacteapi/index.php/api-out-service/admissionconfirmation` (placeholder) | `authorization`, `api_code`, `institution_code`, `index_number`, `confirmation_code` | `params`: `confirmed` (0/1), `reason` (`WRONG_CODE`, `ALREADY_CONFIRMED_ELSEWHERE`) |

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
| `GET /mock/status` | Outage on or off, number of uploads, processing time |
| `GET /mock/batches` | Every upload received (programme, intake, payment reference), with its outcomes so far (and confirmation codes) |
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
`NactvetMockTest`: outcomes by index number and processing time in the feedback, paging and year/intake, uploading again
and repeated decisions, incomplete and duplicate applicants, confirmation codes and confirmation elsewhere, credentials,
incomplete headings and outages.
`StatePersistenceTest`: uploads, verification numbers and confirmation codes survive a restart; reset clears the file.
