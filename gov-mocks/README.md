# Government systems mock

Development stand-ins for the government systems CIMS talks to, so CIMS's **live** adapters can be run end to end on a
developer's computer:

| System | Requirement | What the mock does |
|---|---|---|
| **GePG** | FR-INT-03 | Signed XML in the shape of the GePG service provider specification: checks CIMS's signature on each bill (SHA1withRSA), acknowledges with 7101, sends the control number back a moment later by callback, posts payments made from the dashboard, answers reconciliation requests |
| **HESLB** | FR-INT-05 | Receives registration and continuation data; answers with beneficiaries and disbursements |
| **NIDA** | FR-INT-06 | Checks a NIN against names and date of birth |
| **MUSE** | FR-INT-07 | Receives summarised fee journals; refuses unbalanced ones and duplicates |
| **TAMISEMI** | FR-INT-08 | Lists the students it selected to an institution |
| **NHIF** | FR-INT-10 | Checks students' health insurance cards; registers students under the student package and issues cards |

GePG follows its specification's message shapes and status codes. **The HESLB, NIDA, MUSE, TAMISEMI and NHIF paths and fields
are placeholders** until each body issues the institution its interface document; CIMS reads every path from configuration
(`cims.heslb.*`, `cims.nida.*`, `cims.muse.*`, `cims.tamisemi.*`, `cims.nhif.*`), so only configuration changes then. Everything is kept in
memory: restarting the mock forgets it.

## Run it

```powershell
cd C:\dev\msingi-cims\gov-mocks
.\mvnw.cmd spring-boot:run
```

It listens on **http://localhost:8098** (dashboard with a tab per system). Then run CIMS with the `gov-mocks` profile
(also start `nactvet-mock` for NACTVET student registration and results):

```powershell
cd C:\dev\msingi-cims\backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev,gov-mocks,nactvet-mock"
```

In CIMS, under **Administration → Integrations**, enter a GePG SP code, sub SP code, system id and default GFS code (any
values, e.g. SP19921 / 1001 / LCIMS001 / 140206) and switch on HESLB, NIDA, MUSE, TAMISEMI and NHIF. The DEMO institution is
private (it collects fees into its own accounts); to try GePG set it to public under Institution.

Or with Docker: `docker compose --profile mocks up -d gov-mocks nactvet-mock` (from the repository root).

| Setting (environment variable) | Default | Meaning |
|---|---|---|
| `MOCK_PORT` | `8098` | Port |
| `MOCK_CIMS_URL` | `http://localhost:8085` | Where CIMS runs: control numbers, payments and reconciliations are posted there |
| `MOCK_GEPG_KEYSTORE`, `MOCK_GEPG_SP_CERTIFICATE` | `../dev-keys/…` | The mock's GePG key and CIMS's certificate (TEST-ONLY, see `dev-keys/README.md`) |
| `MOCK_GEPG_DELAY_MS` | `1500` | How long GePG takes to send the control number and the reconciliation |
| `MOCK_HESLB_API_KEY`, `MOCK_NIDA_API_KEY`, `MOCK_MUSE_API_KEY`, `MOCK_TAMISEMI_API_KEY`, `MOCK_NHIF_API_KEY` | `mock-…-key` | Keys CIMS must send in `X-Api-Key` (the `gov-mocks` profile sends these) |

## GePG

| Path | Message | Answer |
|---|---|---|
| `POST /api/bill/sigqrequest` | `gepgBillSubReq` signed by CIMS | `gepgBillSubReqAck`: 7101, or 7303 bad signature, 7242 invalid bill (items not adding up, no GFS code) |
| `POST /api/reconciliations/sig_sp_qrequest` | `gepgSpReconcReq` | `gepgSpReconcReqAck` 7101; the day's payments follow by callback |

Callbacks to CIMS (signed with the mock's GePG key, retried until CIMS answers 7101):
`/api/integrations/gepg/control-number` (`gepgBillSubResp`), `/payment-notification` (`gepgPmtSpInfo`),
`/reconciliation` (`gepgSpReconcResp`).

Dashboard: each bill with its control number and whether CIMS acknowledged it; **Pay in full** / **Pay part** sends a
payment; **Send again** re-sends one (CIMS must not post it twice); switches to refuse new bills and to add a payment CIMS
does not know to the next reconciliation (an exception to resolve).

## HESLB, NIDA, MUSE, TAMISEMI, NHIF

| Path | Rules |
|---|---|
| `POST /heslb/api/v1/registrations` | Students without a CSEE index number are rejected. Those whose index number ends in an even digit become beneficiaries (tuition 1,200,000 for 0/2/4, 800,000 for 6/8) |
| `GET /heslb/api/v1/beneficiaries?institution_code&academic_year` | Beneficiaries with allocations; `total_to_institution` is what HESLB pays the institution |
| `GET /heslb/api/v1/disbursements?institution_code&academic_year` | Batches made with **Disburse** on the dashboard (half of each beneficiary's tuition) |
| `POST /nida/api/v1/verify` | NIN of 20 digits starting with the birth date (YYYYMMDD): ending in 9 → NOT_FOUND; other birth date → MISMATCH; otherwise VALID |
| `POST /muse/api/v1/journals` | Debits must equal credits; credit lines need a GFS code; the same `batch_ref` again → 409 with the journal already posted |
| `GET /tamisemi/api/v1/selections?institution_code&year` | Selections added on the dashboard (random students, or one with a chosen index number) |
| `POST /nhif/api/v1/cards/verify` | `card_no` ending in 0 → NOT_FOUND; 9 → INACTIVE; 8 → ACTIVE but held by NEEMA JOHN KIMARO; otherwise ACTIVE to the next 30 June. Cards issued by registration or added on the dashboard answer with their holder |
| `POST /nhif/api/v1/student-registrations` | Students without a date of birth are rejected on receipt; registration numbers ending in 7 are refused (already a member); the others get a card valid to 30 June at the end of the academic year |
| `GET /nhif/api/v1/student-registrations/status?institution_code&batch_ref` | `PROCESSING` (items `PENDING`) for `MOCK_NHIF_PROCESSING_SECONDS` (default 5), then `COMPLETED` with each student `REGISTERED` (card number) or `REJECTED` (reason) |

The institution code is CIMS's NACTVET registration number for the institution (e.g. `REG/DEMO/001`) unless another code
is entered under Integrations.

Developer endpoints: `GET /mock/status`, `POST /mock/outage?system=GEPG&on=true` (every call to that system answers 503),
`GET /mock/{gepg|heslb|nida|muse|tamisemi|nhif}`, and the dashboard's actions.

## Tests

`GovMocksTest`: GePG bill → signed control number callback → payment → reconciliation against a stand-in CIMS; forged
signatures, unbalanced bills and XML entities refused; NIDA rules; HESLB beneficiaries and disbursement; MUSE balance and
duplicates; TAMISEMI selections and outage; NHIF card rules, student registration and the cards it issues.
