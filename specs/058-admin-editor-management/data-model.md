# Data Model: Admin Editor Management

## Editor

- Existing fields: slug ID, bilingual name/title/bio, bilingual curation description, public active state, active date range, timestamps.
- New field: `revision integer not null default 1`, incremented by a table trigger for every profile mutation, including approved editor requests and admin access changes.
- Slug is immutable in this workflow.

## Editor membership

- Existing one-to-one optional editor/Auth link.
- `active` controls editor workspace authorization.
- Deactivation updates membership state but does not delete the link or Auth user.

## State transitions

- Profile edit: validate revision and fields → update profile → increment revision → audit `editor.updated`.
- Deactivate: validate revision and linked membership → set membership inactive and profile inactive → increment revision → audit `editor.access_deactivated`.
- Restore access: validate revision and linked membership → set membership active, leave profile inactive → increment revision → audit `editor.access_restored`.
