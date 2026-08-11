import {
  ConfigurationFiles,
  ImageMagick,
  initializeImageMagick,
  MagickFormat,
} from "@imagemagick/magick-wasm";

export const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
export const MAX_IMAGE_DIMENSION = 8_192;
export const MAX_IMAGE_PIXELS = 12_000_000;
const MAX_DECODED_BYTES = 128 * 1024 * 1024;
const MAX_CONTAINER_CHUNKS = 10_000;

const MAGICK_POLICY = `<?xml version="1.0" encoding="UTF-8"?>
<policymap>
  <policy domain="resource" name="thread" value="1"/>
  <policy domain="resource" name="width" value="8192"/>
  <policy domain="resource" name="height" value="8192"/>
  <policy domain="resource" name="area" value="12MP"/>
  <policy domain="resource" name="list-length" value="2"/>
  <policy domain="resource" name="memory" value="128MiB"/>
  <policy domain="resource" name="map" value="0"/>
  <policy domain="resource" name="disk" value="0"/>
  <policy domain="resource" name="time" value="2"/>
  <policy domain="system" name="max-memory-request" value="96MiB"/>
  <policy domain="system" name="memory-map" value="anonymous"/>
  <policy domain="delegate" rights="none" pattern="*"/>
  <policy domain="filter" rights="none" pattern="*"/>
  <policy domain="path" rights="none" pattern="-"/>
  <policy domain="path" rights="none" pattern="@*"/>
  <policy domain="path" rights="none" pattern="*../*"/>
  <policy domain="module" rights="none" pattern="*"/>
  <policy domain="module" rights="read | write" pattern="{JPEG,PNG,WEBP}"/>
  <policy domain="coder" rights="none" pattern="*"/>
  <policy domain="coder" rights="read" pattern="{JPEG,PNG,WEBP}"/>
</policymap>`;

let magickReady: Promise<void> | undefined;

function ensureMagick(): Promise<void> {
  magickReady ??= (async () => {
    const configuration = ConfigurationFiles.default;
    configuration.policy.data = MAGICK_POLICY;
    const wasmUrl = new URL(
      "magick.wasm",
      import.meta.resolve("@imagemagick/magick-wasm"),
    );
    const wasmBytes = await Deno.readFile(wasmUrl);
    await initializeImageMagick(wasmBytes, configuration);
  })();
  return magickReady;
}

export type SupportedImageMime = "image/jpeg" | "image/png" | "image/webp";

export interface ImageInspection {
  mimeType: SupportedImageMime;
  byteSize: number;
  width: number;
  height: number;
  checksumSha256: string;
}

export class ImageValidationError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "ImageValidationError";
  }
}

interface Dimensions {
  width: number;
  height: number;
}

interface PngPass {
  width: number;
  height: number;
  rowBytes: number;
}

function ascii(bytes: Uint8Array, offset: number, length: number): string {
  let value = "";
  for (let index = 0; index < length; index += 1) {
    value += String.fromCharCode(bytes[offset + index]);
  }
  return value;
}

function uint16be(bytes: Uint8Array, offset: number): number {
  return (bytes[offset] << 8) | bytes[offset + 1];
}

function uint16le(bytes: Uint8Array, offset: number): number {
  return bytes[offset] | (bytes[offset + 1] << 8);
}

function uint24le(bytes: Uint8Array, offset: number): number {
  return bytes[offset] | (bytes[offset + 1] << 8) |
    (bytes[offset + 2] << 16);
}

function uint32be(bytes: Uint8Array, offset: number): number {
  return new DataView(bytes.buffer, bytes.byteOffset + offset, 4).getUint32(
    0,
    false,
  );
}

function uint32le(bytes: Uint8Array, offset: number): number {
  return new DataView(bytes.buffer, bytes.byteOffset + offset, 4).getUint32(
    0,
    true,
  );
}

function validateDimensions(width: number, height: number): Dimensions {
  if (
    !Number.isInteger(width) || !Number.isInteger(height) || width < 1 ||
    height < 1
  ) {
    throw new ImageValidationError(
      "image_dimensions_invalid",
      "Image dimensions must be positive integers.",
    );
  }
  if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
    throw new ImageValidationError(
      "image_dimension_limit_exceeded",
      `Image dimensions may not exceed ${MAX_IMAGE_DIMENSION}px on either side.`,
    );
  }
  if (width * height > MAX_IMAGE_PIXELS) {
    throw new ImageValidationError(
      "image_pixel_limit_exceeded",
      `Image may not exceed ${MAX_IMAGE_PIXELS} decoded pixels.`,
    );
  }
  return { width, height };
}

function crc32(bytes: Uint8Array, offset: number, length: number): number {
  let crc = 0xffff_ffff;
  for (let index = offset; index < offset + length; index += 1) {
    crc ^= bytes[index];
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ (0xedb8_8320 & -(crc & 1));
    }
  }
  return (crc ^ 0xffff_ffff) >>> 0;
}

function concatenate(chunks: Uint8Array[], totalLength: number): Uint8Array {
  const output = new Uint8Array(totalLength);
  let offset = 0;
  for (const chunk of chunks) {
    output.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return output;
}

function pngPasses(
  width: number,
  height: number,
  bitsPerPixel: number,
  interlace: number,
): PngPass[] {
  const pass = (passWidth: number, passHeight: number): PngPass => ({
    width: passWidth,
    height: passHeight,
    rowBytes: Math.ceil(passWidth * bitsPerPixel / 8),
  });
  if (interlace === 0) return [pass(width, height)];

  const startX = [0, 4, 0, 2, 0, 1, 0];
  const startY = [0, 0, 4, 0, 2, 0, 1];
  const stepX = [8, 8, 4, 4, 2, 2, 1];
  const stepY = [8, 8, 8, 4, 4, 2, 2];
  const size = (total: number, start: number, step: number) =>
    total <= start ? 0 : Math.ceil((total - start) / step);

  return startX.map((x, index) =>
    pass(
      size(width, x, stepX[index]),
      size(height, startY[index], stepY[index]),
    )
  ).filter((item) => item.width > 0 && item.height > 0);
}

async function inflatePng(
  compressed: Uint8Array,
  expectedLength: number,
): Promise<Uint8Array> {
  const inputBuffer = new ArrayBuffer(compressed.byteLength);
  new Uint8Array(inputBuffer).set(compressed);

  try {
    const stream = new Blob([inputBuffer]).stream().pipeThrough(
      new DecompressionStream("deflate"),
    );
    const reader = stream.getReader();
    const chunks: Uint8Array[] = [];
    let totalLength = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalLength += value.byteLength;
      if (totalLength > expectedLength) {
        await reader.cancel();
        throw new ImageValidationError(
          "png_decoded_length_mismatch",
          "PNG scanline data exceeds its dimensions.",
        );
      }
      chunks.push(value);
    }
    if (totalLength !== expectedLength) {
      throw new ImageValidationError(
        "png_decoded_length_mismatch",
        "PNG scanline data does not match its dimensions.",
      );
    }
    return concatenate(chunks, totalLength);
  } catch (error) {
    if (error instanceof ImageValidationError) throw error;
    throw new ImageValidationError(
      "png_decode_failed",
      `PNG compressed scanlines are invalid: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  }
}

async function validatePng(bytes: Uint8Array): Promise<Dimensions | null> {
  const signature = [137, 80, 78, 71, 13, 10, 26, 10];
  if (!signature.every((value, index) => bytes[index] === value)) return null;
  if (bytes.length < 45) {
    throw new ImageValidationError(
      "png_truncated",
      "PNG container is truncated.",
    );
  }

  let offset = 8;
  let chunkCount = 0;
  let dimensions: Dimensions | null = null;
  let bitDepth = 0;
  let colorType = -1;
  let interlace = 0;
  let sawPalette = false;
  let sawIdat = false;
  let idatEnded = false;
  let sawIend = false;
  let idatLength = 0;
  const idatChunks: Uint8Array[] = [];

  while (offset < bytes.length) {
    chunkCount += 1;
    if (chunkCount > MAX_CONTAINER_CHUNKS || offset + 12 > bytes.length) {
      throw new ImageValidationError(
        "png_chunk_invalid",
        "PNG chunk table is invalid.",
      );
    }

    const dataLength = uint32be(bytes, offset);
    const typeOffset = offset + 4;
    const type = ascii(bytes, typeOffset, 4);
    const dataOffset = offset + 8;
    const crcOffset = dataOffset + dataLength;
    const chunkEnd = crcOffset + 4;
    if (!/^[A-Za-z]{4}$/.test(type) || chunkEnd > bytes.length) {
      throw new ImageValidationError(
        "png_chunk_invalid",
        "PNG chunk bounds are invalid.",
      );
    }
    if (
      crc32(bytes, typeOffset, 4 + dataLength) !== uint32be(bytes, crcOffset)
    ) {
      throw new ImageValidationError(
        "png_crc_mismatch",
        `PNG ${type} chunk failed its CRC check.`,
      );
    }

    if (chunkCount === 1 && type !== "IHDR") {
      throw new ImageValidationError(
        "png_missing_ihdr",
        "PNG must begin with IHDR.",
      );
    }

    if (type === "IHDR") {
      if (dimensions !== null || dataLength !== 13) {
        throw new ImageValidationError(
          "png_ihdr_invalid",
          "PNG IHDR is invalid.",
        );
      }
      dimensions = validateDimensions(
        uint32be(bytes, dataOffset),
        uint32be(bytes, dataOffset + 4),
      );
      bitDepth = bytes[dataOffset + 8];
      colorType = bytes[dataOffset + 9];
      interlace = bytes[dataOffset + 12];
      const validDepths: Record<number, number[]> = {
        0: [1, 2, 4, 8, 16],
        2: [8, 16],
        3: [1, 2, 4, 8],
        4: [8, 16],
        6: [8, 16],
      };
      if (
        !validDepths[colorType]?.includes(bitDepth) ||
        bytes[dataOffset + 10] !== 0 || bytes[dataOffset + 11] !== 0 ||
        ![0, 1].includes(interlace)
      ) {
        throw new ImageValidationError(
          "png_ihdr_invalid",
          "PNG IHDR uses unsupported encoding parameters.",
        );
      }
    } else if (type === "PLTE") {
      if (
        sawPalette || sawIdat || colorType === 0 || colorType === 4 ||
        dataLength < 3 || dataLength > 768 || dataLength % 3 !== 0
      ) {
        throw new ImageValidationError(
          "png_palette_invalid",
          "PNG palette is invalid.",
        );
      }
      sawPalette = true;
    } else if (type === "IDAT") {
      if (dimensions === null || idatEnded) {
        throw new ImageValidationError(
          "png_idat_order_invalid",
          "PNG IDAT chunks must be consecutive after IHDR.",
        );
      }
      sawIdat = true;
      idatLength += dataLength;
      idatChunks.push(bytes.subarray(dataOffset, crcOffset));
    } else if (type === "IEND") {
      if (!sawIdat || dataLength !== 0 || chunkEnd !== bytes.length) {
        throw new ImageValidationError(
          "png_iend_invalid",
          "PNG IEND is invalid.",
        );
      }
      sawIend = true;
    } else if (["acTL", "fcTL", "fdAT"].includes(type)) {
      throw new ImageValidationError(
        "png_animation_unsupported",
        "Animated PNG images are not supported.",
      );
    } else {
      if (sawIdat) idatEnded = true;
      if ((type.charCodeAt(0) & 0x20) === 0) {
        throw new ImageValidationError(
          "png_unknown_critical_chunk",
          `PNG contains unsupported critical chunk ${type}.`,
        );
      }
    }

    offset = chunkEnd;
    if (sawIend) break;
  }

  if (
    !dimensions || !sawIdat || !sawIend || offset !== bytes.length ||
    (colorType === 3 && !sawPalette)
  ) {
    throw new ImageValidationError(
      "png_structure_invalid",
      "PNG is missing required structural chunks.",
    );
  }

  const channels: Record<number, number> = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 };
  const passes = pngPasses(
    dimensions.width,
    dimensions.height,
    channels[colorType] * bitDepth,
    interlace,
  );
  const expectedLength = passes.reduce(
    (total, pass) => total + (pass.rowBytes + 1) * pass.height,
    0,
  );
  if (expectedLength > MAX_DECODED_BYTES) {
    throw new ImageValidationError(
      "image_decoded_size_limit_exceeded",
      "Decoded image exceeds the memory safety limit.",
    );
  }

  const decoded = await inflatePng(
    concatenate(idatChunks, idatLength),
    expectedLength,
  );
  let decodedOffset = 0;
  for (const pass of passes) {
    for (let row = 0; row < pass.height; row += 1) {
      if (decoded[decodedOffset] > 4) {
        throw new ImageValidationError(
          "png_filter_invalid",
          "PNG scanline uses an invalid filter method.",
        );
      }
      decodedOffset += pass.rowBytes + 1;
    }
  }
  if (decodedOffset !== decoded.length) {
    throw new ImageValidationError(
      "png_decoded_length_mismatch",
      "PNG decoded scanlines are structurally inconsistent.",
    );
  }
  return dimensions;
}

function validateJpeg(bytes: Uint8Array): Dimensions | null {
  if (bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
  if (bytes.length < 16) {
    throw new ImageValidationError(
      "jpeg_truncated",
      "JPEG container is truncated.",
    );
  }

  const startOfFrame = new Set([
    0xc0,
    0xc1,
    0xc2,
    0xc3,
    0xc5,
    0xc6,
    0xc7,
    0xc9,
    0xca,
    0xcb,
    0xcd,
    0xce,
    0xcf,
  ]);
  const componentIds = new Set<number>();
  let offset = 2;
  let markerCount = 0;
  let dimensions: Dimensions | null = null;
  let scanCount = 0;
  let sawEnd = false;

  while (offset < bytes.length) {
    markerCount += 1;
    if (markerCount > MAX_CONTAINER_CHUNKS || bytes[offset] !== 0xff) {
      throw new ImageValidationError(
        "jpeg_marker_invalid",
        `JPEG marker stream is invalid at byte ${offset}.`,
      );
    }
    while (offset < bytes.length && bytes[offset] === 0xff) offset += 1;
    if (offset >= bytes.length) {
      throw new ImageValidationError(
        "jpeg_truncated",
        "JPEG marker is truncated.",
      );
    }
    const marker = bytes[offset++];
    if (
      marker === 0x00 || marker === 0xd8 ||
      (marker >= 0xd0 && marker <= 0xd7)
    ) {
      throw new ImageValidationError(
        "jpeg_marker_invalid",
        "JPEG contains a marker in an invalid context.",
      );
    }
    if (marker === 0xd9) {
      if (offset !== bytes.length) {
        throw new ImageValidationError(
          "jpeg_trailing_data",
          "JPEG contains data after EOI.",
        );
      }
      sawEnd = true;
      break;
    }
    if (marker === 0x01) continue;
    if (offset + 2 > bytes.length) {
      throw new ImageValidationError(
        "jpeg_truncated",
        "JPEG segment is truncated.",
      );
    }

    const segmentLength = uint16be(bytes, offset);
    const dataOffset = offset + 2;
    const segmentEnd = offset + segmentLength;
    if (segmentLength < 2 || segmentEnd > bytes.length) {
      throw new ImageValidationError(
        "jpeg_segment_invalid",
        "JPEG segment bounds are invalid.",
      );
    }

    if (startOfFrame.has(marker)) {
      if (dimensions || segmentLength < 11 || bytes[dataOffset] !== 8) {
        throw new ImageValidationError(
          "jpeg_frame_invalid",
          "JPEG frame metadata is invalid or unsupported.",
        );
      }
      const componentCount = bytes[dataOffset + 5];
      if (
        componentCount < 1 || componentCount > 4 ||
        segmentLength !== 8 + 3 * componentCount
      ) {
        throw new ImageValidationError(
          "jpeg_frame_invalid",
          "JPEG frame component table is invalid.",
        );
      }
      dimensions = validateDimensions(
        uint16be(bytes, dataOffset + 3),
        uint16be(bytes, dataOffset + 1),
      );
      for (let index = 0; index < componentCount; index += 1) {
        const componentOffset = dataOffset + 6 + index * 3;
        const id = bytes[componentOffset];
        const sampling = bytes[componentOffset + 1];
        if (
          componentIds.has(id) || (sampling >> 4) < 1 || (sampling >> 4) > 4 ||
          (sampling & 0x0f) < 1 || (sampling & 0x0f) > 4 ||
          bytes[componentOffset + 2] > 3
        ) {
          throw new ImageValidationError(
            "jpeg_frame_invalid",
            "JPEG frame components are invalid.",
          );
        }
        componentIds.add(id);
      }
    } else if (marker === 0xdb) {
      let cursor = dataOffset;
      while (cursor < segmentEnd) {
        const tableInfo = bytes[cursor++];
        const precision = tableInfo >> 4;
        if (precision > 1 || (tableInfo & 0x0f) > 3) {
          throw new ImageValidationError(
            "jpeg_quantization_table_invalid",
            "JPEG quantization table is invalid.",
          );
        }
        cursor += precision === 0 ? 64 : 128;
        if (cursor > segmentEnd) {
          throw new ImageValidationError(
            "jpeg_quantization_table_invalid",
            "JPEG quantization table is truncated.",
          );
        }
      }
    } else if (marker === 0xc4) {
      let cursor = dataOffset;
      while (cursor < segmentEnd) {
        const tableInfo = bytes[cursor++];
        if (
          (tableInfo >> 4) > 1 || (tableInfo & 0x0f) > 3 ||
          cursor + 16 > segmentEnd
        ) {
          throw new ImageValidationError(
            "jpeg_huffman_table_invalid",
            "JPEG Huffman table is invalid.",
          );
        }
        let symbolCount = 0;
        let availableCodes = 1;
        for (let length = 0; length < 16; length += 1) {
          const count = bytes[cursor + length];
          symbolCount += count;
          availableCodes = availableCodes * 2 - count;
          if (availableCodes < 0) {
            throw new ImageValidationError(
              "jpeg_huffman_table_invalid",
              "JPEG Huffman code lengths are oversubscribed.",
            );
          }
        }
        cursor += 16 + symbolCount;
        if (symbolCount < 1 || symbolCount > 256 || cursor > segmentEnd) {
          throw new ImageValidationError(
            "jpeg_huffman_table_invalid",
            "JPEG Huffman symbols are invalid.",
          );
        }
      }
    } else if (marker === 0xdd && segmentLength !== 4) {
      throw new ImageValidationError(
        "jpeg_restart_interval_invalid",
        "JPEG restart interval is invalid.",
      );
    }

    if (marker === 0xda) {
      if (!dimensions) {
        throw new ImageValidationError(
          "jpeg_scan_invalid",
          "JPEG scan appears before its frame.",
        );
      }
      const scanComponents = bytes[dataOffset];
      if (
        scanComponents < 1 || scanComponents > componentIds.size ||
        segmentLength !== 6 + 2 * scanComponents
      ) {
        throw new ImageValidationError(
          "jpeg_scan_invalid",
          "JPEG scan component table is invalid.",
        );
      }
      const seenScanComponents = new Set<number>();
      for (let index = 0; index < scanComponents; index += 1) {
        const componentOffset = dataOffset + 1 + index * 2;
        const id = bytes[componentOffset];
        const selectors = bytes[componentOffset + 1];
        if (
          !componentIds.has(id) || seenScanComponents.has(id) ||
          (selectors >> 4) > 3 || (selectors & 0x0f) > 3
        ) {
          throw new ImageValidationError(
            "jpeg_scan_invalid",
            "JPEG scan selectors are invalid.",
          );
        }
        seenScanComponents.add(id);
      }
      const spectralOffset = dataOffset + 1 + 2 * scanComponents;
      if (
        bytes[spectralOffset] > 63 || bytes[spectralOffset + 1] > 63 ||
        (bytes[spectralOffset + 2] >> 4) > 13 ||
        (bytes[spectralOffset + 2] & 0x0f) > 13
      ) {
        throw new ImageValidationError(
          "jpeg_scan_invalid",
          "JPEG spectral selection is invalid.",
        );
      }

      offset = segmentEnd;
      let entropyBytes = 0;
      while (offset < bytes.length) {
        if (bytes[offset] !== 0xff) {
          entropyBytes += 1;
          offset += 1;
          continue;
        }
        const markerOffset = offset;
        while (offset < bytes.length && bytes[offset] === 0xff) offset += 1;
        if (offset >= bytes.length) break;
        const entropyMarker = bytes[offset];
        if (entropyMarker === 0x00) {
          entropyBytes += 1;
          offset += 1;
          continue;
        }
        if (entropyMarker >= 0xd0 && entropyMarker <= 0xd7) {
          offset += 1;
          continue;
        }
        offset = markerOffset;
        break;
      }
      if (entropyBytes < 1) {
        throw new ImageValidationError(
          "jpeg_entropy_data_missing",
          "JPEG scan has no entropy-coded data.",
        );
      }
      scanCount += 1;
      continue;
    }

    offset = segmentEnd;
  }

  if (!dimensions || scanCount < 1 || !sawEnd) {
    throw new ImageValidationError(
      "jpeg_structure_invalid",
      "JPEG is missing a frame, scan, or EOI marker.",
    );
  }
  return dimensions;
}

function validateWebp(bytes: Uint8Array): Dimensions | null {
  if (ascii(bytes, 0, 4) !== "RIFF" || ascii(bytes, 8, 4) !== "WEBP") {
    return null;
  }
  if (bytes.length < 30 || uint32le(bytes, 4) + 8 !== bytes.length) {
    throw new ImageValidationError(
      "webp_riff_invalid",
      "WebP RIFF size is invalid.",
    );
  }

  let offset = 12;
  let chunkCount = 0;
  let dimensions: Dimensions | null = null;
  let extendedDimensions: Dimensions | null = null;
  let sawImageData = false;
  let sawExtendedHeader = false;
  let extendedFlags = 0;

  while (offset < bytes.length) {
    chunkCount += 1;
    if (chunkCount > MAX_CONTAINER_CHUNKS || offset + 8 > bytes.length) {
      throw new ImageValidationError(
        "webp_chunk_invalid",
        "WebP chunk table is invalid.",
      );
    }
    const type = ascii(bytes, offset, 4);
    const dataLength = uint32le(bytes, offset + 4);
    const dataOffset = offset + 8;
    const dataEnd = dataOffset + dataLength;
    const chunkEnd = dataEnd + (dataLength & 1);
    if (!/^[\x20-\x7e]{4}$/.test(type) || chunkEnd > bytes.length) {
      throw new ImageValidationError(
        "webp_chunk_invalid",
        "WebP chunk bounds are invalid.",
      );
    }
    if ((dataLength & 1) === 1 && bytes[dataEnd] !== 0) {
      throw new ImageValidationError(
        "webp_padding_invalid",
        "WebP chunk padding must be zero.",
      );
    }

    if (type === "VP8X") {
      if (chunkCount !== 1 || sawExtendedHeader || dataLength !== 10) {
        throw new ImageValidationError(
          "webp_vp8x_invalid",
          "WebP extended header is invalid.",
        );
      }
      extendedFlags = bytes[dataOffset];
      if (
        (extendedFlags & 0xc1) !== 0 || bytes[dataOffset + 1] !== 0 ||
        bytes[dataOffset + 2] !== 0 || bytes[dataOffset + 3] !== 0
      ) {
        throw new ImageValidationError(
          "webp_vp8x_invalid",
          "WebP extended header contains reserved bits.",
        );
      }
      if ((extendedFlags & 0x02) !== 0) {
        throw new ImageValidationError(
          "webp_animation_unsupported",
          "Animated WebP images are not supported.",
        );
      }
      extendedDimensions = validateDimensions(
        uint24le(bytes, dataOffset + 4) + 1,
        uint24le(bytes, dataOffset + 7) + 1,
      );
      sawExtendedHeader = true;
    } else if (type === "VP8 ") {
      if (
        sawImageData || dataLength < 10 || (bytes[dataOffset] & 1) !== 0 ||
        bytes[dataOffset + 3] !== 0x9d || bytes[dataOffset + 4] !== 0x01 ||
        bytes[dataOffset + 5] !== 0x2a
      ) {
        throw new ImageValidationError(
          "webp_vp8_invalid",
          "WebP VP8 key frame is invalid.",
        );
      }
      const firstPartitionLength = uint24le(bytes, dataOffset) >>> 5;
      if (firstPartitionLength < 7 || firstPartitionLength > dataLength - 3) {
        throw new ImageValidationError(
          "webp_vp8_invalid",
          "WebP VP8 partition length is invalid.",
        );
      }
      dimensions = validateDimensions(
        uint16le(bytes, dataOffset + 6) & 0x3fff,
        uint16le(bytes, dataOffset + 8) & 0x3fff,
      );
      sawImageData = true;
    } else if (type === "VP8L") {
      if (
        sawImageData || dataLength < 5 || bytes[dataOffset] !== 0x2f ||
        (bytes[dataOffset + 4] >> 5) !== 0
      ) {
        throw new ImageValidationError(
          "webp_vp8l_invalid",
          "WebP VP8L stream header is invalid.",
        );
      }
      dimensions = validateDimensions(
        1 + bytes[dataOffset + 1] + ((bytes[dataOffset + 2] & 0x3f) << 8),
        1 + (bytes[dataOffset + 2] >> 6) + (bytes[dataOffset + 3] << 2) +
          ((bytes[dataOffset + 4] & 0x0f) << 10),
      );
      sawImageData = true;
    } else if (type === "ANIM" || type === "ANMF") {
      throw new ImageValidationError(
        "webp_animation_unsupported",
        "Animated WebP images are not supported.",
      );
    }

    offset = chunkEnd;
  }

  if (!dimensions || !sawImageData || offset !== bytes.length) {
    throw new ImageValidationError(
      "webp_structure_invalid",
      "WebP is missing one complete image-data chunk.",
    );
  }
  if (
    sawExtendedHeader &&
    (!extendedDimensions || dimensions.width !== extendedDimensions.width ||
      dimensions.height !== extendedDimensions.height)
  ) {
    throw new ImageValidationError(
      "webp_dimension_mismatch",
      "WebP extended and image-data dimensions disagree.",
    );
  }
  return dimensions;
}

function toHex(buffer: ArrayBuffer): string {
  return Array.from(
    new Uint8Array(buffer),
    (value) => value.toString(16).padStart(2, "0"),
  ).join("");
}

async function fullyDecodeImage(
  bytes: Uint8Array,
  mimeType: SupportedImageMime,
  dimensions: Dimensions,
): Promise<void> {
  await ensureMagick();
  const format = mimeType === "image/png"
    ? MagickFormat.Png
    : mimeType === "image/jpeg"
    ? MagickFormat.Jpeg
    : MagickFormat.WebP;

  try {
    let decodedLength = -1;
    ImageMagick.read(bytes, format, (image) => {
      if (
        image.width !== dimensions.width || image.height !== dimensions.height
      ) {
        throw new Error(
          "decoded dimensions do not match the validated container",
        );
      }
      const rgba = image.getPixels((pixels) =>
        pixels.toByteArray(0, 0, image.width, image.height, "RGBA")
      );
      if (rgba === null) throw new Error("pixel decoder returned no RGBA data");
      decodedLength = rgba.byteLength;
    });
    if (decodedLength !== dimensions.width * dimensions.height * 4) {
      throw new Error("decoded RGBA length does not match image dimensions");
    }
  } catch (error) {
    throw new ImageValidationError(
      "image_decode_failed",
      `Image pixels could not be fully decoded: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  }
}

export function extensionForMime(
  mimeType: SupportedImageMime,
): "jpg" | "png" | "webp" {
  if (mimeType === "image/jpeg") return "jpg";
  if (mimeType === "image/png") return "png";
  return "webp";
}

export async function inspectImageBytes(
  bytes: Uint8Array,
): Promise<ImageInspection> {
  if (bytes.byteLength < 1) {
    throw new ImageValidationError("image_empty", "Image is empty.");
  }
  if (bytes.byteLength > MAX_IMAGE_BYTES) {
    throw new ImageValidationError(
      "image_too_large",
      "Image exceeds the 10 MiB limit.",
    );
  }

  let mimeType: SupportedImageMime;
  let dimensions = await validatePng(bytes);
  if (dimensions) {
    mimeType = "image/png";
  } else {
    dimensions = validateJpeg(bytes);
    if (dimensions) {
      mimeType = "image/jpeg";
    } else {
      dimensions = validateWebp(bytes);
      if (dimensions) {
        mimeType = "image/webp";
      } else {
        throw new ImageValidationError(
          "image_magic_unsupported",
          "Bytes are not a supported JPEG, PNG, or WebP image.",
        );
      }
    }
  }

  await fullyDecodeImage(bytes, mimeType, dimensions);

  const digestBuffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(digestBuffer).set(bytes);
  const checksum = await crypto.subtle.digest("SHA-256", digestBuffer);
  return {
    mimeType,
    byteSize: bytes.byteLength,
    width: dimensions.width,
    height: dimensions.height,
    checksumSha256: toHex(checksum),
  };
}
