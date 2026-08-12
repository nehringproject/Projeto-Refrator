# Privacy

Refrator is bring-your-own-provider software. Conversation content is sent only
to the provider selected for that request or to a configured LiteLLM route.
Local models do not require a remote provider.

The app does not intentionally collect advertising identifiers or sell data.
Sensitive fields, authorization headers, cookies, API keys and OTP values are
redacted before remote diagnostic or model context is produced.

Device automation, notifications, app visibility, storage access and screen
capture remain disabled until the corresponding Android permission and the
in-app authorization are both granted.

Diagnostic exports omit secrets by default. Users should still inspect an
export before sharing it.
