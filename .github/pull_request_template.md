## Intent

What this change does and why, in two or three sentences. Link the milestone and any business rules (`BR-n`).

## Proof

The tests that prove it, and how a reviewer can see it working.

## Risk

What could break, and how it is contained or would be noticed.

## Review focus

The part that deserves the closest look.

## Self-review

The author ticks these before asking for a review, and says in the description where one does not hold.

- [ ] I read the whole diff again, line by line, after the last commit.
- [ ] The checks CI runs pass locally: `make test`, and `make up`, `make smoke` and `make e2e` for a change the
      running stack can see.
- [ ] Tests cover the change, at the lowest layer that can prove it, and every test name the documents cite exists
      (`make test-docs`).
- [ ] Javadoc or TSDoc says why, for every new public type and exported function.
- [ ] The documents that own what changed are updated here: README, architecture, ADRs, domain rules, error
      catalogue, delivery and the diagrams, as applicable.
- [ ] The architecture page still matches the code, or this change does not affect it.
- [ ] No secret, credential, account ID or real personal data is in the diff or in this description.
- [ ] I know how this change is undone: a revert of the merge, plus any migration, data or AWS step that needs.
