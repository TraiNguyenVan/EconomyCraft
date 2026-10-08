# Decision: Agent switch generated files

- **Slug**: agent-switch-generated-files
- **Decided**: 2026-10-08
- **Verdict**: needs-clarification
- **Artifacts reviewed**: intake.md, research.md, problem.md, concept.md

## Scorecard

| Criterion | Rating | Justification |
|-----------|--------|---------------|
| Problem validity | adequate | The uncertainty about what switching changes is stated by the user, but recurring churn or lost configuration has not been demonstrated. |
| Evidence strength | weak | Research finds one deliberate OpenCode-to-Codex migration and no switch generator in the inspected paths; it has no example of a recurring switch or resulting configuration gap. |
| Value vs. inaction | unknown | No baseline exists for switch frequency, review overhead, or recovery from missing configuration. |
| Feasibility / appetite | adequate | The recommended concept is a small, bounded investigation, though the target operation and affected contributors remain unidentified. |
| Strategic fit | adequate | Understanding tracked project workflow and integration configuration is relevant to repository maintenance; no project-goal conflict was identified. |
| Risk posture | weak | The risk of ignoring needed shared configuration is recognized, but affected files and actual consequences have not been verified. |

## Verdict & Rationale

The revised concept appropriately recommends verifying the switch behavior before changing the workflow. The repository evidence shows a one-time OpenCode-to-Codex migration and scripts that read integration state to format command names; no switch-triggered generator was found in the inspected paths. Since recurring impact and missing configuration remain unconfirmed, evidence strength is weak and the idea does not meet the bar for `go`. Clarify the exact operation and collect a representative diff before deciding whether a project change is worthwhile.

## If needs-clarification

- **Blocking questions**:
  - [NEEDS CLARIFICATION: Which exact Spec Kit command or external operation is being used to switch integrations?]
  - [NEEDS CLARIFICATION: What files does that operation create, modify, or remove? Provide a representative before/after diff.]
  - [NEEDS CLARIFICATION: Are switch-related commits recurring and unwanted, or was the observed case the one-time migration in commit `5ffe325`?]
  - [NEEDS CLARIFICATION: Which changed files are required by contributors using other agent tools, and what evidence confirms that requirement?]
  - [NEEDS CLARIFICATION: Has a contributor experienced a missing configuration setting, and what review or recovery cost does switching cause?]
- **Revisit stage**: research; update define only if findings change the affected users, impact, or success measures, then decide again.
