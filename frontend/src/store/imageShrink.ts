/**
 * Shrink policy for outgoing image attachments — rules only, no DOM.
 *
 * The wire limits do not move: MAX_ATTACHMENT_BYTES is mirrored byte-for-byte
 * in the Android codec and decodeRelayContent silently DROPS anything over
 * them, so raising one side turns a photo into an attachment that vanishes on
 * the other device with no error anywhere. A 3-12MB camera photo therefore has
 * to be made smaller before it reaches the envelope, not admitted by a bigger
 * cap.
 *
 * Everything here is pure and DOM-free: the same ladder and the same budget
 * arithmetic also exist in the gateway app's Kotlin module, and the only way
 * to keep the two encoders producing the same file is to keep the policy
 * testable in isolation from canvas/Blob. The browser-side encoding lives in
 * the DOM shim that imports this module.
 */

/**
 * Total attachment budget the composer aims at for one message.
 *
 * Deliberately far below MAX_ATTACHMENT_BYTES: the carrier, not the relay, is
 * the binding limit here — a Korean MMS PDU tops out near 300KiB, and the
 * caption, subject and MIME framing have to fit inside it next to the
 * pictures. Encoding up to the wire cap produces messages the relay accepts
 * and the carrier truncates.
 */
export const COMPOSE_ATTACHMENT_BUDGET = 240 * 1024;

/**
 * Smallest share an image may be asked to encode into. Below this the ladder
 * runs out of rungs before it reaches the target and the picture comes out
 * unreadable, so the allocator refuses instead of shipping mush.
 */
export const COMPOSE_MIN_PER_IMAGE = 48 * 1024;

/**
 * Derived, never a literal: the count only holds while it is the budget
 * divided by the floor. A hardcoded 5 left next to a changed budget hands the
 * last image a share no rung can hit.
 */
export const MAX_IMAGES_PER_MESSAGE = Math.floor(COMPOSE_ATTACHMENT_BUDGET / COMPOSE_MIN_PER_IMAGE);

/**
 * Refused before decoding, because decoding is what costs: a browser expands
 * an image to RGBA, so 80MP is ~320MB of canvas and the tab dies before any
 * rung is measured. Enforced by the DOM shim, which is the only place that
 * knows the source's byte length and pixel count.
 */
export const MAX_SOURCE_BYTES = 32 * 1024 * 1024;
export const MAX_SOURCE_PIXELS = 80_000_000;

export interface ShrinkRung {
  /** Long-edge cap in pixels. */
  edge: number;
  /**
   * JPEG quality as an INTEGER PERCENT. Kotlin's Bitmap.compress takes the
   * percent directly; canvas.toBlob wants 0-1 and the TS encoder divides by
   * 100. Storing the percent is what keeps the two ladders comparable by eye.
   */
  quality: number;
}

/**
 * The shared ladder, highest fidelity first.
 *
 * Two rungs per edge on the way down: quality is spent before pixels, because
 * a softer re-encode at the same resolution still reads as the same photo
 * while a downscale throws detail away permanently. The pair also gives the
 * predictor a cheap second try at each size before it drops resolution.
 *
 * This table is duplicated in the Android module. Changing a row here without
 * changing it there makes the two devices produce visibly different files from
 * the same picture.
 */
export const SHRINK_RUNGS: readonly ShrinkRung[] = [
  { edge: 1600, quality: 82 },
  { edge: 1600, quality: 68 },
  { edge: 1280, quality: 72 },
  { edge: 1280, quality: 58 },
  { edge: 1024, quality: 66 },
  { edge: 1024, quality: 50 },
  { edge: 800, quality: 58 },
  { edge: 640, quality: 50 },
];

/**
 * The ladder as it applies to one source.
 *
 * Every edge is clamped down to the source: re-encoding a 900px photo "at
 * 1600" upscales it, which costs bytes and adds nothing. Clamping makes rungs
 * collide, and a collapsed duplicate is a rung the encoder would measure
 * twice for the identical result — so they are dropped, keeping the first
 * occurrence. The list always ends at the clamped floor and is never empty;
 * callers index into it.
 */
export function rungsFor(sourceLongEdge: number): ShrinkRung[] {
  const source = Math.max(1, Math.floor(sourceLongEdge));
  const rungs: ShrinkRung[] = [];
  for (const rung of SHRINK_RUNGS) {
    const edge = Math.min(rung.edge, source);
    if (rungs.some((kept) => kept.edge === edge && kept.quality === rung.quality)) continue;
    rungs.push({ edge, quality: rung.quality });
  }
  return rungs;
}

export interface PixelSize {
  width: number;
  height: number;
}

/**
 * Draw size for a rung: the long edge becomes `edge` (never more than the
 * source has), the aspect ratio is preserved, and the short edge floors to at
 * least 1 — a panorama scaled to a 0-pixel height is a canvas the browser
 * refuses to encode at all.
 */
export function targetSize(srcW: number, srcH: number, edge: number): PixelSize {
  const width = Math.max(1, Math.floor(srcW));
  const height = Math.max(1, Math.floor(srcH));
  const long = Math.max(width, height);
  const target = Math.max(1, Math.min(Math.floor(edge), long));
  if (target === long) return { width, height };
  if (width >= height) return { width: target, height: Math.max(1, Math.floor((height * target) / width)) };
  return { width: Math.max(1, Math.floor((width * target) / height)), height: target };
}

export interface RungProbe {
  /** Pixel count of the encoded probe, width * height. */
  pixels: number;
  /** Integer percent the probe was encoded at. */
  quality: number;
  bytes: number;
}

/**
 * Predictions are fitted from one measurement, so they are only roughly right.
 * Aiming at 90% of the budget leaves room for the miss; aiming at 100% makes
 * every underestimate cost another full encode.
 */
const PREDICTION_HEADROOM = 0.9;

/**
 * Which rung `probe` was encoded at.
 *
 * Quality alone is ambiguous — 58 and 50 each appear twice on the ladder — but
 * an image of `pixels` pixels cannot have come off a rung whose edge squared is
 * smaller, which separates the pair for any normal aspect ratio. Ties resolve
 * to the LOWER rung: assuming the probe came from a smaller encode only costs
 * a little quality, while assuming a larger one makes the predictor scale
 * every rung down from a size the probe never had and hand back an index that
 * overshoots the budget. A quality that is on no rung anchors at 0, which
 * simply leaves the whole ladder available.
 */
function probeRungIndex(probe: RungProbe, rungs: readonly ShrinkRung[]): number {
  let anchor = 0;
  for (let i = 0; i < rungs.length; i++) {
    if (rungs[i].quality === probe.quality && rungs[i].edge * rungs[i].edge >= probe.pixels) anchor = i;
  }
  return anchor;
}

/**
 * The rung to encode next, given what one probe encode actually weighed.
 *
 * JPEG size runs roughly with pixel count and with the square of quality, so a
 * single measurement is enough to skip the rungs that cannot possibly fit —
 * which is the whole point: each rung costs a full decode-and-encode pass on
 * the phone, and walking a 12MP photo down eight of them is seconds of frozen
 * UI. Two clamps bound the guess: never before the probe's own rung (going
 * back up re-measures a size already known to be too big) and never past the
 * floor (the lowest rung is the last thing the ladder can offer, fitting or
 * not — the caller, not the predictor, decides to give up).
 *
 * `rungs` must be a rungsFor result: non-empty, clamped to the source.
 */
export function predictRung(probe: RungProbe, budget: number, rungs: readonly ShrinkRung[]): number {
  const floor = rungs.length - 1;
  const anchor = probeRungIndex(probe, rungs);
  const anchorEdge = rungs[anchor].edge;
  const target = budget * PREDICTION_HEADROOM;
  for (let i = anchor; i <= floor; i++) {
    // Clamped because rungsFor may leave equal edges next to each other; an
    // edge above the anchor's would predict an upscale that never happens.
    const scale = Math.min(rungs[i].edge, anchorEdge) / anchorEdge;
    const quality = rungs[i].quality / probe.quality;
    if (probe.bytes * scale * scale * quality * quality <= target) return i;
  }
  return floor;
}

function baseMime(value: string): string {
  return value.split(";", 1)[0].trim().toLowerCase();
}

/**
 * HEIC/HEIF are listed although only Safari decodes them: there the iPhone's
 * native photo format shrinks like any other, and everywhere else the decode
 * fails and the shim refuses the file — still better than attaching a 4MB HEIC
 * the decoder on the far side would drop.
 */
const SHRINKABLE_MIME = new Set([
  "image/jpeg",
  // Not an IANA type, but cameras and mail gateways emit it. Calling it
  // unshrinkable would refuse a photo the decoder handles perfectly well.
  "image/jpg",
  "image/png",
  "image/webp",
  "image/heic",
  "image/heif",
  "image/bmp",
]);

/** Formats a canvas round-trip can make smaller without destroying them. */
export function isShrinkableImage(mime: string): boolean {
  return SHRINKABLE_MIME.has(baseMime(mime));
}

/**
 * A canvas re-encode keeps frame one and silently drops the animation, so a
 * GIF is sent byte-for-byte or not at all.
 */
export function isPassThroughImage(mime: string): boolean {
  return baseMime(mime) === "image/gif";
}

export interface EncodedCandidate {
  /** The blob's ACTUAL type, never the type that was requested. */
  type: string;
  size: number;
}

function actually<T extends EncodedCandidate>(candidate: T | null | undefined, type: string): T | null {
  return candidate && baseMime(candidate.type) === type ? candidate : null;
}

/**
 * Which encode to send.
 *
 * JPEG by default; PNG only when it is genuinely smaller AND already inside
 * the budget, which happens for screenshots and flat graphics where JPEG
 * ringing costs more bytes than lossless does.
 *
 * Both candidates are checked against their own declared type first, because
 * canvas.toBlob does not fail on a format the browser cannot write — it
 * silently hands back image/png for whatever was asked. Trusting the request
 * would attach a PNG labelled JPEG, which is the one thing the far side has no
 * way to notice.
 */
export function chooseOutputType<T extends EncodedCandidate>(
  jpegCandidate: T | null | undefined,
  pngCandidate: T | null | undefined,
  budget: number,
): T | null {
  const jpeg = actually(jpegCandidate, "image/jpeg");
  const png = actually(pngCandidate, "image/png");
  if (png && png.size <= budget && (!jpeg || png.size < jpeg.size)) return png;
  return jpeg ?? png ?? null;
}

export interface BudgetItem {
  name: string;
  size: number;
  /** Whether a re-encode can make it smaller — see isShrinkableImage. */
  shrinkable: boolean;
}

export interface BudgetPlan {
  /** Parallel to `items`, same order. Empty when `rejection` is set. */
  budgets: number[];
  /** User-facing Korean refusal, or null when the split works out. */
  rejection: string | null;
}

function kib(bytes: number): number {
  return Math.floor(bytes / 1024);
}

function refuse(message: string): BudgetPlan {
  return { budgets: [], rejection: message };
}

/**
 * Split one message's budget over the picked files, in pick order.
 *
 * Non-shrinkable files are RESERVED at their exact size before anything is
 * divided: a PDF cannot be made to fit a share, so the shares have to be cut
 * from what is left after it. The images then split the remainder evenly, and
 * each one that needs less than its share — because its source is already
 * smaller than the share, and is passed through untouched — re-credits the
 * difference to the images that still need it.
 *
 * The re-credit settles repeatedly rather than sweeping once in pick order, so
 * the split does not depend on which file the user happened to choose first:
 * picking a 20 KB thumbnail after a 3 MB photo has to give that photo the same
 * budget as picking it before. ImageShrinkPolicy.allocate on the Android side
 * resolves this identically, and the two are expected to agree.
 */
export function allocateBudgets(
  items: readonly BudgetItem[],
  total = COMPOSE_ATTACHMENT_BUDGET,
  /**
   * Photos already staged in the composer. They are re-encoded once and then
   * reserved at their finished size, so they take no share here — but they
   * still occupy slots, and without counting them a sixth photo could be
   * added one pick at a time.
   */
  reservedImages = 0,
): BudgetPlan {
  const sharing = items.filter((item) => item.shrinkable).length;
  const images = reservedImages + sharing;
  if (images > MAX_IMAGES_PER_MESSAGE) {
    return refuse(`사진은 한 번에 최대 ${MAX_IMAGES_PER_MESSAGE}장까지 보낼 수 있습니다`);
  }
  let reserved = 0;
  for (const item of items) {
    if (item.shrinkable) continue;
    // Nothing downstream can shrink it, so name it: "attachments are too big"
    // leaves the user removing pictures that were never the problem.
    if (item.size > total) {
      return refuse(`'${item.name}' 파일이 너무 커서 보낼 수 없습니다 (최대 ${kib(total)}KB)`);
    }
    reserved += item.size;
  }
  if (reserved > total) return refuse(`첨부파일 전체 크기가 ${kib(total)}KB를 넘습니다`);
  let remaining = total - reserved;
  if (sharing > 0 && remaining < sharing * COMPOSE_MIN_PER_IMAGE) {
    return refuse("다른 첨부파일이 커서 사진을 함께 보낼 수 없습니다");
  }
  const budgets = items.map((item) => (item.shrinkable ? -1 : item.size));
  let left = sharing;
  // Settle every image that already fits its share, then re-split what they
  // left behind over the ones that do not. Each pass can only free budget, so
  // shares never shrink and the loop ends once a pass settles nothing.
  let settled = true;
  while (settled && left > 0) {
    settled = false;
    const share = Math.floor(remaining / left);
    items.forEach((item, index) => {
      if (budgets[index] !== -1 || item.size > share) return;
      budgets[index] = item.size;
      remaining -= item.size;
      left -= 1;
      settled = true;
    });
  }
  if (left > 0) {
    const share = Math.floor(remaining / left);
    budgets.forEach((budget, index) => {
      if (budget === -1) budgets[index] = share;
    });
  }
  return { budgets, rejection: null };
}

/**
 * Strip metadata from an image without touching a pixel.
 *
 * A source that already fits its allocation should travel as it is rather than
 * being re-encoded to hit a byte count it already meets — a 1.6 KB logo has no
 * rung that beats it, and re-encoding a screenshot to reach its own size makes
 * the text in it unreadable. But an untouched camera file carries EXIF, and
 * EXIF carries GPS: an SMS leaves the E2E boundary at the gateway and reaches a
 * stranger's handset through the carrier, so home coordinates must not ride
 * along. Removing the metadata segments is what makes the pass-through safe.
 *
 * Only container framing is rewritten, so the decoded image is bit-identical.
 * Anything unrecognised is returned unchanged: refusing to strip is safe, and
 * guessing at a format's structure is not.
 */
export function stripMetadata(bytes: Uint8Array, mime: string): Uint8Array {
  const type = mime.split(";")[0].trim().toLowerCase();
  if (type === "image/jpeg" || type === "image/jpg") return stripJpeg(bytes);
  if (type === "image/png") return stripPng(bytes);
  return bytes;
}

/**
 * Drop APP1 (EXIF and XMP, which is where GPS lives) and COM.
 *
 * APP0 (JFIF) and APP2 (the ICC colour profile) are deliberately kept: neither
 * says where the picture was taken, and dropping the profile would shift the
 * colours of every wide-gamut phone photo.
 */
function stripJpeg(bytes: Uint8Array): Uint8Array {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return bytes;
  const keep: Array<[number, number]> = [[0, 2]];
  let at = 2;
  while (at + 3 < bytes.length) {
    if (bytes[at] !== 0xff) return bytes; // Not segment-aligned; do not guess.
    const marker = bytes[at + 1];
    // Start of scan: everything from here to the end is entropy-coded data.
    if (marker === 0xda) {
      keep.push([at, bytes.length]);
      break;
    }
    const length = (bytes[at + 2] << 8) | bytes[at + 3];
    if (length < 2 || at + 2 + length > bytes.length) return bytes;
    const drop = marker === 0xe1 || marker === 0xfe;
    if (!drop) keep.push([at, at + 2 + length]);
    at += 2 + length;
  }
  return splice(bytes, keep);
}

/** Drop the chunks that carry EXIF, comments and timestamps. */
function stripPng(bytes: Uint8Array): Uint8Array {
  const SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (bytes.length < 8 || SIGNATURE.some((value, i) => bytes[i] !== value)) return bytes;
  const dropped = new Set(["eXIf", "tEXt", "iTXt", "zTXt", "tIME"]);
  const keep: Array<[number, number]> = [[0, 8]];
  let at = 8;
  while (at + 8 <= bytes.length) {
    const length = (bytes[at] << 24 | bytes[at + 1] << 16 | bytes[at + 2] << 8 | bytes[at + 3]) >>> 0;
    const end = at + 12 + length;
    if (end > bytes.length) return bytes;
    const name = String.fromCharCode(bytes[at + 4], bytes[at + 5], bytes[at + 6], bytes[at + 7]);
    if (!dropped.has(name)) keep.push([at, end]);
    at = end;
    if (name === "IEND") break;
  }
  return splice(bytes, keep);
}

function splice(bytes: Uint8Array, keep: Array<[number, number]>): Uint8Array {
  const total = keep.reduce((sum, [from, to]) => sum + (to - from), 0);
  if (total === bytes.length) return bytes;
  const out = new Uint8Array(total);
  let at = 0;
  for (const [from, to] of keep) {
    out.set(bytes.subarray(from, to), at);
    at += to - from;
  }
  return out;
}
