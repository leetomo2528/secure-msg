/**
 * Turning picked files into sendable attachments.
 *
 * This is the DOM half of the shrink path: it decodes, draws and re-encodes,
 * and it owns every browser API involved. All the arithmetic — which rungs to
 * try, which one to jump to, how the budget is split — lives in imageShrink.ts
 * so it can be tested without a browser. The dependency runs one way only.
 *
 * A photo off a modern phone is 3-12 MB and the relay refuses anything over
 * 512 KiB, so without this the composer could only ever attach screenshots.
 */
import { b64u } from "../crypto/keys";
import type { MessageAttachment } from "./db";
import {
  COMPOSE_ATTACHMENT_BUDGET,
  MAX_SOURCE_BYTES,
  MAX_SOURCE_PIXELS,
  allocateBudgets,
  chooseOutputType,
  isPassThroughImage,
  isShrinkableImage,
  predictRung,
  stripMetadata,
  rungsFor,
  targetSize,
  type BudgetItem,
} from "./imageShrink";

export interface PrepareResult {
  attachments: MessageAttachment[];
  /** Korean, one per file that could not be prepared. */
  errors: string[];
}

interface Encoded {
  blob: Blob;
  type: string;
  size: number;
}

/**
 * Decoded once per file and reused for every rung.
 *
 * A 12 MP photo is ~48 MB of RGBA once decoded, so the one thing this must not
 * do is hold two of them at a time. Files are processed strictly serially and
 * the bitmap is closed before the next one starts.
 */
async function decode(file: File): Promise<ImageBitmap> {
  // imageOrientation matters: a phone photo is usually stored landscape with
  // an EXIF rotation flag, and drawing the raw pixels would send it sideways.
  // The re-encode drops EXIF, so there is no second chance to apply it.
  return createImageBitmap(file, { imageOrientation: "from-image" });
}

function canvasFor(width: number, height: number): OffscreenCanvas | HTMLCanvasElement {
  if (typeof OffscreenCanvas !== "undefined") return new OffscreenCanvas(width, height);
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  return canvas;
}

async function toBlob(
  canvas: OffscreenCanvas | HTMLCanvasElement,
  type: string,
  quality: number,
): Promise<Blob | null> {
  if ("convertToBlob" in canvas) return canvas.convertToBlob({ type, quality });
  return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
}

/**
 * One rung: draw at `size`, encode at `quality`.
 *
 * JPEG has no alpha, and a canvas defaults to transparent black, so a PNG with
 * transparency would encode its transparent regions as black rather than as
 * the white every viewer expects. The fill is unconditional because it costs
 * nothing on an opaque source.
 */
async function encodeAt(
  bitmap: ImageBitmap,
  edge: number,
  quality: number,
  type: string,
): Promise<Encoded | null> {
  const size = targetSize(bitmap.width, bitmap.height, edge);
  const canvas = canvasFor(size.width, size.height);
  const ctx = canvas.getContext("2d") as
    | OffscreenCanvasRenderingContext2D
    | CanvasRenderingContext2D
    | null;
  if (!ctx) return null;
  if (type === "image/jpeg") {
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, size.width, size.height);
  }
  ctx.drawImage(bitmap, 0, 0, size.width, size.height);
  const blob = await toBlob(canvas, type, quality / 100);
  // Release the backing store rather than waiting for GC: the next rung
  // allocates its own, and on a phone three live canvases is where this path
  // starts being killed.
  canvas.width = 0;
  canvas.height = 0;
  if (!blob) return null;
  return { blob, type: blob.type, size: blob.size };
}

/**
 * Walk the ladder until an encode lands inside `budget`.
 *
 * The first rung is a probe: what it actually measured tells predictRung how
 * expensive this particular image is, and the walk then resumes from the rung
 * that is predicted to fit rather than stepping down one at a time. A photo
 * typically costs two encodes; a near-noise worst case costs five.
 */
async function shrink(bitmap: ImageBitmap, sourceMime: string, budget: number): Promise<Encoded | null> {
  const rungs = rungsFor(Math.max(bitmap.width, bitmap.height));
  const probe = await encodeAt(bitmap, rungs[0].edge, rungs[0].quality, "image/jpeg");
  if (!probe) return null;
  let best = probe;
  if (probe.size > budget) {
    const size = targetSize(bitmap.width, bitmap.height, rungs[0].edge);
    const from = predictRung(
      { pixels: size.width * size.height, quality: rungs[0].quality, bytes: probe.size },
      budget,
      rungs,
    );
    for (let i = from; i < rungs.length; i += 1) {
      const candidate = await encodeAt(bitmap, rungs[i].edge, rungs[i].quality, "image/jpeg");
      if (!candidate) continue;
      best = candidate;
      if (candidate.size <= budget) break;
    }
  }
  if (best.size > budget) return null;
  // Only a source that was already lossless can win on PNG. Trying it on a
  // camera photo would spend a second full encode to lose every time.
  if (sourceMime !== "image/png") return best;
  const edge = Math.max(bitmap.width, bitmap.height);
  const png = await encodeAt(bitmap, Math.min(edge, rungs[0].edge), 100, "image/png");
  return chooseOutputType(best, png, budget);
}

async function attachmentOf(blob: Blob, name: string, type: string): Promise<MessageAttachment> {
  return attachmentFrom(new Uint8Array(await blob.arrayBuffer()), name, type);
}

function attachmentFrom(bytes: Uint8Array, name: string, type: string): MessageAttachment {
  return {
    name: name.slice(0, 120) || "attachment",
    content_type: type,
    data: b64u(bytes),
    // From the produced buffer, never from File.size. The send path re-decodes
    // this base64 and rejects the attachment when the two disagree, so a size
    // copied from the source makes the file permanently unsendable the moment
    // a re-encode changes it.
    size: bytes.byteLength,
  };
}

/** `image/jpeg; charset=binary` and `IMAGE/PNG` both have to match the tables. */
function baseMime(value: string): string {
  return value.split(";")[0].trim().toLowerCase();
}

function renamed(name: string, type: string): string {
  if (type !== "image/jpeg") return name;
  return `${name.replace(/\.[^.]*$/, "")}.jpg`;
}

/**
 * Prepare picked files for the composer.
 *
 * Strictly serial on purpose. Decoding in parallel is faster and is also the
 * shape that runs a phone browser out of memory on the third photo.
 */
export async function prepareAttachments(
  files: File[],
  existing: readonly MessageAttachment[],
): Promise<PrepareResult> {
  const spent = existing.reduce((sum, item) => sum + item.size, 0);
  const items: BudgetItem[] = files.map((file) => ({
    name: file.name,
    size: file.size,
    shrinkable: isShrinkableImage(file.type),
  }));
  const plan = allocateBudgets(
    items,
    COMPOSE_ATTACHMENT_BUDGET - spent,
    existing.filter((item) => isShrinkableImage(item.content_type) || isPassThroughImage(item.content_type)).length,
  );
  if (plan.rejection) return { attachments: [], errors: [plan.rejection] };

  const attachments: MessageAttachment[] = [];
  const errors: string[] = [];
  for (let i = 0; i < files.length; i += 1) {
    const file = files[i];
    const budget = plan.budgets[i];
    const mime = baseMime(file.type);
    try {
      if (!isShrinkableImage(mime)) {
        // A GIF loses its animation to a re-encode and everything else is not
        // an image at all; both travel as-is or not at all.
        if (file.size > budget) {
          errors.push(`'${file.name}' 파일이 너무 커서 보낼 수 없습니다`);
          continue;
        }
        attachments.push(await attachmentOf(file, file.name, mime || "application/octet-stream"));
        continue;
      }
      if (file.size > MAX_SOURCE_BYTES) {
        errors.push(`'${file.name}' 사진이 너무 큽니다`);
        continue;
      }
      // A source that already fits travels as it is. Re-encoding it would only
      // hurt: there is no rung below a 1.6KB logo, so the ladder used to run
      // out and refuse the picture outright, and a screenshot forced to reach
      // its own byte count comes back with unreadable text. Metadata still has
      // to go, which is the whole reason this is not a bare pass-through.
      const original = stripMetadata(new Uint8Array(await file.arrayBuffer()), mime);
      if (original.byteLength <= budget) {
        attachments.push(attachmentFrom(original, file.name, mime));
        continue;
      }
      const bitmap = await decode(file);
      try {
        if (bitmap.width * bitmap.height > MAX_SOURCE_PIXELS) {
          errors.push(`'${file.name}' 사진의 해상도가 너무 높습니다`);
          continue;
        }
        const encoded = await shrink(bitmap, mime, budget);
        if (!encoded) {
          errors.push(`'${file.name}' 사진을 줄이지 못했습니다`);
          continue;
        }
        attachments.push(await attachmentOf(encoded.blob, renamed(file.name, encoded.type), encoded.type));
      } finally {
        bitmap.close();
      }
    } catch {
      // Every failure here is the same failure to the user: the browser could
      // not read this file. HEIC on anything but Safari lands here, and so
      // does a content URI the picker revoked between choosing and reading.
      errors.push(`'${file.name}' 파일을 읽지 못했습니다`);
    }
  }
  return { attachments, errors };
}
