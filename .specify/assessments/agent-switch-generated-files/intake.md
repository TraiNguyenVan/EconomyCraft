# Idea Intake: Agent switch generated files

- **Slug**: agent-switch-generated-files
- **Created**: 2026-10-08
- **Source**: pasted text
- **Type**: exploration

## Idea (as captured)

> how do we sovle this problem: developers use different kinds of agent, and every time they switch it create a commit, so do we need to add generated file by the switchh event to gitignore, thought i affraid this will create some config miss

## Restated

Developers use different agent tools, and switching between them results in generated files being committed. The idea raises whether those files should be ignored by Git and whether ignoring them could cause required configuration to be missed.

## Origin & Context

- **Raised by**: [NEEDS CLARIFICATION: who raised the idea]
- **Trigger**: Agent-tool switching produces commits containing generated files; the source and impact of these commits are [NEEDS CLARIFICATION].

## First-Glance Unknowns

- [NEEDS CLARIFICATION: Which agent tools are involved, and what files does each generate or modify?]
- [NEEDS CLARIFICATION: Are the commits automatic, or are developers committing the files manually?]
- [NEEDS CLARIFICATION: Which generated files contain required shared project configuration, and which are local or tool-specific?]
- [NEEDS CLARIFICATION: What does “config miss” mean in practice—missing settings for some agents, or configuration omitted from version control?]
- [NEEDS CLARIFICATION: How often does this happen, and what impact does it have on development or review?]
