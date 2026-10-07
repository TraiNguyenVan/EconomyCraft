# Feature Specification: Refresh Every Project Document

**Feature Branch**: `001-refresh-all-documents`

**Created**: 2026-10-07

**Status**: Draft

**Input**: User description: "update every single document here"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A server owner sets up the mod from the docs alone (Priority: P1)

A server owner reads `README.md` from scratch to decide whether EconomyCraft fits their server, then
follows it to install the mod, grant permissions, and configure prices. Every command, permission node,
configuration key and default value they rely on exists in the shipped mod and behaves as written.

**Why this priority**: `README.md` is the only document every consumer of this repository touches. Today
it documents defaults that contradict the shipped configuration file, so an owner who follows it verbatim
configures an economy that is not the economy they get. Correctness here is worth more than any new prose.

**Independent Test**: Can be fully tested by taking a clean server, configuring it using only `README.md`,
and checking each documented key, command and permission node against the running server. Delivers a server
that behaves as advertised with no further research.

**Acceptance Scenarios**:

1. **Given** a documented configuration key with a stated default, **When** that default is compared to the
   value shipped in the configuration file, **Then** the two agree, or the document states that the shipped
   default differs.
2. **Given** a documented command or permission node, **When** the server owner grants exactly that node,
   **Then** the documented capability becomes available, and no documented capability is reachable only
   through an undocumented node.
3. **Given** an owner reading the feature list, **When** they look for a feature that ships in the mod,
   **Then** they find it described somewhere in the documentation set.

---

### User Story 2 - A mod developer integrates against the API wiki (Priority: P2)

A developer building a companion mod reads the `wiki/` pages to find the API entry points, wire up event
listeners, and know what is public and what is internal. The wiki matches the API the shipped jar actually
exposes, and every page is reachable from the wiki index.

**Why this priority**: `wiki/` is the contract surface for third-party integrations and the most expensive
to get wrong. It is second only to the README because a wrong README misconfigures one server while a wrong
API page misleads an integrator.

**Independent Test**: Can be fully tested by writing a small integration against the shipped jar using only
the `wiki/` pages as reference, then compiling it without consulting the source tree.

**Acceptance Scenarios**:

1. **Given** any type named on an API wiki page, **When** an integrator imports it from the documented
   package, **Then** it resolves and is part of the supported public surface.
2. **Given** the wiki index, **When** a reader follows every link in it, **Then** every link resolves to an
   existing page.
3. **Given** any page in `wiki/`, **When** a reader looks for the equivalent entry point in the `README.md`,
   **Then** the two do not contradict each other.

---

### User Story 3 - A contributor knows the rules the project works by (Priority: P3)

A contributor picks up work on the project and wants to know what rules their change must obey, what has
already shipped, and why things are the way they are. They find those answers in the project's own
documents rather than by reverse-engineering a plan for a subsystem that finished months ago or by guessing.

**Why this priority**: Contributors are a small audience and gaps here mislead rather than block. But the
current state costs real time: the project constitution contains no principles at all, so the rules that are
in fact followed — server-side only, single-threaded API contract, tax must be burned rather than credited,
fiscal sources must be registered or they corrupt the leaderboards — are recorded only inside the plan of
one past effort. Delete that plan without relocating them and the reasoning is lost.

**Independent Test**: Can be fully tested by asking a new contributor to name three rules their change must
follow and to explain one past design decision, using only the project's documents.

**Acceptance Scenarios**:

1. **Given** a contributor about to change money handling, **When** they look for the rules that apply,
   **Then** they find them stated somewhere, and the rules match what the codebase actually enforces.
2. **Given** a file path cited inside a project document, **When** a reader opens that path, **Then** it
   resolves inside this repository.
3. **Given** a shipped change, **When** a reader consults the change log, **Then** they find it recorded
   exactly once, under a single current release section.
4. **Given** a reader looking for outstanding work, **When** they search the documentation, **Then** no
   document presents completed work as still to be done.

---

### Edge Cases

- What happens when documentation and code genuinely disagree about intended behavior? The code is treated
  as the source of truth and the documentation is corrected; no behavior is changed to match a document.
- What happens when a documented feature is configurable and ships disabled by default? It must be documented
  with that default stated, and must be reachable from the documentation index rather than only from a
  passing mention inside a longer paragraph.
- What happens when two documents describe the same key, command or rule with different values? They are
  reconciled against the code and reduced to one stated value, not left in disagreement.
- What happens to prose that is correct today but describes an internal development process, an
  implementation order, or a phase number that no reader outside the original author can act on?
- What happens when a wiki page is written for players and another for integrators, in different languages?
  Each page keeps its own audience; a page is not translated merely because a sibling page is.
- What happens when a documented value is generated or defaulted in more than one place, so that no single
  file can be called authoritative? The documentation must still state one value, and the ambiguity is
  recorded rather than silently resolved.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST bring every factual claim in the project documentation into agreement with the
  code as currently shipped, and MUST NOT change any runtime behavior as part of this work.
- **FR-002**: The system MUST treat the shipped configuration file and the registered commands, permission
  nodes and public API surface as the authority for all documented defaults, commands, permissions and types.
- **FR-003**: The system MUST correct every documented configuration key whose stated name or default value
  disagrees with the shipped configuration file.
- **FR-004**: The system MUST document every configuration key that ships in the configuration file and is
  absent from the documentation, including the keys for the quest, gossip and login-message sections.
- **FR-005**: The system MUST document every player-facing and administrator-facing command and permission
  node that ships but is absent from the documentation, and MUST remove any documented command, permission
  node or configuration key that no longer ships.
- **FR-006**: The system MUST ensure that every shipped player- or administrator-facing feature is described
  in at least one document, and that every feature, effect, limit or command described in the documentation
  is something the mod actually ships.
- **FR-007**: The system MUST make the documentation set internally consistent, so that no two documents state
  different values for the same key, command, permission, limit or rule.
- **FR-008**: The system MUST repair every broken internal link in the documentation, including links between
  documents, links to sections within a document, and links between the `README.md` and the `wiki/` index.
- **FR-009**: The system MUST correct every file path cited inside a project document so that it resolves
  inside this repository, and MUST remove or rewrite any citation that points outside the repository.
- **FR-010**: The system MUST correct typographic and spelling defects that impede understanding, including
  misspellings in headings and table headers.
- **FR-011**: The system MUST remove development-process language that is meaningless to a reader outside the
  original authoring effort, such as phase numbers, task identifiers and implementation ordering, from
  documents intended for consumers.
- **FR-012**: The system MUST record every shipped change exactly once in the change log, under a single
  current release section, rather than under several sections carrying the same release heading.
- **FR-013**: The system MUST NOT present completed work as outstanding in any document that remains after
  this work.
- **FR-014**: The system MUST bring both the documentation published to the project's users and the
  repository's tooling and process documentation into agreement with the project as it actually is. The
  published set comprises the top-level entry point, change log and wiki pages; the tooling and process set
  comprises the assistant command definitions, the specification-kit templates, and the project constitution.
- **FR-015**: The system MUST fill the project constitution with the principles, constraints and governance
  rules that the project actually works by, replacing the blank placeholder text, and MUST NOT invent a
  principle that the project does not already practise or that its code does not already enforce.
- **FR-016**: The system MUST review the assistant command definitions and the specification-kit templates
  and correct any that are internally inconsistent, incomplete or contradicted by the project's own workflow,
  while leaving their structure and section order intact.
- **FR-017**: The system MUST adopt the wiki's existing language split as a stated policy: pages written for
  players are written in Vietnamese, pages written for integrators are written in English, and each page is
  written in exactly one language. The system MUST bring any page that violates this into compliance and
  MUST record the policy where a reader will find it.
- **FR-018**: The system MUST delete the completed implementation-plan document, and MUST NOT carry its
  content into another document as live work. Its historical record already exists in the change log.
- **FR-019**: The system MUST preserve every design decision and constraint that the deleted plan document
  was the only remaining record of, by relocating it into the document where it is relevant, or by recording
  it in the constitution where it is a standing rule.

### Key Entities

- **Document**: A Markdown file the project maintains and publishes, identified by its repository path. Each
  document has a defined audience — prospective server owners, running server owners, third-party
  integrators, or contributors — which determines how much implementation detail it may carry.
- **Documented fact**: A single claim made by a document about the system, such as a configuration key, its
  default value, a command, a permission node, a public API type, a limit, or a behavioral rule. A fact may
  be stated by more than one document and must have exactly one agreed value.
- **Source of truth**: The shipped configuration file, the registered command set, the permission node set and
  the public API package. These, and not the documentation, decide whether a documented fact is correct.
- **Ground-truth change**: A correction that makes a documented fact agree with the source of truth. It is
  the only kind of change this feature produces.
- **Unverifiable claim**: A documented statement that cannot be checked against the source of truth, such as a
  design rationale or a future intention. Such a statement must not be silently rewritten into a verifiable
  one, and must not be used to justify a code change.
- **Release section**: A single heading in the change log under which all not-yet-released changes are
  recorded, so that a release never ships with changes split across several identically named sections.
- **Tracking document**: The document that records outstanding work. It is distinct from a plan document,
  which records the design and ordering of one past effort and becomes worthless once that effort ships.
- **Standing rule**: A constraint or principle the project applies to all future work, as opposed to a
  decision that applied to one feature. Standing rules belong in the constitution; feature decisions belong in
  the change log or the document for the feature they govern.
- **Audience language**: The language a page is written in, which follows from who the page is for. A
  player's page and an integrator's page are written in different languages by policy, and a page has exactly
  one.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of configuration keys named in the documentation match a key that actually ships, and
  100% of their stated defaults equal the shipped default.
- **SC-002**: 100% of commands, permission nodes and public API types named in the documentation resolve in
  the shipped mod, and 100% of the ones that ship under a documented audience appear in the documentation.
- **SC-003**: Every link in the documentation resolves, verified by checking each one; the count of broken
  links is zero.
- **SC-004**: Every file path cited in the documentation resolves inside the repository; the count of
  citations pointing outside the repository is zero.
- **SC-005**: A reviewer who knows the codebase finds no factual contradiction between the documentation and
  the code after the work completes.
- **SC-006**: The change log contains exactly one current release section, and no shipped change appears in
  more than one place within it.
- **SC-007**: No document in the repository presents completed work as outstanding; the completed plan
  document no longer exists, and no other document has inherited its unfinished-looking items.
- **SC-008**: No runtime behavior, configuration default, public API signature or permission node differs
  before and after the work; verified by the project's own test suite passing unchanged.
- **SC-009**: A new server owner can install, configure and permission the mod using only the documentation,
  without needing to read the source tree.
- **SC-010**: The project constitution contains no placeholder text and states at least one principle, one
  standing constraint and one governance rule that are each traceable to something the project practises.
- **SC-011**: Every page in the wiki is written in the single language its audience calls for, and the
  policy is stated in a document a contributor will actually read.
- **SC-012**: A reader who wants to know why a design is the way it is can find that reason either in the
  change log, in the constitution, or in the document for the feature it governs; the count of design
  decisions lost with the deleted plan document is zero.

## Assumptions

- "Every single document" is taken to mean both the documentation published to the project's users and the
  repository's tooling and process documentation. The assistant command definitions and the specification-kit
  templates are therefore in scope, as is the project constitution.
- The project constitution is in scope on the understanding that it is a genuine gap rather than a file the
  project intends to leave blank: it currently contains only placeholder text and states no principles.
- The specification-kit templates are shared scaffolding that tooling may regenerate from its own copy. They
  are in scope to be reviewed and corrected, but a change to one is only worth making where the project's own
  workflow is actually affected, since a later toolkit upgrade may overwrite it. This is called out because it
  is the one place in this feature where the work may not stick.
- The code, not the documentation, is correct wherever the two disagree. No runtime behavior, default value
  or API signature will be changed to make a document true.
- The mod's target audience is server owners and third-party integrators; documentation is written for them
  rather than for the original authors.
- The wiki remains Markdown files under `wiki/`, rendered by the wiki hosting the repository, so no change of
  documentation tool or hosting is implied.
- The existing split between Vietnamese player-facing pages and English integrator-facing pages is retained
  as deliberate policy rather than treated as drift, per FR-017. The audit found a recorded decision behind
  it, so the default is to preserve it and only fix the pages that break their own rule.
- The completed implementation plan is deleted rather than archived, per FR-018. Anything in it that is still
  the only record of a design decision is relocated before deletion, per FR-019; anything that merely records
  what shipped is already covered by the change log.
- The `README.md` continues to serve as the single entry point, with the `wiki/` set covering detail that
  would otherwise bloat it.
- A defect that cannot be corrected by editing documentation alone — for example a shipped default that
  contradicts the project's own stated identity — is recorded and reported rather than silently worked around,
  because fixing it would require a code change that FR-001 forbids.
- Findings for the planning phase, with file and line evidence, are recorded in
  [`drift-inventory.md`](drift-inventory.md) in this directory.
- Verified numeric defaults, such as tax rates and multipliers, will be taken from the shipped configuration
  file as read at the time of the work, and re-checked if the configuration file changes before completion.
- The repository's existing licence, upstream attribution and fork statement are correct and are preserved.