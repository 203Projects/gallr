# Product surface configuration guard

`validate-config.py` checks the security-sensitive configuration shared by the
gallery product surfaces:

- local Auth remains invite-only while email OTP stays available to
  pre-provisioned owners;
- every directly tested Edge Function has a matching `supabase/config.toml`
  section, import map, and entrypoint; and
- each function's `verify_jwt` mode matches its reviewed authentication
  boundary.

Run the guard and its regression suite from the repository root:

```sh
python3 scripts/product-surfaces/validate-config.test.py
python3 scripts/product-surfaces/validate-config.py
```

Adding a function or intentionally changing an authentication boundary requires
updating the validator contract and tests in the same review. Do not weaken the
guard to make an unrelated build pass.
