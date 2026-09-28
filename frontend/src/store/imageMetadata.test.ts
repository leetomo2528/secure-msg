import { beforeAll, describe, expect, it } from "vitest";
import { initCrypto, unb64u } from "../crypto/keys";
import { buildAttachment, stripImageMetadata } from "./imageMetadata";

function join(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

const SOI = Uint8Array.from([0xff, 0xd8]);

function jpegSegment(marker: number, body: number[]): Uint8Array {
  const length = body.length + 2;
  return Uint8Array.from([0xff, marker, length >> 8, length & 0xff, ...body]);
}

const app0 = jpegSegment(0xe0, [0x4a, 0x46, 0x49, 0x46, 0, 1, 1]); // JFIF\0
const app1 = jpegSegment(0xe1, [
  0x45, 0x78, 0x69, 0x66, 0, 0, // Exif\0\0
  0x49, 0x49, 0x2a, 0, 8, 0, 0, 0, // TIFF header
  1, 0, 0x25, 0x88, 4, 0, 1, 0, 0, 0, 0x47, 0x50, 0x53, 0, // fake GPS IFD
]);
const comment = jpegSegment(0xfe, Array.from(new TextEncoder().encode("shot at home")));
const app2 = jpegSegment(0xe2, [
  ...Array.from(new TextEncoder().encode("ICC_PROFILE")), 0, 1, 1, 0xaa,
]);
const dqt = jpegSegment(0xdb, [0, 1, 2, 3]);
const sof0 = jpegSegment(0xc0, [8, 0, 1, 0, 1, 1, 1, 0x11, 0]);
const dht = jpegSegment(0xc4, [0, 1, 2, 3]);
const sos = jpegSegment(0xda, [1, 1, 0, 0, 0x3f, 0]);
const entropy = Uint8Array.from([0x12, 0xff, 0x00, 0x34, 0xff, 0xd0, 0x56, 0xff, 0xd9, 0x99, 0x88]);

function jpegWithMetadata(): Uint8Array {
  return join(SOI, app0, app1, comment, app2, dqt, sof0, dht, sos, entropy);
}

function hasSequence(bytes: Uint8Array, sequence: number[]): boolean {
  return bytes.some((_, at) => sequence.every((value, offset) => bytes[at + offset] === value));
}

const PNG_SIGNATURE = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function readU32(bytes: Uint8Array, at: number): number {
  return ((bytes[at] << 24) | (bytes[at + 1] << 16) | (bytes[at + 2] << 8) | bytes[at + 3]) >>> 0;
}

function writeU32(bytes: Uint8Array, at: number, value: number): void {
  bytes[at] = value >>> 24;
  bytes[at + 1] = value >>> 16;
  bytes[at + 2] = value >>> 8;
  bytes[at + 3] = value;
}

function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(name: string, body: number[]): Uint8Array {
  const chunk = new Uint8Array(12 + body.length);
  writeU32(chunk, 0, body.length);
  for (let i = 0; i < 4; i += 1) chunk[4 + i] = name.charCodeAt(i);
  chunk.set(body, 8);
  writeU32(chunk, 8 + body.length, crc32(chunk.subarray(4, 8 + body.length)));
  return chunk;
}

const ihdr = pngChunk("IHDR", [0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0]);
const text = pngChunk("tEXt", [0x47, 0x50, 0x53, 0, 0x48, 0x6f, 0x6d, 0x65]);
const exif = pngChunk("eXIf", [0x49, 0x49, 0x2a, 0, 0x47, 0x50, 0x53]);
const internationalText = pngChunk("iTXt", [0x4c, 0x6f, 0x63, 0]);
const compressedText = pngChunk("zTXt", [0x4c, 0x6f, 0x63, 0, 0, 0x78]);
const iccp = pngChunk("iCCP", [0x49, 0x43, 0x43, 0, 0, 0x78]);
const idat = pngChunk("IDAT", [0x78, 0x9c, 0x01, 0x02, 0x03]);
const time = pngChunk("tIME", [0x07, 0xea, 9, 28, 12, 0, 0]);
const iend = pngChunk("IEND", []);

function pngWithMetadata(): Uint8Array {
  return join(PNG_SIGNATURE, ihdr, text, exif, internationalText, compressedText, iccp, idat, time, iend);
}

describe("stripImageMetadata", () => {
  it("removes JPEG APP1/COM while preserving APP0, APP2 and SOS through EOF byte-for-byte", () => {
    const before = jpegWithMetadata();
    const after = stripImageMetadata(before, "image/jpeg");
    const sosBefore = before.length - sos.length - entropy.length;
    const sosAfter = after.length - sos.length - entropy.length;

    expect(after.length).toBe(before.length - app1.length - comment.length);
    expect(hasSequence(after.subarray(0, sosAfter), [0xff, 0xe1])).toBe(false);
    expect(hasSequence(after.subarray(0, sosAfter), [0xff, 0xfe])).toBe(false);
    expect(after.subarray(2, 2 + app0.length)).toEqual(app0);
    expect(after.subarray(2 + app0.length, 2 + app0.length + app2.length)).toEqual(app2);
    expect(after.subarray(sosAfter)).toEqual(before.subarray(sosBefore));
  });

  it("removes PNG metadata and keeps every other chunk and CRC unchanged", () => {
    const before = pngWithMetadata();
    const after = stripImageMetadata(before, "image/png");
    expect(after).toEqual(join(PNG_SIGNATURE, ihdr, iccp, idat, iend));

    const names: string[] = [];
    let at = PNG_SIGNATURE.length;
    while (at < after.length) {
      const length = readU32(after, at);
      names.push(String.fromCharCode(...after.subarray(at + 4, at + 8)));
      expect(readU32(after, at + 8 + length)).toBe(crc32(after.subarray(at + 4, at + 8 + length)));
      at += 12 + length;
    }
    expect(names).toEqual(["IHDR", "iCCP", "IDAT", "IEND"]);
    expect(at).toBe(after.length);
  });

  it("returns malformed or incomplete JPEG framing unchanged", () => {
    const samples = [
      join(SOI, app1, app0), // No SOS, even though metadata was found.
      Uint8Array.from([0xff, 0xd8, 0xff, 0xe1, 0, 20, 1]), // Length past EOF.
      join(SOI, app1, Uint8Array.from([0xff, 0xd9])), // EOI before SOS.
      join(SOI, app1, Uint8Array.from([0xff, 0x01]), sos, entropy), // TEM.
      join(SOI, app1, Uint8Array.from([0xff, 0xd0]), sos, entropy), // RST0.
      join(SOI, app1, SOI, sos, entropy), // Second SOI.
      join(SOI, app1, jpegSegment(0xf0, [1, 2]), sos, entropy), // Reserved marker.
      join(SOI, app1, Uint8Array.from([0xff, 0xda, 0, 20])), // Truncated SOS header.
    ];
    for (const bytes of samples) {
      expect(stripImageMetadata(bytes, "image/jpeg")).toBe(bytes);
    }
  });

  it("handles FF fill bytes before a JPEG marker without corrupting the scan", () => {
    const filledApp0 = join(Uint8Array.from([0xff]), app0);
    const filledApp1 = join(Uint8Array.from([0xff]), app1);
    const before = join(SOI, filledApp0, filledApp1, sos, entropy);
    const after = stripImageMetadata(before, "image/jpeg");
    expect(after).toEqual(join(SOI, filledApp0, sos, entropy));

    // Longer fill runs, including one directly before SOS, keep the same framing.
    const runApp1 = join(Uint8Array.from([0xff, 0xff, 0xff]), app1);
    const runSos = join(Uint8Array.from([0xff, 0xff]), sos);
    const longer = join(SOI, app0, runApp1, comment, runSos, entropy);
    expect(stripImageMetadata(longer, "image/jpeg")).toEqual(join(SOI, app0, runSos, entropy));
  });

  it("returns malformed or incomplete PNG framing unchanged", () => {
    const badSignature = pngWithMetadata();
    badSignature[0] = 0;
    const hugeLength = join(PNG_SIGNATURE, ihdr, text, idat, iend);
    hugeLength[PNG_SIGNATURE.length + ihdr.length] = 0x80;
    const samples = [
      badSignature,
      join(PNG_SIGNATURE, ihdr, text, idat), // No IEND.
      join(PNG_SIGNATURE, ihdr, text, idat, iend.subarray(0, 7)), // Truncated IEND.
      join(PNG_SIGNATURE, text, ihdr, idat, iend), // First chunk is not IHDR.
      join(PNG_SIGNATURE, ihdr, text, iend), // No IDAT.
      join(PNG_SIGNATURE, ihdr, text, ihdr, idat, iend), // Duplicate IHDR.
      join(PNG_SIGNATURE, ihdr, pngChunk("b@d!", [1]), idat, iend), // Invalid chunk name.
      hugeLength,
      join(PNG_SIGNATURE, ihdr, Uint8Array.from([0, 0, 0, 40, 0x74, 0x45, 0x58, 0x74])),
    ];
    for (const bytes of samples) {
      expect(stripImageMetadata(bytes, "image/png")).toBe(bytes);
    }
  });

  it("passes clean images through by identity and discards only PNG bytes after IEND", () => {
    const cleanJpeg = join(SOI, app0, app2, dqt, sof0, dht, sos, entropy);
    const cleanPng = join(PNG_SIGNATURE, ihdr, iccp, idat, iend);
    expect(stripImageMetadata(cleanJpeg, "image/jpeg")).toBe(cleanJpeg);
    expect(stripImageMetadata(cleanPng, "image/png")).toBe(cleanPng);
    expect(stripImageMetadata(join(cleanPng, Uint8Array.from([1, 2])), "image/png")).toEqual(cleanPng);
  });

  it("sniffs images when the MIME is absent or generic, but never mangles a declared mismatch", () => {
    const jpeg = jpegWithMetadata();
    const png = pngWithMetadata();
    const strippedJpeg = stripImageMetadata(jpeg, "image/jpeg");
    const strippedPng = stripImageMetadata(png, "image/png");
    expect(stripImageMetadata(jpeg, "")).toEqual(strippedJpeg);
    expect(stripImageMetadata(jpeg, "application/octet-stream")).toEqual(strippedJpeg);
    expect(stripImageMetadata(png, "application/octet-stream")).toEqual(strippedPng);
    expect(stripImageMetadata(jpeg, " IMAGE/JPG ; charset=binary ")).toEqual(strippedJpeg);
    expect(stripImageMetadata(png, " IMAGE/PNG; charset=binary")).toEqual(strippedPng);
    expect(stripImageMetadata(jpeg, "image/png")).toBe(jpeg);
    expect(stripImageMetadata(png, "image/jpeg")).toBe(png);
  });

  it("returns empty, GIF, ZIP, and text bytes unchanged", () => {
    for (const [bytes, mime] of [
      [new Uint8Array(), "image/jpeg"],
      [new Uint8Array(), "image/png"],
      [Uint8Array.from([0x47, 0x49, 0x46, 0x38]), "image/gif"],
      [Uint8Array.from([0x50, 0x4b, 3, 4]), "application/octet-stream"],
      [new TextEncoder().encode("hello"), "text/plain"],
    ] as const) {
      expect(stripImageMetadata(bytes, mime)).toBe(bytes);
    }
  });
});

describe("buildAttachment", () => {
  beforeAll(async () => { await initCrypto(); });

  it("reports the stripped size and encodes exactly the stripped bytes", () => {
    const before = jpegWithMetadata();
    const stripped = stripImageMetadata(before, "image/jpeg");
    const attachment = buildAttachment("photo.jpg", "image/jpeg", before);
    expect(attachment.size).toBe(stripped.length);
    expect(attachment.size).toBeLessThan(before.length);
    expect(unb64u(attachment.data)).toEqual(stripped);
  });

  it("truncates the name and supplies defaults without changing non-image bytes", () => {
    const longName = "n".repeat(130);
    const bytes = Uint8Array.from([1, 2, 3]);
    const named = buildAttachment(longName, "text/plain", bytes);
    expect(named.name).toBe("n".repeat(120));
    expect(named.size).toBe(bytes.length);
    expect(unb64u(named.data)).toEqual(bytes);

    const unnamed = buildAttachment("", "", bytes);
    expect(unnamed.name).toBe("attachment");
    expect(unnamed.content_type).toBe("application/octet-stream");
    expect(unb64u(unnamed.data)).toEqual(bytes);
  });
});
