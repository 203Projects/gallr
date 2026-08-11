# Implementation Plan: Admin editor onboarding

1. Add an admin-only database command that validates and atomically creates an
   editor profile, editor membership, and audit record.
2. Add a verified-JWT Edge Function that authorizes the admin before parsing
   input, sends the invitation with the server-side Auth Admin API, then calls
   the database command with the original caller authorization.
3. Add a typed repository and Editors workspace to the React admin portal.
4. Gate navigation and routing by `staffRole === "admin"` while retaining the
   server-side checks as the security boundary.
5. Verify pgTAP, no-network Edge tests, React tests, typecheck, build, lint, and
   migration lineage.
