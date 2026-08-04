# Security Policy

## Project Status

JuLC is an experimental project and should **not** be used in production or with real funds on Cardano mainnet.

## Reporting a Vulnerability

If you discover a security vulnerability in JuLC, please report it responsibly:

1. **Do not** open a public GitHub issue for security vulnerabilities
2. Email your report to **security at bloxbean.com**
3. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact (e.g., could produce incorrect UPLC, fund loss risk)
   - Suggested fix (if any)

## Scope

Security-relevant issues include:

- **Compiler correctness**: Bugs that cause the compiler to generate silently wrong UPLC (validators that accept transactions they should reject, or vice versa)
- **Serialization**: FLAT or CBOR encoding/decoding bugs that produce invalid on-chain representations
- **Fund safety**: Any scenario where compiled validators could lead to locked or stolen funds
- **Supply chain**: Dependency vulnerabilities that affect compiled output

Out of scope:

- Off-chain stub behavior divergences (documented in the Known Limitations)
- Performance issues (execution budget consumption)
- Feature requests

## Known Limitations

JuLC has documented compiler limitations that can produce incorrect UPLC in specific scenarios. These are tracked in the [Compiler Limitations](https://julc.dev/getting-started/#16-compiler-limitations) documentation and are not considered security vulnerabilities unless they affect fund safety in undocumented ways.
