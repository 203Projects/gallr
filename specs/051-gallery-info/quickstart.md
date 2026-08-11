# Quickstart: Gallery Info verification

No deployment or credential change is part of this feature.

```sh
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
node --test scripts/staging-rehearsal/lib/validate-migration-lineage.test.mjs

supabase db reset
supabase test db supabase/tests/database --local
supabase db lint --local --schema public,content,content_private --fail-on error

cd supabase/functions/geocode-address
deno task check
deno task test

cd ../../../gallery
npm test
npm run typecheck
npm run build
```

Rendered smoke path:

1. Sign in as an active owner and open `Gallery Info`.
2. Search a Korean address, confirm no values apply before selection, choose one candidate,
   save, and confirm revision/status feedback.
3. Create an exhibition and confirm all venue defaults including coordinates are copied.
4. Change Gallery Info again and confirm the existing draft did not change.
5. Repeat at desktop and 390px mobile widths with keyboard navigation and no relevant
   console errors.
6. Confirm a pending claimant for an existing gallery receives no Gallery Info editor.
