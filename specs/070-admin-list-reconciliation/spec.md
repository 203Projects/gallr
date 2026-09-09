# Specification: Admin list reconciliation

## Problem

The Exhibitions list can replace a just-saved optimistic record with an older
in-flight list snapshot. A failed filter request also leaves rows from the
previous filter visible beneath the new controls.

## Acceptance criteria

1. A completed list response never replaces a record already saved at a higher
   revision during that request.
2. The saved record is re-evaluated against the filters current when the list
   response completes.
3. A failed list request clears the table and shows the existing failure notice;
   rows from a previous filter are not presented as current results.
4. Newer list requests still supersede older list requests.
