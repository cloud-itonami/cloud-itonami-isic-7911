# cloud-itonami-7911

Open Business Blueprint for **ISIC Rev.5 7911**: travel agency
activities (booking and selling third-party travel products --
flights, accommodation, transfers -- as an intermediary on behalf of
travelers).

This repository designs a forkable OSS business for community travel
agency operations: booking-authority and consumer-protection-scope
management, robotics-assisted itinerary preparation and booking-
confirmation staging, and booking/reconciliation records — run by a
qualified agency so it keeps its own bonding and consumer-protection
compliance history instead of renting a closed travel-booking
platform.

## The settlement amount is recomputed, not read

`:coordinate-vendor-settlement` proposals carry an `:estimated-amount`,
and a threshold escalates large settlements to a human. Until now that
gate read the amount **straight out of the advisor's own proposal** —
the gate's only input was the number it existed to guard against. Two
consequences, both closed:

- an advisor stating a figure just under the threshold for a far larger
  settlement bypassed the human escalation entirely;
- `some->` on a missing `:estimated-amount` returned nil, so a proposal
  carrying **no amount at all** escalated to nobody.

Entities now carry a filed per-unit rate and a billable-unit count, and
`recomputed-settlement` derives the amount from those via
[`kotoba.reservation`](https://github.com/kotoba-lang/reservation). The
mismatch gate is HARD and the threshold is applied to the **recomputed**
amount, so understating cannot buy its way under it. A settlement that
cannot be recomputed is itself a HARD violation.


## Scope note: booking intermediary, not a carrier or a package organizer

`cloud-itonami-isic-5110` (passenger air), `cloud-itonami-isic-4911`/
`4912` (passenger/freight rail) and `cloud-itonami-isic-5011`/`5020`
(passenger/cargo water transport) are all CARRIERS that move goods or
people aboard their own vehicle or vessel; this repository books
travel WITH those carriers on a traveler's behalf without operating
any transport itself. Also distinct from `cloud-itonami-isic-7912`
("Community Tour Operator Operations", a separate future/sibling
vertical): a travel agent RETAILS third-party travel products and
typically bears intermediary liability, while a tour operator
DESIGNS and ASSEMBLES package tours under its own brand and bears
organizer liability -- a distinction the EU's Package Travel Directive
(2015/2302) makes explicit, and one that many jurisdictions' "Seller
of Travel" statutes (California, Florida, Washington, Iowa, Hawaii)
also require separate registration/bonding to reflect. Agencies
issuing airline tickets typically require ARC (US) or IATA
(international) accreditation.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a
**robot performs the physical domain work**. Here robots (itinerary-
preparation automation, booking-confirmation staging and document
assembly) operate under an actor that proposes actions and an
independent **Travel Agency Governor** that gates them. The governor
never releases a booking confirmation or issues a ticket itself;
`:high`/`:safety-critical` actions (a booking outside verified
bonding/registration scope, a ticket issuance without a completed
payment-verification check, a reconciliation record without verified
evidence) require human sign-off.

## Core Contract

```text
intake + identity + bonding/consumer-protection scope + booking request
        |
        v
Travel Agency Advisor -> Travel Agency Governor -> match, booking record, or human approval
        |
        v
robot actions (gated) + booking record + reconciliation record + audit ledger
```

No automated advice can release a booking confirmation the governor
refuses, match an unregistered agent to an out-of-scope booking, or
publish a reconciliation record without governor approval and audit
evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `7911`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/labor`](https://github.com/kotoba-lang/labor) — agent registration, dispatch, timesheet/follow-up contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
