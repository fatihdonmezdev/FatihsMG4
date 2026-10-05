# Local MG4 signing tools

Place the two DriveHub platform-signing files in this directory:

- `platform.pk8`
- `platform.x509.pem`

They sign the unstable vehicle APK described in `../OTA.md`. The expected
certificate SHA-256 is
`c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`.

The signing files are intentionally ignored by Git. Do not publish the private
key; release APKs remain reproducible locally from this checkout.
