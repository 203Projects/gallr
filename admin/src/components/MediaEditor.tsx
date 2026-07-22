import { useEffect, useState } from "react";
import type {
  AdminMediaAsset,
  AdminMediaMetadataPatch,
  AdminMediaRole,
} from "../domain";
import { ImageIcon } from "./Icons";

interface MediaEditorProps {
  media: AdminMediaAsset[];
  loading: boolean;
  busy: boolean;
  error: string | null;
  editable: boolean;
  readOnlyReason: string | null;
  onUpload: (file: File, role: AdminMediaRole) => void;
  onUpdateMetadata: (
    assetId: string,
    patch: AdminMediaMetadataPatch,
  ) => void;
  onReorder: (orderedAssetIds: string[]) => void;
  onDetach: (assetId: string) => void;
  onClearError: () => void;
}

function statusLabel(status: AdminMediaAsset["status"]): string {
  switch (status) {
    case "pending_upload":
      return "Upload reserved";
    case "ready":
      return "Processing for publication";
    case "published":
      return "Published";
    case "orphaned":
      return "Upload needs attention";
    case "rejected":
      return "Rejected during processing";
  }
}

function MediaPreview({ asset }: { asset: AdminMediaAsset }) {
  const alt = asset.altEn || asset.altKo || asset.originalFilename;
  return (
    <div className="media-asset-preview">
      {asset.previewUrl ? (
        <img src={asset.previewUrl} alt={alt} />
      ) : (
        <ImageIcon className="media-placeholder-icon" />
      )}
    </div>
  );
}

function MetadataEditor({
  asset,
  editable,
  busy,
  onSave,
}: {
  asset: AdminMediaAsset;
  editable: boolean;
  busy: boolean;
  onSave: (patch: AdminMediaMetadataPatch) => void;
}) {
  const [metadata, setMetadata] = useState<AdminMediaMetadataPatch>({
    altKo: asset.altKo,
    altEn: asset.altEn,
    credit: asset.credit,
    rightsUrl: asset.rightsUrl,
  });

  useEffect(() => {
    setMetadata({
      altKo: asset.altKo,
      altEn: asset.altEn,
      credit: asset.credit,
      rightsUrl: asset.rightsUrl,
    });
  }, [asset.altEn, asset.altKo, asset.credit, asset.rightsUrl]);

  const dirty =
    metadata.altKo !== asset.altKo ||
    metadata.altEn !== asset.altEn ||
    metadata.credit !== asset.credit ||
    metadata.rightsUrl !== asset.rightsUrl;

  const field = (
    label: string,
    key: keyof AdminMediaMetadataPatch,
    type = "text",
  ) => (
    <label className="field media-metadata-field">
      <span>{label}</span>
      <input
        type={type}
        value={metadata[key]}
        disabled={!editable || busy}
        onChange={(event) =>
          setMetadata((current) => ({
            ...current,
            [key]: event.target.value,
          }))
        }
      />
    </label>
  );

  return (
    <div className="media-metadata-editor">
      {field("Alt text (Korean)", "altKo")}
      {field("Alt text (English)", "altEn")}
      {field("Image credit", "credit")}
      {field("Rights URL", "rightsUrl", "url")}
      <div className="media-metadata-footer">
        <span className="muted">
          {dirty ? "Metadata has unsaved changes" : "Metadata saved"}
        </span>
        <button
          className="outlined-compact"
          type="button"
          disabled={!editable || busy || !dirty}
          onClick={() => onSave(metadata)}
        >
          Save metadata
        </button>
      </div>
    </div>
  );
}

function FileChooser({
  label,
  role,
  disabled,
  onUpload,
}: {
  label: string;
  role: AdminMediaRole;
  disabled: boolean;
  onUpload: MediaEditorProps["onUpload"];
}) {
  return (
    <label className="outlined-button file-button">
      {label}
      <input
        type="file"
        accept="image/jpeg,image/png,image/webp"
        disabled={disabled}
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = "";
          if (file) onUpload(file, role);
        }}
      />
    </label>
  );
}

export function MediaEditor({
  media,
  loading,
  busy,
  error,
  editable,
  readOnlyReason,
  onUpload,
  onUpdateMetadata,
  onReorder,
  onDetach,
  onClearError,
}: MediaEditorProps) {
  const cover = media.find((asset) => asset.role === "cover") ?? null;
  const gallery = media
    .filter((asset) => asset.role === "gallery")
    .sort((left, right) => left.sortOrder - right.sortOrder);

  const moveGallery = (index: number, offset: -1 | 1) => {
    const destination = index + offset;
    if (destination < 0 || destination >= gallery.length) return;
    const next = gallery.map((asset) => asset.assetId);
    [next[index], next[destination]] = [next[destination], next[index]];
    onReorder(next);
  };

  const renderAsset = (asset: AdminMediaAsset, galleryIndex?: number) => (
    <article className="media-asset" key={asset.assetId}>
      <div className="media-asset-heading">
        <div>
          <strong>{asset.originalFilename || "Untitled image"}</strong>
          <span className={`media-status media-status-${asset.status}`}>
            {statusLabel(asset.status)}
          </span>
        </div>
        <div className="media-order-actions">
          {galleryIndex !== undefined && (
            <>
              <button
                className="outlined-compact"
                type="button"
                disabled={!editable || busy || galleryIndex === 0}
                aria-label={`Move ${asset.originalFilename || "gallery image"} up`}
                onClick={() => moveGallery(galleryIndex, -1)}
              >
                Up
              </button>
              <button
                className="outlined-compact"
                type="button"
                disabled={!editable || busy || galleryIndex === gallery.length - 1}
                aria-label={`Move ${asset.originalFilename || "gallery image"} down`}
                onClick={() => moveGallery(galleryIndex, 1)}
              >
                Down
              </button>
            </>
          )}
          <button
            className="text-button media-remove-button"
            type="button"
            disabled={!editable || busy}
            aria-label={`Remove ${asset.originalFilename || "image"}`}
            onClick={() => onDetach(asset.assetId)}
          >
            Remove
          </button>
        </div>
      </div>
      <MediaPreview asset={asset} />
      <p className="media-file-details">
        {asset.width && asset.height ? `${asset.width} × ${asset.height} · ` : ""}
        {(asset.byteSize / (1024 * 1024)).toFixed(1)} MiB
      </p>
      {asset.status === "rejected" && (
        <p className="media-rejected-help">
          ! Processing rejected this image. Remove it and upload another image.
        </p>
      )}
      <MetadataEditor
        asset={asset}
        editable={editable}
        busy={busy}
        onSave={(patch) => onUpdateMetadata(asset.assetId, patch)}
      />
    </article>
  );

  if (loading) {
    return <p className="media-state" role="status">Loading media…</p>;
  }

  return (
    <div className="media-editor" aria-busy={busy}>
      {readOnlyReason && <p className="media-readonly-note">{readOnlyReason}</p>}
      {error && (
        <div className="media-error" role="alert">
          <span>! {error}</span>
          <button className="text-button" type="button" onClick={onClearError}>
            Dismiss
          </button>
        </div>
      )}
      {busy && <p className="media-state" role="status">Updating media…</p>}

      <section className="media-section" aria-labelledby="cover-media-title">
        <div className="media-section-heading">
          <h3 id="cover-media-title">Cover image</h3>
          <FileChooser
            label={cover ? "Replace cover" : "Choose cover image"}
            role="cover"
            disabled={!editable || busy}
            onUpload={onUpload}
          />
        </div>
        {cover ? (
          renderAsset(cover)
        ) : (
          <div className="media-empty">
            <ImageIcon className="media-placeholder-icon" />
            <p>No cover image attached.</p>
          </div>
        )}
      </section>

      <section className="media-section" aria-labelledby="gallery-media-title">
        <div className="media-section-heading">
          <div>
            <h3 id="gallery-media-title">Gallery</h3>
            <p>{gallery.length} images</p>
          </div>
          <FileChooser
            label="Add gallery image"
            role="gallery"
            disabled={!editable || busy}
            onUpload={onUpload}
          />
        </div>
        {gallery.length > 0 ? (
          <div className="media-gallery-list">
            {gallery.map((asset, index) => renderAsset(asset, index))}
          </div>
        ) : (
          <p className="media-gallery-empty">No gallery images attached.</p>
        )}
      </section>

      <p className="field-help media-format-help">
        JPEG, PNG, or WebP · maximum 10 MiB. New images are processed before
        they can be published.
      </p>
    </div>
  );
}
