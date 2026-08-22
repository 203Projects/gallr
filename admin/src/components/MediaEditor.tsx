import { useEffect, useState } from "react";
import type {
  AdminMediaAsset,
  AdminMediaMetadataPatch,
  AdminMediaRole,
} from "../domain";
import { ImageIcon } from "./Icons";
import { useI18n, type MessageKey } from "../i18n";

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

function statusKey(status: AdminMediaAsset["status"]): MessageKey {
  switch (status) {
    case "pending_upload":
      return "media.uploadReserved";
    case "ready":
      return "media.processing";
    case "published":
      return "media.published";
    case "orphaned":
      return "media.attention";
    case "rejected":
      return "media.rejected";
  }
}

function MediaPreview({ asset }: { asset: AdminMediaAsset }) {
  const { localized } = useI18n();
  const alt = localized(asset.altKo, asset.altEn, asset.originalFilename);
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
  const { t } = useI18n();
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
      {field(t("media.altKo"), "altKo")}
      {field(t("media.altEn"), "altEn")}
      {field(t("media.credit"), "credit")}
      {field(t("media.rightsUrl"), "rightsUrl", "url")}
      <div className="media-metadata-footer">
        <span className="muted">
          {t(dirty ? "media.metadataDirty" : "media.metadataSaved")}
        </span>
        <button
          className="outlined-compact"
          type="button"
          disabled={!editable || busy || !dirty}
          onClick={() => onSave(metadata)}
        >
          {t("media.saveMetadata")}
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
  const { t, formatNumber } = useI18n();
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
          <strong>{asset.originalFilename || t("media.untitled")}</strong>
          <span className={`media-status media-status-${asset.status}`}>
            {t(statusKey(asset.status))}
          </span>
        </div>
        <div className="media-order-actions">
          {galleryIndex !== undefined && (
            <>
              <button
                className="outlined-compact"
                type="button"
                disabled={!editable || busy || galleryIndex === 0}
                aria-label={t("media.moveUpLabel", { name: asset.originalFilename || t("media.galleryImage") })}
                onClick={() => moveGallery(galleryIndex, -1)}
              >
                {t("media.up")}
              </button>
              <button
                className="outlined-compact"
                type="button"
                disabled={!editable || busy || galleryIndex === gallery.length - 1}
                aria-label={t("media.moveDownLabel", { name: asset.originalFilename || t("media.galleryImage") })}
                onClick={() => moveGallery(galleryIndex, 1)}
              >
                {t("media.down")}
              </button>
            </>
          )}
          <button
            className="text-button media-remove-button"
            type="button"
            disabled={!editable || busy}
            aria-label={t("media.removeLabel", { name: asset.originalFilename || t("media.image") })}
            onClick={() => onDetach(asset.assetId)}
          >
            {t("media.remove")}
          </button>
        </div>
      </div>
      <MediaPreview asset={asset} />
      <p className="media-file-details">
        {asset.width && asset.height
          ? `${formatNumber(asset.width)} × ${formatNumber(asset.height)} · `
          : ""}
        {formatNumber(asset.byteSize / (1024 * 1024), {
          minimumFractionDigits: 1,
          maximumFractionDigits: 1,
        })} MiB
      </p>
      {asset.status === "rejected" && (
        <p className="media-rejected-help">
          {t("media.processingRejected")}
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
    return <p className="media-state" role="status">{t("media.loading")}</p>;
  }

  return (
    <div className="media-editor" aria-busy={busy}>
      {readOnlyReason && <p className="media-readonly-note">{readOnlyReason}</p>}
      {error && (
        <div className="media-error" role="alert">
          <span>! {error}</span>
          <button className="text-button" type="button" onClick={onClearError}>
            {t("common.dismiss")}
          </button>
        </div>
      )}
      {busy && <p className="media-state" role="status">{t("media.updating")}</p>}

      <section className="media-section" aria-labelledby="cover-media-title">
        <div className="media-section-heading">
          <h3 id="cover-media-title">{t("media.cover")}</h3>
          <FileChooser
            label={t(cover ? "media.replaceCover" : "media.chooseCover")}
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
            <p>{t("media.noCover")}</p>
          </div>
        )}
      </section>

      <section className="media-section" aria-labelledby="gallery-media-title">
        <div className="media-section-heading">
          <div>
            <h3 id="gallery-media-title">{t("media.gallery")}</h3>
            <p>{t("media.imageCount", { count: formatNumber(gallery.length) })}</p>
          </div>
          <FileChooser
            label={t("media.addGallery")}
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
          <p className="media-gallery-empty">{t("media.noGallery")}</p>
        )}
      </section>

      <p className="field-help media-format-help">
        {t("media.formatHelp")}
      </p>
    </div>
  );
}
