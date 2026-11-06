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
| **Payment aggregators** | FR-FIN-03/04 | Selcom (signed checkout API), AzamPay (token, MNO checkout, callback) and ClickPesa (token, USSD push, status): phone prompts paid after a few seconds, notifications to the institution |
| **NHIF** | FR-INT-10 | Student Registration API v2: agent token, student registration, card applications with control numbers, card checks, intakes; calls CIMS back on payment and card issue |

GePG and NHIF follow their specifications' message shapes and error codes (NHIF: *Student Registration API Documentation*, v2).
**The HESLB, NIDA, MUSE and TAMISEMI paths and fields are placeholders** until each body issues the institution its interface document; CIMS reads every path from configuration
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
| `MOCK_HESLB_API_KEY`, `MOCK_NIDA_API_KEY`, `MOCK_MUSE_API_KEY`, `MOCK_TAMISEMI_API_KEY` | `mock-…-key` | Keys CIMS must send in `X-Api-Key` (the `gov-mocks` profile sends these) |
| `MOCK_NHIF_CLIENT_ID`, `MOCK_NHIF_CLIENT_SECRET` | `mock-agent`, `mock-secret` | NHIF registration agent credentials for `/nhif/connect/token` (enter them in CIMS › Integrations › NHIF with SystemId 1, SubSystemId 2) |

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
### NHIF Student Registration API (v2)

Token `POST /nhif/connect/token` (form: `client_id`, `client_secret`, `grant_type=client_credentials`, `scope`); every call
then sends `Authorization: Bearer`, `SystemId` and `SubSystemId` (required on `/student/register`, error 4001/4002 without).
Answers are NHIF's `BulkOperationResult` (`succeeded`, `failed[index, identifier, errors[code, field, message]]`,
`totalRequested`, `hasFailures`, `isFullSuccess`), with HTTP 400 when anything failed.

| Path | Rules |
|---|---|
| `POST /nhif/api/v2/student/register`, `/students/register` | No date of birth → 2070; Form Four index not `S0101-0001-2023` → 2113; admission number ending in 7 → 2111 (already a member) |
| `POST /nhif/api/v2/student/cardapplication` | All-or-nothing. Batch number not `id/startYear/endYear/serial` → 2124; category 1 without a NIDA number → 2131, or one not registered → 3020; category 2 card not starting `SN` → 2139; repeated correlation id → 3017. Otherwise each student gets a control number (995…) for TZS 50,400 |
| `GET /nhif/api/v2/studentcontrolnobybatchno?batchNo` | The batch's control numbers |
| `GET /nhif/api/v2/verifystudentcard?cardNo` | Card ending in 0 → failed 2137; 9 → INACTIVE; 8 → ACTIVE held by NEEMA JOHN KIMARO; otherwise ACTIVE. Cards issued here answer with the holder; a registered NIDA number answers too |
| `GET /nhif/api/v2/agentintakes` | October and March intakes of the current academic year |
| `POST /nhif/api/v2/student/changerequest` | Accepted; an `Approved` change-request callback follows |

Callbacks: enter the institution's callback URL, token URL and callback credentials (CIMS › Integrations › NHIF › Callback
credentials) on the dashboard. The mock then gets a token and calls CIMS back on each card application (control number),
**Mark paid** (payment) and **Issue card** (card number), as NHIF does. Without them the callbacks are listed but not sent.

### Payment aggregators (Selcom, AzamPay, ClickPesa)

Enter the merchant credentials shown on the dashboard (tab **Aggregators**) in CIMS › Integrations › Mobile money, with the API
addresses `http://localhost:8098/selcom/v1`, `http://localhost:8098/azampay` (authenticator `http://localhost:8098/azampay-auth`) or
`http://localhost:8098/clickpesa`. A phone prompt is paid `MOCK_AGGREGATORS_COMPLETE_SECONDS` (5) seconds after it is sent, except to
a phone ending in 9 ("insufficient balance"). Selcom checks every call's digest and notifies the address CIMS sends with each order
(signed); for AzamPay and ClickPesa, paste CIMS's notification address on the dashboard.

| Path | What it does |
|---|---|
| `POST /selcom/v1/checkout/create-order-minimal`, `/wallet-payment`; `GET /checkout/order-status` | Signed (`Authorization: SELCOM …`, `Digest`, `Signed-Fields`, `Timestamp`); order, phone prompt, status |
| `POST /azampay-auth/AppRegistration/GenerateToken`; `POST /azampay/azampay/mno/checkout` | Token; prompt with `provider` Mpesa, Tigo, Airtel, Halopesa or Azampesa |
| `POST /clickpesa/third-parties/generate-token`, `/payments/initiate-ussd-push-request`; `GET /payments/{orderReference}` | Token (`client-id`, `api-key`); prompt; status |

The institution code is CIMS's NACTVET registration number for the institution (e.g. `REG/DEMO/001`) unless another code
is entered under Integrations.

Developer endpoints: `GET /mock/status`, `POST /mock/outage?system=GEPG&on=true` (every call to that system answers 503),
`GET /mock/{gepg|heslb|nida|muse|tamisemi|nhif}`, and the dashboard's actions.

## Tests

`GovMocksTest`: GePG bill → signed control number callback → payment → reconciliation against a stand-in CIMS; forged
signatures, unbalanced bills and XML entities refused; NIDA rules; HESLB beneficiaries and disbursement; MUSE balance and
duplicates; TAMISEMI selections and outage; NHIF token, registration, all-or-nothing card application, control numbers and the callbacks for payment and card issue.
