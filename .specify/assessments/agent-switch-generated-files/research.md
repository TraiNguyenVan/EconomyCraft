# Idea Research: Agent switch generated files

- **Slug**: agent-switch-generated-files
- **Created**: 2026-10-08
- **Evidence confidence (overall)**: low

## Users & Demand

- The intake reports that developers switch agent tools and that switching results in generated files being committed. This remains a single stakeholder statement; no support tickets, interviews, or usage data corroborate frequency or impact. [source: intake.md] (confidence: low, cited)
- Repository history shows one project workflow migration from OpenCode to Codex in commit `5ffe325`. It establishes that a migration occurred, but not that normal integration switches repeatedly generate commits. [source: git commit `5ffe325`] (confidence: high, cited)

## Prior Art

- Before `5ffe325`, `.specify/integration.json` recorded `opencode` as the installed and default integration with `.` as its invocation separator; after the commit it records `codex` with `-`. The file is tracked. [source: `.specify/integration.json` in parent and commit `5ffe325`] (confidence: high, cited)
- The same migration renamed OpenCode command files into `.agents/skills/`, added `.specify/integrations/codex.manifest.json`, removed `.specify/integrations/opencode.manifest.json`, and modified shared Spec Kit scripts and templates. These are project workflow changes associated with a one-time migration, not evidence of a routine switch operation generating the same changes each time. [source: `git show --name-status 5ffe325`; commit diff] (confidence: high, cited)
- In the inspected `.specify/scripts/bash/common.sh` and `.specify/scripts/python/common.py`, integration state is read to select the command invocation separator (`.` or `-`). Repository search of `.specify/extensions/`, `.specify/scripts/`, and `.agents/skills/` found no integration-switch command that generates or rewrites integration files. This is bounded to those inspected paths; another tool or an external Spec Kit operation could perform generation. [source: `.specify/scripts/bash/common.sh`, `get_invoke_separator`; `.specify/scripts/python/common.py`, integration separator resolution; repository search] (confidence: medium, cited)
- Current repository state tracks `.agents/skills/`, `AGENTS.md`, `.specify/integration.json`, and `.specify/integrations/codex.manifest.json`, while `.gitignore` ignores `.claude/`. This is a mixed tracking policy; the repository does not explain the rationale for ignoring `.claude/` or identify its contents. [source: `.gitignore`; `git ls-files`] (confidence: high, cited)
- No earlier assessment or decision about generated agent files was found in the available `.specify/assessments/` records. [source: repository search] (confidence: medium, cited)

## Market & Context

- The internal evidence shows a migration that changed both integration-specific and shared workflow files. It does not establish what contributors currently do for routine switching, or which files they consider unnecessary churn. [source: git commit `5ffe325`; intake.md] (confidence: low, cited)
- No external sources were consulted. The cost of doing nothing is unmeasured: the report could reflect recurring review overhead, a one-time migration, or a misunderstanding of which operation creates the files. [source: intake.md; repository evidence above] (confidence: low, cited)

## Data & Constraints

- `.specify/integration.json` records the installed/default integration and integration settings; inspected shared scripts use the setting to format command names. This file participates in command behavior and should not be assumed disposable based only on being integration-related. [source: `.specify/integration.json`; `.specify/scripts/bash/common.sh`; `.specify/scripts/python/common.py`] (confidence: high, cited)
- Commit `5ffe325` changed many files, including Spec Kit workflow definitions and feature documentation. Its size cannot be used as a measure of recurring generated-file churn. [source: `git show --stat 5ffe325`] (confidence: high, cited)
- No quantitative data is available for developers affected, routine switches, resulting commits, review time, or configuration failures. [NEEDS CLARIFICATION: gather representative switch diffs and frequency/impact data]

## Evidence Against the Idea

- The only concrete switch-related repository evidence is a deliberate OpenCode-to-Codex migration commit that changes useful, tracked project workflow assets. It does not show recurring unwanted generation; broad ignore rules could hide configuration contributors need. [source: git commit `5ffe325`; `.specify/integration.json`; tracked-file list] (confidence: medium, cited)
- The inspected repository scripts read the active integration to format command names, but no switch-triggered generator was found in those paths. The reported behavior may be triggered by a different command or external tool, so the target of a change is currently uncertain. [source: `.specify/scripts/bash/common.sh`; `.specify/scripts/python/common.py`; repository search] (confidence: medium, cited)

## Gaps & Open Questions

- [NEEDS CLARIFICATION: Which exact Spec Kit command or external operation is used to switch integrations?]
- [NEEDS CLARIFICATION: What exact files does that operation create, modify, or remove? Provide a before/after diff if possible.]
- [NEEDS CLARIFICATION: Are the resulting commits recurring and unwanted, or is the observed case the one-time migration in commit `5ffe325`?]
- [NEEDS CLARIFICATION: Which changed files are needed by contributors using other agent tools, and how has that need been verified?]
- [NEEDS CLARIFICATION: Has ignoring or omitting any of these files actually caused a configuration gap?]
- [NEEDS CLARIFICATION: How many developers/switches are affected, and what review or recovery cost results?]

## Sources

- `intake.md` (host: local repository, policy: not applicable)
- Git commit `5ffe325` and its parent — “chore(speckit): migrate project workflow to Codex” (host: local repository, policy: not applicable)
- `.specify/integration.json` (host: local repository, policy: not applicable)
- `.specify/scripts/bash/common.sh`, `.specify/scripts/python/common.py`, and repository search across `.specify/extensions/`, `.specify/scripts/`, and `.agents/skills/` (host: local repository, policy: not applicable)
- `.gitignore` and tracked-file list from `git ls-files` (host: local repository, policy: not applicable)
