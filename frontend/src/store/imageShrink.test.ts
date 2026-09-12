import { describe, expect, it } from "vitest";
import { MAX_ATTACHMENT_BYTES } from "./helpers";
import {
  allocateBudgets,
  stripMetadata,
  chooseOutputType,
  COMPOSE_ATTACHMENT_BUDGET,
  COMPOSE_MIN_PER_IMAGE,
  isPassThroughImage,
  isShrinkableImage,
  MAX_IMAGES_PER_MESSAGE,
  predictRung,
  rungsFor,
  SHRINK_RUNGS,
  targetSize,
  type BudgetItem,
} from "./imageShrink";

const KB = 1024;

function image(name: string, size: number): BudgetItem {
  return { name, size, shrinkable: true };
}

function file(name: string, size: number): BudgetItem {
  return { name, size, shrinkable: false };
}

describe("wire limits the shrink budget hangs off", () => {
  it("stays strictly under the cap both codecs enforce", () => {
    // MAX_ATTACHMENT_BYTES is mirrored byte-for-byte in the Android codec and
    // decodeRelayContent DROPS anything over it without an error, so raising
    // one side alone makes attachments disappear on the other device. The
    // composer budget must stay below it rather than the cap moving up.
    expect(MAX_ATTACHMENT_BYTES).toBe(512 * KB);
    expect(COMPOSE_ATTACHMENT_BUDGET).toBeLessThan(MAX_ATTACHMENT_BYTES);
  });

  it("derives the image count from the budget and the per-image floor", () => {
    expect(MAX_IMAGES_PER_MESSAGE).toBe(5);
    expect(MAX_IMAGES_PER_MESSAGE * COMPOSE_MIN_PER_IMAGE).toBeLessThanOrEqual(COMPOSE_ATTACHMENT_BUDGET);
  });

  it("keeps quality an integer percent, as Bitmap.compress takes it", () => {
    for (const rung of SHRINK_RUNGS) {
      expect(Number.isInteger(rung.quality)).toBe(true);
      expect(rung.quality).toBeGreaterThan(0);
      expect(rung.quality).toBeLessThanOrEqual(100);
    }
  });
});

describe("rungsFor", () => {
  it("clamps every rung to a 900px source and never upscales it", () => {
    const rungs = rungsFor(900);
    expect(rungs.every((rung) => rung.edge <= 900)).toBe(true);
    expect(rungs[0]).toEqual({ edge: 900, quality: 82 });
    // 900 collides with nothing: the two 58s and the two 50s still differ by
    // edge (900/800 and 900/640), so all eight rungs survive.
    expect(rungs).toHaveLength(SHRINK_RUNGS.length);
  });

  it("leaves a source larger than the top rung alone", () => {
    expect(rungsFor(6000)).toEqual([...SHRINK_RUNGS]);
  });

  it("collapses the duplicates a small source produces and still ends at the floor", () => {
    const rungs = rungsFor(600);
    expect(rungs).toEqual([
      { edge: 600, quality: 82 },
      { edge: 600, quality: 68 },
      { edge: 600, quality: 72 },
      { edge: 600, quality: 58 },
      { edge: 600, quality: 66 },
      { edge: 600, quality: 50 },
    ]);
    expect(rungs[rungs.length - 1]).toEqual({ edge: 600, quality: 50 });
  });

  it("always ends at the clamped floor, is never empty and repeats no rung", () => {
    const floor = SHRINK_RUNGS[SHRINK_RUNGS.length - 1];
    for (const source of [1, 120, 639, 640, 641, 799, 801, 1023, 1100, 1280, 1599, 1601, 4032]) {
      const rungs = rungsFor(source);
      expect(rungs.length).toBeGreaterThan(0);
      expect(rungs[rungs.length - 1]).toEqual({
        edge: Math.min(floor.edge, source),
        quality: floor.quality,
      });
      const keys = new Set(rungs.map((rung) => `${rung.edge}x${rung.quality}`));
      expect(keys.size).toBe(rungs.length);
      expect(rungs.every((rung) => rung.edge <= source)).toBe(true);
    }
  });
});

describe("targetSize", () => {
  it("puts the long edge on the rung and keeps the ratio", () => {
    expect(targetSize(4000, 3000, 1600)).toEqual({ width: 1600, height: 1200 });
    expect(targetSize(3000, 4000, 1600)).toEqual({ width: 1200, height: 1600 });
  });

  it("never upscales a source shorter than the rung", () => {
    expect(targetSize(800, 600, 1600)).toEqual({ width: 800, height: 600 });
  });

  it("never returns a zero dimension for a panorama", () => {
    const size = targetSize(4000, 3, 640);
    expect(size).toEqual({ width: 640, height: 1 });
  });
});

describe("predictRung", () => {
  const budget = COMPOSE_ATTACHMENT_BUDGET;

  it("sends a 12MP probe straight to a rung that fits", () => {
    // 4000x3000 encoded at the top rung: 1600x1200 came out at 380KB, nearly
    // double the budget, so rungs 0 and 1 are measurements not worth taking.
    const index = predictRung({ pixels: 1600 * 1200, quality: 82, bytes: 380 * KB }, budget, SHRINK_RUNGS);
    expect(index).toBe(2);
    expect(SHRINK_RUNGS[index]).toEqual({ edge: 1280, quality: 72 });
    expect(index).toBeLessThan(SHRINK_RUNGS.length - 1);
  });

  it("never jumps backwards past the rung the probe came from", () => {
    const index = predictRung({ pixels: 1024 * 768, quality: 66, bytes: 40 * KB }, budget, SHRINK_RUNGS);
    expect(index).toBe(4);
  });

  it("reads a repeated quality off the pixel count, not off the first match", () => {
    // Quality 58 sits at rung 3 (1280) and rung 6 (800). 800x600 cannot have
    // come off the 1280 rung, so the answer must stay at 6.
    const index = predictRung({ pixels: 800 * 600, quality: 58, bytes: 200 * KB }, budget, SHRINK_RUNGS);
    expect(index).toBe(6);
  });

  it("stops at the floor when nothing on the ladder is predicted to fit", () => {
    const huge = { pixels: 1600 * 1200, quality: 82, bytes: 20 * 1024 * KB };
    expect(predictRung(huge, budget, SHRINK_RUNGS)).toBe(SHRINK_RUNGS.length - 1);
    const collapsed = rungsFor(600);
    expect(predictRung(huge, budget, collapsed)).toBe(collapsed.length - 1);
  });

  it("keeps its own promise: the chosen rung is predicted under the budget", () => {
    const probe = { pixels: 1600 * 1200, quality: 82, bytes: 380 * KB };
    const chosen = SHRINK_RUNGS[predictRung(probe, budget, SHRINK_RUNGS)];
    const scale = chosen.edge / 1600;
    const quality = chosen.quality / probe.quality;
    expect(probe.bytes * scale * scale * quality * quality).toBeLessThanOrEqual(budget);
  });
});

describe("which formats get re-encoded", () => {
  it("accepts the camera and screenshot formats", () => {
    for (const mime of ["image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic", "image/heif", "image/bmp"]) {
      expect(isShrinkableImage(mime)).toBe(true);
    }
    expect(isShrinkableImage("IMAGE/JPEG")).toBe(true);
    expect(isShrinkableImage("image/jpeg; charset=binary")).toBe(true);
  });

  it("refuses animation and non-images", () => {
    for (const mime of ["image/gif", "application/pdf", "text/vcard", "text/x-vcard", "application/octet-stream", ""]) {
      expect(isShrinkableImage(mime)).toBe(false);
    }
  });

  it("passes GIF through and nothing else", () => {
    expect(isPassThroughImage("image/gif")).toBe(true);
    expect(isPassThroughImage("image/jpeg")).toBe(false);
    expect(isPassThroughImage("application/pdf")).toBe(false);
  });
});

describe("chooseOutputType", () => {
  const budget = 100 * KB;

  it("keeps a PNG that is both smaller and inside the budget", () => {
    const jpeg = { type: "image/jpeg", size: 90 * KB };
    const png = { type: "image/png", size: 40 * KB };
    expect(chooseOutputType(jpeg, png, budget)).toBe(png);
  });

  it("takes the JPEG when the PNG is larger or over the budget", () => {
    const jpeg = { type: "image/jpeg", size: 60 * KB };
    expect(chooseOutputType(jpeg, { type: "image/png", size: 80 * KB }, budget)).toBe(jpeg);
    expect(chooseOutputType(jpeg, null, budget)).toBe(jpeg);
    const overBudget = { type: "image/jpeg", size: 200 * KB };
    expect(chooseOutputType(overBudget, { type: "image/png", size: 150 * KB }, budget)).toBe(overBudget);
  });

  it("falls back to JPEG when toBlob silently substituted the type", () => {
    // canvas.toBlob answers a format it cannot write with image/png (or
    // whatever it does support) instead of failing, so a candidate that is not
    // the type that was asked for is not that type.
    const jpeg = { type: "image/jpeg", size: 90 * KB };
    const substituted = { type: "image/jpeg", size: 30 * KB };
    expect(chooseOutputType(jpeg, substituted, budget)).toBe(jpeg);
  });

  it("does not hand back a JPEG slot that came out as PNG", () => {
    const png = { type: "image/png", size: 50 * KB };
    expect(chooseOutputType({ type: "image/png", size: 400 * KB }, png, budget)).toBe(png);
    expect(chooseOutputType({ type: "image/png", size: 400 * KB }, null, budget)).toBeNull();
  });
});

describe("allocateBudgets", () => {
  it("splits the budget evenly across three photos", () => {
    const plan = allocateBudgets([image("a.jpg", 3e6), image("b.jpg", 4e6), image("c.jpg", 5e6)]);
    expect(plan.rejection).toBeNull();
    expect(plan.budgets).toEqual([81920, 81920, 81920]);
    expect(plan.budgets.reduce((sum, value) => sum + value, 0)).toBeLessThanOrEqual(COMPOSE_ATTACHMENT_BUDGET);
  });

  it("reserves a PDF at its exact size and splits only what is left", () => {
    const plan = allocateBudgets([file("계약서.pdf", 100 * KB), image("a.jpg", 3e6), image("b.jpg", 3e6)]);
    expect(plan.rejection).toBeNull();
    expect(plan.budgets).toEqual([100 * KB, 71680, 71680]);
  });

  it("re-credits an image that came in small to the images after it", () => {
    const plan = allocateBudgets([image("small.jpg", 20 * KB), image("b.jpg", 5e6), image("c.jpg", 5e6)]);
    expect(plan.rejection).toBeNull();
    expect(plan.budgets).toEqual([20 * KB, 112640, 112640]);
    expect(plan.budgets.reduce((sum, value) => sum + value, 0)).toBeLessThanOrEqual(COMPOSE_ATTACHMENT_BUDGET);
  });

  it("re-credits the same way whichever order the small photo was picked in", () => {
    // Pick order is whatever the file dialog handed back, so a budget that
    // depended on it would silently give the same three photos different
    // quality from one attempt to the next.
    const first = allocateBudgets([image("small.jpg", 20 * KB), image("b.jpg", 5e6), image("c.jpg", 5e6)]);
    const last = allocateBudgets([image("b.jpg", 5e6), image("c.jpg", 5e6), image("small.jpg", 20 * KB)]);
    expect(last.budgets).toEqual([112640, 112640, 20 * KB]);
    expect([...last.budgets].sort()).toEqual([...first.budgets].sort());
  });

  it("refuses a sixth photo by the derived count", () => {
    const plan = allocateBudgets(Array.from({ length: 6 }, (_, i) => image(`${i}.jpg`, 3e6)));
    expect(plan.rejection).toBe(`사진은 한 번에 최대 ${MAX_IMAGES_PER_MESSAGE}장까지 보낼 수 있습니다`);
    expect(plan.rejection).toContain("최대 5장");
    expect(plan.budgets).toEqual([]);
  });

  it("refuses an oversized non-image by name", () => {
    const plan = allocateBudgets([file("여행계획.pdf", 900 * KB), image("a.jpg", 3e6)]);
    expect(plan.rejection).toContain("여행계획.pdf");
    expect(plan.budgets).toEqual([]);
  });

  it("refuses photos that no longer have room next to the other files", () => {
    const plan = allocateBudgets([file("메모.pdf", 60 * KB), ...Array.from({ length: 5 }, (_, i) => image(`${i}.jpg`, 3e6))]);
    expect(plan.rejection).toBe("다른 첨부파일이 커서 사진을 함께 보낼 수 없습니다");
  });

  it("sends a small GIF untouched and refuses a large one rather than transcoding it", () => {
    const small = { name: "웃음.gif", size: 90 * KB, shrinkable: isShrinkableImage("image/gif") };
    const plan = allocateBudgets([small, image("a.jpg", 3e6)]);
    expect(plan.rejection).toBeNull();
    // Reserved at its exact size: re-encoding it is not on the table, so it
    // either rides along whole or the message is refused.
    expect(plan.budgets[0]).toBe(90 * KB);
    expect(isPassThroughImage("image/gif")).toBe(true);

    const big = { name: "긴애니.gif", size: 900 * KB, shrinkable: isShrinkableImage("image/gif") };
    const refused = allocateBudgets([big]);
    expect(refused.rejection).toContain("긴애니.gif");
    expect(refused.budgets).toEqual([]);
  });

  it("allows an empty pick and a lone non-image that fits", () => {
    expect(allocateBudgets([])).toEqual({ budgets: [], rejection: null });
    expect(allocateBudgets([file("명함.vcf", 4 * KB)])).toEqual({ budgets: [4 * KB], rejection: null });
  });
});

describe("stripMetadata", () => {
  const jpeg = (...segments: number[][]) =>
    Uint8Array.from([0xff, 0xd8, ...segments.flat(), 0xff, 0xda, 0x00, 0x02, 0x01, 0x02, 0x03]);
  const segment = (marker: number, body: number[]) =>
    [0xff, marker, 0x00, body.length + 2, ...body];

  it("drops the JPEG segment GPS lives in and keeps the colour profile", () => {
    const before = jpeg(
      segment(0xe0, [0x4a, 0x46, 0x49, 0x46]),   // APP0 JFIF
      segment(0xe1, [0x45, 0x78, 0x69, 0x66]),   // APP1 EXIF
      segment(0xe2, [0x49, 0x43, 0x43]),         // APP2 ICC
      segment(0xfe, [0x68, 0x69]),               // COM
    );
    const after = stripMetadata(before, "image/jpeg");
    // The EXIF and the comment are gone; JFIF and the ICC profile stay, because
    // neither says where the picture was taken and dropping the profile shifts
    // the colours of every wide-gamut phone photo.
    // APP1 is marker + length + 4 body = 8 bytes, COM is 2 + 2 + 2 = 6.
    expect(after.length).toBe(before.length - 8 - 6);
    expect(Array.from(after)).toContain(0xe0);
    expect(Array.from(after)).toContain(0xe2);
    // Entropy-coded data after the start of scan is copied verbatim.
    expect(Array.from(after.slice(-5))).toEqual([0x00, 0x02, 0x01, 0x02, 0x03]);
  });

  it("drops the PNG metadata chunks and keeps the pixels", () => {
    const chunk = (name: string, body: number[]) => [
      0, 0, 0, body.length, ...Array.from(name, (ch) => ch.charCodeAt(0)), ...body, 0, 0, 0, 0,
    ];
    const before = Uint8Array.from([
      0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
      ...chunk("IHDR", [1, 2, 3]), ...chunk("tEXt", [9, 9]), ...chunk("eXIf", [7]),
      ...chunk("IDAT", [4, 5]), ...chunk("IEND", []),
    ]);
    const after = stripMetadata(before, "image/png");
    const names = new TextDecoder("latin1").decode(after);
    expect(names).not.toContain("tEXt");
    expect(names).not.toContain("eXIf");
    expect(names).toContain("IHDR");
    expect(names).toContain("IDAT");
    expect(names).toContain("IEND");
  });

  it("returns anything it does not understand untouched", () => {
    // Refusing to strip is safe; guessing at a container's structure is not.
    for (const [bytes, mime] of [
      [Uint8Array.from([1, 2, 3]), "image/webp"],
      [Uint8Array.from([0xff, 0xd8, 0x00]), "image/jpeg"],      // not segment-aligned
      [Uint8Array.from([0x89, 0x50, 0x4e]), "image/png"],       // truncated signature
    ] as const) {
      expect(stripMetadata(bytes, mime)).toBe(bytes);
    }
  });

  it("leaves a file with no metadata byte-identical", () => {
    const clean = jpeg(segment(0xe0, [0x4a, 0x46, 0x49, 0x46]));
    expect(stripMetadata(clean, "image/jpeg")).toBe(clean);
  });
});
