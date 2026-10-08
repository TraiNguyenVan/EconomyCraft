# Problem Definition: Agent switch generated files

- **Slug**: agent-switch-generated-files
- **Created**: 2026-10-08
- **Inputs used**: intake.md, updated research.md, user clarification

## Problem Statement

Developers switching agent tools or Spec Kit integrations may encounter repository changes and are unsure which files the switch operation creates or changes, and which are needed by other contributors. The repository confirms a one-time OpenCode-to-Codex migration but does not establish recurring switch-generated changes, configuration loss, or the cost of the reported commits, so the current problem is uncertainty about the behavior and impact of switching rather than a verified recurring defect.

## Affected Users & Stakeholders

- **Users**: Developers changing agent tools or Spec Kit integrations — may need to review changed files and determine whether shared instructions or integration settings are affected. [source: intake.md; updated research.md]
- **Stakeholders**: Repository/workflow maintainers — need to understand which tracked workflow files support project contributors and whether migration changes are intentional. [source: tracked `.agents/skills/`, `AGENTS.md`, `.specify/integration.json`, and `.specify/integrations/codex.manifest.json`]
- **Stakeholders**: Contributors using the repository’s agent and Spec Kit workflow — may be affected if required shared configuration is missing or inconsistent. [NEEDS CLARIFICATION: identify contributors and any observed configuration failures]

## Goals

- Establish what the reported integration-switch operation changes and whether those changes recur.
- Distinguish observed switch-related churn from project workflow changes that are intentionally shared.
- Determine whether contributors have experienced missing configuration and whether the problem warrants further work.

## Non-Goals

- Choosing or mandating an agent tool or Spec Kit integration.
- Defining ignore rules, file layouts, migration behavior, or an implementation plan.
- Treating all integration-specific or generated files as disposable.
- Changing application behavior or EconomyCraft gameplay configuration.

## Success Metrics

- A representative switch operation is documented with the files it creates, modifies, or removes and whether each is needed by other contributors (baseline: unknown).
- Frequency of switch-related commits and review effort is established over an agreed period (baseline: unknown).
- Number of confirmed cases where a contributor lacked required configuration after a switch is established (baseline: no cases documented in the available evidence).

## Cost of Inaction

If routine switching does create repeated changes, contributors may continue reviewing unclear diffs and may be uncertain whether to retain integration configuration. If the only evidence is a one-time migration, a broader policy change may solve no recurring problem. Current evidence does not quantify either cost: commit `5ffe325` shows a deliberate OpenCode-to-Codex migration, while inspected scripts use integration state to format command names and no switch generator was found in the inspected repository paths. [source: updated research.md]

## Open Questions

- [NEEDS CLARIFICATION: Which exact Spec Kit command or external operation is used to switch integrations?]
- [NEEDS CLARIFICATION: What files does that operation create, modify, or remove? Provide a representative before/after diff.]
- [NEEDS CLARIFICATION: Are resulting commits recurring and unwanted, or was the observed case the one-time migration in commit `5ffe325`?]
- [NEEDS CLARIFICATION: Which changed files are needed by contributors using other tools, and how has that need been verified?]
- [NEEDS CLARIFICATION: Has a contributor actually lost or missed required configuration?]
- [NEEDS CLARIFICATION: How frequently does this occur and what review or recovery cost does it cause?]
