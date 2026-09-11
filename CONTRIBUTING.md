# Contributing

`cloud-itonami-7911` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development
The capability layer lives in `kotoba-lang/robotics` and
`kotoba-lang/labor`. This repo holds the business blueprint and
operator contracts.

```bash
kbb -X:test
kbb -M:lint
```

## Rules
- Do not commit real traveler or payment data.
- Keep robot dispatch and reconciliation records behind the Travel
  Agency Governor.
- Treat booking/ticketing workflows as high-risk: add tests for
  robot-safety gating, bonding scope, evidence, disclosure and audit
  logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
