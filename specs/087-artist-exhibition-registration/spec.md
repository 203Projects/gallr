# Exhibition-first registration

## User stories and acceptance criteria

1. An artist, curator, or organizer can choose **전시 등록**, prepare an exhibition, sign in with an existing gallr account, and request editorial review without a gallery membership. An exhibition may take place at a gallery, rented venue, or pop-up. Selecting a venue never creates gallery management authority.
2. A gallery operator can choose **갤러리 등록 (관계자)** and enter the existing gallery search, claim, verification, and workspace flow. Existing workspace links remain available. Registration choices are usable without JavaScript.
3. An authenticated submitter can see only their own submitted exhibitions, review notes, and accurate publication state. Approval is shown as preparation for publication until a canonical published version exists. A rejected request can be copied into a new request while preserving the original review record.

## Requirements

- `/submit/` is an exhibition-first chooser. Homepage, public navigation, and footer agree on the two actions.
- `/submit/exhibition/` offers exhibition details, venue, contact relationship, preview, and authenticated submission. Preserve entered fields through in-page email/password login. Posters are private JPEG/PNG uploads, maximum 10 MiB; one cover is required.
- Only verified, non-anonymous Auth identities may register intake/upload slots or submit. Authorization is derived from the server identity, never relationship fields or client metadata.
- Use the existing canonical public-form review pipeline and private media bucket. Store an additive account association and preserve gallery/editor privileges and historical public submissions.
- Reserve bounded upload slots before uploading; validate path, ownership, MIME, bytes and object metadata before submission. No anonymous upload grant, overwrite, broad read, or new gallery role.
- Submission retries keep the same request ID and return the same result; changed retries fail. Account rate limits and per-draft media bounds apply transactionally.
- Private status reads are bounded and cannot expose another user's contact, draft, or review notes. Server determines publication state.
- UI is bilingual KO/EN, accessible, sharp-cornered and monochrome, with one orange primary CTA and black CTA text.
- Missing configuration or unactivated backend fails closed with a useful contact fallback. Activation, Auth configuration and database rollout require staging verification and separate release authorization.

## Scope

Public web and additive database contracts; existing Gallery onboarding and Admin review remain the authorities. Mobile UI, visitor suggestions, artist profiles, payments, and production rollout are outside this feature.
