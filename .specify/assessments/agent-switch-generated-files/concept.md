# Concept: Agent switch generated files

- **Slug**: agent-switch-generated-files
- **Created**: 2026-10-08
- **Recommended option**: Option A — Verify the switch behavior

## Options

### Option A — Verify the switch behavior
- **Sketch**: Establish what contributors mean by switching agent tools or Spec Kit integrations, which operation they run, what repository changes follow, and whether those changes recur. Use the findings to decide whether there is a real workflow problem and whether any files are required by other contributors.
- **Appetite**: small (days) as an initial investigation; the time needed to observe recurring behavior is uncertain.
- **Trade-offs**: Addresses the largest evidence gap without risking needed configuration. It does not immediately reduce any real commit churn contributors may be experiencing.
- **Rabbit holes**: Expanding the investigation to every available agent product; treating the historical one-time OpenCode-to-Codex migration as representative of normal switches; collecting examples without measuring frequency or impact.

### Option B — Define a shared switching experience
- **Sketch**: If investigation confirms a recurring issue, make switching among the tools contributors use predictable, with clear expectations about shared project instructions and integration settings that remain available.
- **Appetite**: medium (weeks), with high uncertainty until the supported tools and changed files are identified.
- **Trade-offs**: Could reduce repeated review overhead and configuration gaps across regular users. Requires ongoing maintenance as supported tools and their conventions change.
- **Rabbit holes**: Supporting tools nobody uses; treating all tool-specific configuration alike; broadening into a general agent-configuration framework.

### Option C — Make no workflow changes
- **Sketch**: Keep the current repository workflow and close the issue if the reported behavior cannot be reproduced or shown to create meaningful recurring cost.
- **Appetite**: small (days) to confirm the available evidence; reassessment may take longer if more observations are needed.
- **Trade-offs**: Avoids spending effort on a problem that may have been a single migration. Any genuine recurring churn or configuration confusion remains unresolved.
- **Rabbit holes**: Reopening the issue without new evidence; accepting anecdotes as proof of recurring impact.

## Recommendation

Recommend **Option A — Verify the switch behavior**. The current evidence supports a one-time OpenCode-to-Codex migration, but does not establish a recurring switch-triggered generator, unwanted commits, or missing configuration. A bounded investigation can answer the problem definition’s success metrics before any broader workflow change is considered. If no recurring cost or configuration gap is found, choose Option C; if one is confirmed, revisit Option B with that evidence.

## Out of Scope (for the recommended option)

- Selecting or mandating an agent tool or Spec Kit integration.
- Defining ignore rules, file layouts, migration behavior, or an implementation plan.
- Treating all integration-specific or generated files as disposable.
- Changing application behavior or EconomyCraft gameplay configuration.
- Supporting additional agent tools before contributors establish a need for them.

## Assumptions to Validate

- Contributors can identify or reproduce the operation they call an agent or Spec Kit integration switch.
- Representative changes and their effect on other contributors can be observed.
- A bounded investigation can distinguish a recurring issue from a one-time migration.
