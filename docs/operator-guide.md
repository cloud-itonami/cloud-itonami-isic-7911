# Operator Guide

## First Deployment
1. Register operator, agency locations, bonding/consumer-protection
   scope, agents and booking automation.
2. Import existing booking and billing history.
3. Run read-only bonding-scope and itinerary/booking-automation
   mission dry-runs.
4. Configure safety-class allowed sets and human sign-off paths.
5. Publish a dry-run reconciliation record and audit export.

## Minimum Production Controls
- bonding/consumer-protection-scope validation before any booking
  dispatch
- governor gate on every robot action before dispatch
- human sign-off for :high/:safety-critical actions (an out-of-scope
  booking, an unverified ticket issuance, an unverified
  reconciliation record)
- evidence-backed reconciliation records
- audit export for every dispatch, sign-off and reconciliation record
- backup manual travel-agency process

## Certification
Certified operators must prove robot-safety integrity, bonding/
consumer-protection discipline, evidence-backed reconciliation
records and human review for dispatch-affecting actions.
