# Data Model: My Gallr gallery following

## FollowedGallerySnapshot

- `nameKo`, `nameEn`
- `cityKo`, `cityEn`
- `regionKo`, `regionEn`

## FollowedGallery

- `galleryKey`: deterministic normalized bilingual venue key.
- `snapshot`: immutable display fallback.
- `knownExhibitionIds`: IDs present at follow time or last acknowledgement.
- `followedAt`: local creation time.

## Derived GalleryCandidate

Groups current catalogue exhibitions by `galleryKey`. It is not persisted. The first exhibition in
catalogue order provides display/location fields and the group retains every current exhibition.

## Newness

`unseenExhibitions = currentExhibitions where id not in knownExhibitionIds`.

Acknowledgement unions all current IDs into `knownExhibitionIds`. Removed catalogue entries are not
removed from the known set, preventing an old exhibition from becoming “new” if it reappears.
