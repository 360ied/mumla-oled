# Murmur interop test PKI (step 1 harness)

These are **test-only, loopback-only** credentials for
`libraries/humla/src/test/java/se/lublin/humla/interop/`. They are never
trusted by production code and must never be used outside `127.0.0.1`.

- `test-cert.pem`: self-signed leaf, CN `127.0.0.1`,
  SAN `IP:127.0.0.1` + `DNS:localhost`, 10-year validity (2026-11-10 to
  2036-11-07). The client test pins it under the `127.0.0.1` hostname alias.
- `test-key.pem`: matching private key (unencrypted PKCS#8). A committed
  test key is compromised by design — reuse outside loopback is a finding,
  not a use case.

Regenerate both together, from this directory
(`libraries/humla/src/test/resources/murmur`), with:

```bash
nix run nixpkgs#openssl -- req -x509 -newkey rsa:2048 \
  -keyout test-key.pem -out test-cert.pem -days 3650 -nodes \
  -subj "/CN=127.0.0.1" \
  -addext "subjectAltName=IP:127.0.0.1,DNS:localhost"
```

Rotation = regenerate, replace both files, keep this note accurate.
Secret scanners will flag `test-key.pem`; that is expected — see above.

Audit note: a sensitive-data scan flags the committed private key
and the loopback address strings in this directory. Both are
intentional test fixtures: the key is self-signed for loopback only
and compromised by design, and the test-server credentials in
`MurmurTestServer` are ephemeral values for the throwaway per-test
server only. No production secrets, personal data, or hardware
identifiers are committed here.
