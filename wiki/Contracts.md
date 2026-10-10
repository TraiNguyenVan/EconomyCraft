# Contracts

Players can post paid work for each other. The requester reserves the full reward in escrow up
front, one contractor accepts the job, submits the finished work, and the requester approves to
release the payment. Contracts can be public (anyone may accept) or targeted at one player.

Open the board with `/contracts` or `/eco contracts`. It also appears in the `/eco` menu.

## Posting work

Use `/contracts new` (or the **New Contract** button) and follow the steps: visibility, optional
target player, category, title, details, reward, and deadline. A confirmation screen shows the
exact terms before anything is reserved. The full reward leaves your balance immediately and is
held in escrow until the contract ends.

`/contracts mine` lists the contracts you posted and the ones you accepted, with their current
state and deadlines.

## Doing work

Accept a contract from the board or with `/contracts accept <id>`. Only one player can hold a
contract; targeted contracts can only be accepted by their target. When the work is done, submit it
with `/contracts submit <id> [notes]` so the requester can review it.

## Review

The requester reviews each submission from the contract screen:

- **Approve and Pay** releases the escrow to the contractor, minus any configured tax.
- **Request Revision** sends the work back with feedback. Each revision grants extra work time, and
  the number of revisions is limited by the server.
- Ignoring a submission is still a decision: after the server's review window it approves
  automatically.

The same steps work as commands: `/contracts approve <id>` and `/contracts revise <id> <reason>`.

## Cancelling, expiring, and disputes

- Cancelling an open contract refunds the escrow at once. After acceptance, cancellation needs both
  sides: the first side proposes, the other confirms from the contract screen or with
  `/contracts cancel <id>`.
- A contract past its work deadline expires and refunds the requester.
- Either side can dispute submitted work — or work that ran out of revisions — with
  `/contracts dispute <id> <reason>`. A dispute freezes the contract until an administrator
  resolves it.

## For server owners

- Enable with `contracts_enabled`. Limits and timings: `max_contract_reward`,
  `contract_default_duration_hours`, `contract_max_duration_hours`, `contract_review_hours`,
  `contract_max_revisions`, `contract_revision_extension_hours`, and
  `max_active_contracts_per_player` (`0` = unlimited).
- Permission nodes: `economycraft.command.contracts` for players,
  `economycraft.admin.contracts` for dispute resolution.
- Admin commands: `/contracts admin list [status]` shows live and finished contracts;
  `/contracts admin resolve <id> pay|refund` pays the contractor or refunds the requester.
- Failed payouts and refunds are recorded as pending settlements and retried automatically; a
  contract pays out or refunds, never both.
