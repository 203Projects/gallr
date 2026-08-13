-- Let Admin render already-published submission media from its public delivery
-- object. The private original remains protected by the existing Storage policy.
create or replace function content_private.admin_submission_json(
  p_submission_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', submission.id,
    'status', submission.status::text,
    'source', submission.source,
    'owner_exhibition_id', submission.owner_exhibition_id,
    'gallery_name_ko', coalesce(gallery.name_ko, ''),
    'gallery_name_en', coalesce(gallery.name_en, ''),
    'submitter_email', coalesce(submission.submitter_email, ''),
    'payload', submission.payload,
    'accepted_exhibition_id', submission.accepted_exhibition_id,
    'review_notes', coalesce(submission.review_notes, ''),
    'submitted_at', submission.submitted_at,
    'reviewed_at', submission.reviewed_at,
    'created_at', submission.created_at,
    'media', coalesce(
      (
        select jsonb_agg(
          jsonb_build_object(
            'asset_id', asset.id,
            'bucket_id', asset.bucket_id,
            'object_path', asset.object_path,
            'public_url', asset.public_url,
            'mime_type', coalesce(asset.mime_type, ''),
            'byte_size', asset.byte_size,
            'original_filename', coalesce(asset.metadata ->> 'original_filename', '')
          ) order by attachment.sort_order, asset.id
        )
        from content.submission_media as attachment
        join content.media_assets as asset on asset.id = attachment.media_id
        where attachment.submission_id = submission.id
      ),
      '[]'::jsonb
    )
  )
  from content.exhibition_submissions as submission
  left join content.exhibitions as exhibition
    on exhibition.id = submission.owner_exhibition_id
  left join content.galleries as gallery on gallery.id = exhibition.gallery_id
  where submission.id = p_submission_id;
$$;

comment on function content_private.admin_submission_json(uuid) is
  'Builds the staff submission DTO, including public delivery URLs for published media.';
