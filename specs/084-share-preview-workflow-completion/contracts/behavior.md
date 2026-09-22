# Contracts

- renderExhibitionStoryCard(exhibition, language, palette) returns a PNG card or throws; cancellation propagates. Missing cover is a successful placeholder card.
- shareStoryCard(card, completion) presents the exact card and completes on dismissal/no target; presentation errors reach the state holder for sanitized logging.
- Outbox receiver authenticates before validating event payload; supported workflow events require complete snapshots. Provider success alone completes the worker lease; failure returns sanitized retryable diagnostic.
- Stable event key is forwarded to provider idempotency; production/staging identity and destination are validated before sending.
- Admin review anchors resolve after authentication. Gallery submission contact is separate from exhibition public contact details.
