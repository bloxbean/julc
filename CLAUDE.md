# CLAUDE.md — JuLC

@AGENTS.md

## Claude Code

For substantial changes:

- Use Plan Mode before editing when reviewing a new ADR or architectural change.
- Verify ADR assumptions against the repository before implementation.
- Treat an accepted ADR as the design contract.
- If repository reality materially contradicts the ADR, report the conflict rather than silently redesigning.
- Implement large changes in bounded milestones.
- Run targeted tests during each milestone and broaden validation based on impact.
- Review the final diff against the ADR before declaring completion.

When reporting completion include:

1. milestone completed
2. modules/files changed
3. tests and commands run
4. semantic behavior verified
5. unresolved issues or ADR deviations
6. recommended independent-review focus
