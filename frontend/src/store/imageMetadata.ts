import { b64u } from "../crypto/keys";
import type { MessageAttachment } from "./db";

type Range = [number, number];

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const PNG_METADATA = new Set(["eXIf", "tEXt", "iTXt", "zTXt", "tIME"]);

/** Remove location, comments, and timestamps without re-encoding image pixels. */
export function stripImageMetadata(bytes: Uint8Array, mime: string): Uint8Array {
  const type = mime.split(";")[0].trim().toLowerCase();
  if (type === "image/jpeg" || type === "image/jpg") return stripJpeg(bytes);
  if (type === "image/png") return stripPng(bytes);
  if (type === "" || type === "application/octet-stream") {
    if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return stripJpeg(bytes);
    if (hasPngSignature(bytes)) return stripPng(bytes);
  }
  return bytes;
}

export function buildAttachment(name: string, contentType: string, bytes: Uint8Array): MessageAttachment {
  const stripped = stripImageMetadata(bytes, contentType);
  return {
    name: name.slice(0, 120) || "attachment",
    content_type: contentType || "application/octet-stream",
    data: b64u(stripped),
    size: stripped.length,
  };
}

/**
 * APP1 contains EXIF/XMP; COM contains comments. APP0 and APP2 stay intact.
 *
 * Known limits of the owner's rule (everything from SOS on is kept verbatim):
 * trailers after the primary image — MPF secondary images addressed from
 * APP2, Samsung's SEFT block after EOI — keep whatever metadata they carry,
 * and dropping APP1 also drops the EXIF Orientation tag.
 */
function stripJpeg(bytes: Uint8Array): Uint8Array {
  if (bytes.length < 3 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[2] !== 0xff) return bytes;

  const keep: Range[] = [[0, 2]];
  let at = 2;
  while (at < bytes.length) {
    const start = at;
    if (bytes[at] !== 0xff) return bytes;
    // A marker may be preceded by any number of FF fill bytes.
    while (at < bytes.length && bytes[at] === 0xff) at += 1;
    if (at === bytes.length) return bytes;
    const marker = bytes[at];
    if (marker === 0xda) {
      // A truncated SOS header is not safe to rewrite either.
      if (at + 2 >= bytes.length) return bytes;
      const length = (bytes[at + 1] << 8) | bytes[at + 2];
      if (length < 2 || at + 1 + length > bytes.length) return bytes;
      keep.push([start, bytes.length]);
      return copyRanges(bytes, keep);
    }
    // Byte stuffing and standalone markers have no length outside a scan.
    // Reserved extension markers are also left untouched: their framing is
    // not established here, so guessing could cut out image data.
    const hasLength = (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc8)
      || (marker >= 0xdb && marker <= 0xdf)
      || (marker >= 0xe0 && marker <= 0xef)
      || marker === 0xfe;
    if (!hasLength) return bytes;
    if (at + 2 >= bytes.length) return bytes;
    const length = (bytes[at + 1] << 8) | bytes[at + 2];
    const end = at + 1 + length;
    if (length < 2 || end > bytes.length) return bytes;
    if (marker !== 0xe1 && marker !== 0xfe) keep.push([start, end]);
    at = end;
  }
  // Without SOS, even otherwise valid-looking segments might be incomplete.
  return bytes;
}

function hasPngSignature(bytes: Uint8Array): boolean {
  return bytes.length >= PNG_SIGNATURE.length
    && PNG_SIGNATURE.every((value, index) => bytes[index] === value);
}

/** Copy whole chunks, including CRCs; trailing bytes after IEND are discarded. */
function stripPng(bytes: Uint8Array): Uint8Array {
  if (!hasPngSignature(bytes)) return bytes;
  const keep: Range[] = [[0, PNG_SIGNATURE.length]];
  let at = PNG_SIGNATURE.length;
  let first = true;
  let sawIdat = false;
  while (at < bytes.length) {
    if (at + 12 > bytes.length) return bytes;
    const length = ((bytes[at] << 24) | (bytes[at + 1] << 16)
      | (bytes[at + 2] << 8) | bytes[at + 3]) >>> 0;
    if (length > 0x7fffffff) return bytes;
    const end = at + 12 + length;
    if (end > bytes.length) return bytes;
    for (let i = at + 4; i < at + 8; i += 1) {
      const code = bytes[i];
      if (!((code >= 65 && code <= 90) || (code >= 97 && code <= 122))) return bytes;
    }
    const name = String.fromCharCode(bytes[at + 4], bytes[at + 5], bytes[at + 6], bytes[at + 7]);
    if (first && (name !== "IHDR" || length !== 13)) return bytes;
    first = false;
    if (name === "IHDR" && at !== PNG_SIGNATURE.length) return bytes;
    if (name === "IDAT") sawIdat = true;
    if (name === "IEND" && (length !== 0 || !sawIdat)) return bytes;
    if (!PNG_METADATA.has(name)) keep.push([at, end]);
    at = end;
    if (name === "IEND") return copyRanges(bytes, keep);
  }
  // A missing IEND may mean the parser stopped before later metadata.
  return bytes;
}

function copyRanges(bytes: Uint8Array, keep: Range[]): Uint8Array {
  const total = keep.reduce((sum, [from, to]) => sum + to - from, 0);
  if (total === bytes.length) return bytes;
  const out = new Uint8Array(total);
  let at = 0;
  for (const [from, to] of keep) {
    out.set(bytes.subarray(from, to), at);
    at += to - from;
  }
  return out;
}
