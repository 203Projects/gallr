# Data and State

StoryCardImage owns PNG bytes and descriptor. Palette contains ARGB background/title/secondary/divider/frame/placeholder/transparent values. Preview transitions Rendering → Ready(card) or Failed; retry creates a new render; close cancels; sharing requires Ready and rejects concurrent calls.

Workflow notifications use outbox event ID/deduplication key, event type, entity snapshot, recipient email, submission time, claim type/note, and saved review notes. Gallery claim address is captured from authenticated identity at submission. Exhibition contact is explicitly entered and saved as submitter_email. Delivery configuration supplies environment, verified sender, provider key, and staging-only sink.
