# Security

## Reporting

Use GitHub's private vulnerability reporting for the public repository. If that
channel is unavailable, do not open a public issue containing exploit details;
wait for a private contact method to be listed by the repository owner.

Security reports should include the Refrator version, Android version, affected
capability and a minimal reproduction without real credentials.

Do not attach API keys, cookies, OTP values, private prompts or unredacted chat
exports. Use the in-app sanitized diagnostic export.

Only the latest released version receives security fixes during the 1.x
development line.

## Boundaries

- Provider credentials are stored through Android Keystore-backed encryption.
- Tool calls pass through schema validation, policy and authorization.
- Browser content is untrusted input and cannot override system instructions.
- Python and LiteLLM run in dedicated app processes, but share the application
  UID and are not a sandbox against malicious native packages.
- Shizuku grants shell-level capabilities, not root.
- Protected screens, biometrics and Android permission dialogs are not bypassed.

## Release material

Release keystores, passwords and upload credentials must remain outside the
repository. Public artifacts include a checksum and dependency inventory.
