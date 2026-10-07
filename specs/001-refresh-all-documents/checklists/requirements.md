# Specification Quality Checklist: Refresh Every Project Document

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-07
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

### Validation iteration 1 — issues found and corrected

- **"Scope is clearly bounded" FAILED on first pass.** The feature description ("update every single
  document here") does not state which documents are meant, and the repository contains documentation
  intended for tooling and process as well as documentation published to users. Scope was therefore
  narrowed in the Assumptions section to the project's own published documents, with the boundary raised
  explicitly as FR-014 for confirmation.
- **"No implementation details leak into specification" FAILED on first pass.** The draft of the Key
  Entities section named concrete repository paths as if they were part of the requirement. This was
  rewritten so that requirements speak of "the documentation set", "the shipped configuration file" and
  "the documentation index" rather than specific paths; paths were moved into the Assumptions section.
- **"Success criteria are technology-agnostic" FAILED on first pass.** An initial criterion measured the
  build and test tooling. It was replaced with SC-008, which states the outcome — no observable behavior
  differs — without naming the tooling used to prove it.
- **Section heading typo corrected.** The Success Criteria heading was written as `*(mandable)*` and was
  restored to the mandatory section label used by the template.

### Validation iteration 2 — after the drift audit

A read-only audit of the documentation against the code was run to test whether the specification actually
covers what is wrong. It did, and it also changed two of the three open questions. Full evidence with
`file:line` citations is in [`../drift-inventory.md`](../drift-inventory.md).

Adjustments made to the specification as a result:

- **FR-006 was broadened.** The first wording only checked that documented features exist. The audit found
  the reverse failure mode too: `wiki/Factions.md` advertises a Communism subsidy buff that the project's own
  decision record marks "read-only, not implemented". The requirement now covers effects and limits as well
  as features, in both directions.
- **FR-015 was reframed.** The audit found the Vietnamese/English split is recorded as a deliberate decision
  in the project's decision log, not an accident. The clarification now asks whether to confirm that policy,
  make the set single-language, or go bilingual, instead of assuming the mix is a defect.
- **FR-016 was strengthened with evidence.** The tracking document is not merely finished — it also contains
  roughly 162 lines of byte-identical duplication, two copies of several phase headings, and two task
  descriptions that contradict each other. The clarification now states this so the choice is informed.
- **FR-014 was made concrete.** The tooling set is named as eleven command definitions plus the specification
  templates, and it is noted that the project constitution is a blank template with no principles in it.
- **A new assumption was added** covering defects that documentation alone cannot fix: they get reported, not
  silently worked around, because FR-001 forbids the code change that would be required.

### Validation iteration 3 — final, all items pass

All three open clarifications were answered, and each answer changed the specification rather than merely
closing a question:

- **FR-014 → scope widened to both sets.** Published documentation *and* tooling/process documentation are
  now explicitly in scope. This added FR-015 (fill the constitution), FR-016 (review command definitions and
  templates) and SC-010.
- **FR-015 → constitution is a deliverable.** The constitution is a blank template, so "update every
  document" now means *authoring* its principles rather than correcting them. FR-015 requires each principle
  to be traceable to something the project already practises, which prevents the constitution from becoming
  a list of aspirations the codebase does not honour.
- **FR-016 → command definitions and templates reviewed.** SC-012 and an assumption record the one risk in
  this scope: shared toolkit files may be regenerated, so work there may not persist.
- **FR-015 (language) → split adopted as policy.** The Vietnamese-player / English-integrator split is now a
  stated rule (FR-017) with a matching criterion (SC-011), rather than an open question or an unexamined
  inconsistency.
- **FR-016 (tracking document) → deleted, with a safeguard.** FR-018 deletes the completed plan; FR-019
  requires relocating any design decision it is the last remaining record of before deletion. SC-012 makes
  the loss count explicitly zero. Without FR-019 the deletion would silently destroy the only record of
  several enforced rules — server-side only, the single-threaded API contract, burning tax rather than
  crediting it, registering fiscal sources — which is what User Story 3 now tests for.

User Story 3 was rewritten as a direct consequence. It previously asked a contributor to find outstanding
work; with the tracking document deleted, that question has no answer, so the story now asks what it should
have asked all along: what rules must my change obey, and why is this designed this way.

| Item | Result |
|---|---|
| No implementation details (languages, frameworks, APIs) | Pass |
| Focused on user value and business needs | Pass |
| Written for non-technical stakeholders | Pass |
| All mandatory sections completed | Pass |
| No [NEEDS CLARIFICATION] markers remain | Pass — zero |
| Requirements are testable and unambiguous | Pass — 19 requirements, each naming a class of claim and a verifiable condition |
| Success criteria are measurable | Pass — 12 criteria |
| Success criteria are technology-agnostic | Pass |
| All acceptance scenarios are defined | Pass — 3 stories, 11 scenarios |
| Edge cases are identified | Pass — 7 edge cases |
| Scope is clearly bounded | Pass — bounded by FR-014, both sets enumerated |
| Dependencies and assumptions identified | Pass — 15 assumptions |
| All functional requirements have clear acceptance criteria | Pass |
| User scenarios cover primary flows | Pass — server owner, integrator, contributor |
| Feature meets measurable outcomes defined in Success Criteria | Pass |
| No implementation details leak into specification | Pass |

### Deferred issue found by the audit — since resolved, no action needed

The audit initially flagged the shipped login message as pointing players at a "third repository identity"
that contradicted the README, implying a change to shipped configuration. `git remote -v` disproves this:
`origin` is `TraiNguyenVan/EconomyCraft` and `upstream` is `PhilipB06/EconomyCraft`. The MOTD default is
correct as shipped, and the README's upstream *wiki* link is the actual defect. This was recorded as an
out-of-scope blocker and is now withdrawn — no configuration change is required, and FR-001 is unaffected.

The investigation was still worth doing: it produced the canonical wiki URL that every documentation link
must now use, and it established that `1.10.0` is unreleased (`git tag` ends at `1.9.0`).

### Carried into planning

- [`../drift-inventory.md`](../drift-inventory.md) — the evidence base, with `file:line` citations for every
  finding. Planning should work from this rather than re-deriving it.
- The shipped default model identifier for the gossip feature could not be confirmed to exist. Documentation
  may record the value; whether the value is correct is a code question and belongs outside this feature.
- `TODO.md` deletion is a planned task, not a completed one. The file is still present in the repository and
  is untouched by this specification phase.