import { useEffect, useRef, useState } from "react";

/**
 * Camera QR reader for the approving device.
 *
 * Uses the platform BarcodeDetector where it exists (Chromium desktop and
 * Android). Everything else — Firefox, Safari, any machine without a camera —
 * gets the paste box, which is not a lesser path: the QR payload is plain
 * public JSON and pasting it reaches the identical pairing session. The human
 * safety-number comparison is what authenticates either way, so no scan
 * mechanism is trusted more than another.
 */
interface DetectedBarcode { rawValue: string }
interface BarcodeDetectorLike {
  detect(source: CanvasImageSource): Promise<DetectedBarcode[]>;
}
type BarcodeDetectorCtor = new (options?: { formats?: string[] }) => BarcodeDetectorLike;

function barcodeDetector(): BarcodeDetectorCtor | null {
  const ctor = (window as unknown as { BarcodeDetector?: BarcodeDetectorCtor }).BarcodeDetector;
  return typeof ctor === "function" ? ctor : null;
}

export default function PairingScanner({
  onPayload, onCancel,
}: {
  onPayload: (payload: string) => void;
  onCancel: () => void;
}) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [manual, setManual] = useState("");
  const supportsCamera = barcodeDetector() != null;

  useEffect(() => {
    if (!supportsCamera) return;
    let stream: MediaStream | null = null;
    let frame = 0;
    let stopped = false;
    const Detector = barcodeDetector();
    if (!Detector) return;
    const detector = new Detector({ formats: ["qr_code"] });

    const scan = async () => {
      const video = videoRef.current;
      if (stopped || !video || video.readyState < 2) {
        frame = requestAnimationFrame(() => void scan());
        return;
      }
      try {
        const found = await detector.detect(video);
        const value = found[0]?.rawValue;
        if (value) {
          stopped = true;
          onPayload(value);
          return;
        }
      } catch {
        // A transient decode failure is normal between frames.
      }
      frame = requestAnimationFrame(() => void scan());
    };

    void (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: "environment" },
        });
        if (stopped) {
          stream.getTracks().forEach((track) => track.stop());
          return;
        }
        if (videoRef.current) {
          videoRef.current.srcObject = stream;
          await videoRef.current.play();
        }
        void scan();
      } catch {
        setCameraError("카메라를 열지 못했습니다. 아래에 QR 내용을 붙여넣어 진행할 수 있습니다.");
      }
    })();

    return () => {
      stopped = true;
      cancelAnimationFrame(frame);
      stream?.getTracks().forEach((track) => track.stop());
    };
  }, [supportsCamera, onPayload]);

  return (
    <div className="space-y-3 rounded-xl bg-fg/[0.04] p-3 animate-rise">
      {supportsCamera && !cameraError && (
        <video
          ref={videoRef}
          muted
          playsInline
          className="aspect-square w-full rounded-lg bg-black object-cover"
        />
      )}
      {(cameraError || !supportsCamera) && (
        <p className="text-[11px] leading-relaxed text-tx-3">
          {cameraError ?? "이 브라우저는 QR 스캔을 지원하지 않습니다. 새 기기 화면의 QR 내용을 붙여넣으세요."}
        </p>
      )}
      <textarea
        value={manual}
        onChange={(event) => setManual(event.target.value)}
        rows={2}
        spellCheck={false}
        placeholder="QR 내용 붙여넣기"
        className="field resize-none font-mono text-[10px]"
      />
      <div className="flex gap-2">
        <button
          type="button"
          disabled={!manual.trim()}
          onClick={() => onPayload(manual.trim())}
          className="btn-ghost flex-1 !py-2 text-xs disabled:opacity-40"
        >
          붙여넣은 값으로 연결
        </button>
        <button type="button" onClick={onCancel} className="btn-ghost !py-2 text-xs">
          취소
        </button>
      </div>
    </div>
  );
}
