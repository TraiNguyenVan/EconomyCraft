# Spec Kit contributor notes

Spec-driven work lives in `specs/<NNN>-<short-name>/`. Feature numbering is sequential. The project
constitution is `.specify/memory/constitution.md`; it states normative principles, while `AGENTS.md` and
this guide capture working mechanics.

## Command definitions

There are 10 command definitions in `.opencode/commands/`. If one changes, update its SHA-256 in
`.specify/integrations/opencode.manifest.json` in the same change. Files under `.specify/templates/` are
regenerated during Spec Kit upgrades; make durable workflow changes in the command definition.

Template content is a minimum, not a replacement shape. A command that overwrites an existing document from
a template must compare the result with the prior document and fail if existing content would be silently
lost.
