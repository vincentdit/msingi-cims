# Development keys — TEST ONLY

These key pairs exist only so CIMS's live GePG adapter and the `gov-mocks` GePG stand-in can sign and verify each other's
messages on a developer's computer. They were generated with `keytool`, protect nothing, and must never be used with the
real GePG or on a server.

| File | What it is | Used by |
|---|---|---|
| `cims-sp-test.p12` | CIMS's signing key (alias `cims-sp`) | CIMS (`CIMS_GEPG_KEYSTORE`) |
| `cims-sp-test.crt` | CIMS's certificate | gov-mocks, to check CIMS's signatures |
| `gepg-mock-test.p12` | The mock GePG's signing key (alias `gepg`) | gov-mocks |
| `gepg-mock-test.crt` | The mock GePG's certificate | CIMS (`CIMS_GEPG_CERTIFICATE`), to check GePG's signatures |

Keystore password: `test-only-not-secret`.

For production, the institution (or Msingi as the system integrator) generates its own key pair, sends the certificate to
GePG during on-boarding, and receives GePG's public certificate. Both live in the server's secret store, not in this
repository.

To make a fresh pair:

```
keytool -genkeypair -alias cims-sp -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=CIMS SP TEST ONLY, C=TZ" \
  -keystore cims-sp-test.p12 -storetype PKCS12 -storepass test-only-not-secret -keypass test-only-not-secret
keytool -exportcert -alias cims-sp -keystore cims-sp-test.p12 -storepass test-only-not-secret -file cims-sp-test.crt
```
