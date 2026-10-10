# Quickstart: Gallery Profile Images

1. Database contract
   ```bash
   node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
   ```
   Then run the pgTAP suite per `database-tests.yml` against a disposable local stack.
2. Shared + app
   ```bash
   ./gradlew shared:allTests composeApp:testAndroidHostTest shared:ktlintCheck composeApp:ktlintCheck
   ```
3. Operator script (network-free tests)
   ```bash
   node --test scripts/gallery-profile-images/gallery-profile-images.test.mjs
   ```
4. Staging publish: `prepare` into a private directory outside the checkout, upload the
   bundle with the staging service key injected from 1Password, run the generated SQL
   against staging, then open My Gallr against staging and follow a gallery with an image.
5. Production publish only after explicit approval, same bundle.
