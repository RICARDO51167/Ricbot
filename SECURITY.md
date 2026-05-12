# Security Policy

## Supported Usage

Ricbot is designed to run as a local or privately deployed agent runtime. Treat enabled tools and channels as privileged capabilities.

## Secrets

- Prefer environment variable placeholders such as `${RICBOT_API_KEY}` over literal secrets in config files.
- When Ricbot writes a config file on POSIX file systems, it attempts to set owner-only permissions (`0600`).
- Do not commit `workspace/`, `.ricbot/`, logs, tokens, or generated runtime state.

## Network Exposure

- Binding the OpenAI-compatible API to a non-loopback host requires `api.bearer_token`.
- Keep `allow_from` restrictive for chat channels. Use `["*"]` only for trusted local testing.
- Web and exec tools include SSRF/path checks, but enabling them still grants broad local capability.

## Reporting

If you find a vulnerability, report it privately to the project maintainer instead of opening a public issue with exploit details.
