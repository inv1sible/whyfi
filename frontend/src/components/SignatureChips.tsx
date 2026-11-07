import type { BleSignatureRef } from "../api/types";
import { DeviceTypeBadge } from "./DeviceTypeBadge";

// Fieldwatch's signature classes (scans/signatures/fieldwatch-signatures-v2.json).
export const SIGNATURE_CLASS_LABELS: Record<string, string> = {
  FINDER: "Finder",
  BEACON: "Beacon",
  SIGNAGE: "Signage",
  WEARABLE: "Wearable",
  SURVEILLANCE: "Surveillance",
  DRONE: "Drone",
  HACKING: "Hacking tool",
  BODYWORN: "Body-worn",
  LAW_ENFORCEMENT: "Law enforcement",
  VEHICLE: "Vehicle",
  GLASSES: "Glasses",
  AUDIO: "Audio",
  CAMERA: "Camera",
  THERMOSTAT: "Thermostat",
  LOCK: "Lock",
  HEALTH: "Health",
  HOME: "Home",
  ISP: "ISP",
  MESH: "Mesh",
  PHONE: "Phone",
  OTHER: "Other",
};

export function signatureClassLabel(kind: string): string {
  return SIGNATURE_CLASS_LABELS[kind] ?? kind;
}

const ADDRESS_RULES = new Set(["OUI", "MAC_PREFIX"]);

/** Only the MAC prefix matched. A random BLE address can start with a
 * catalogued prefix by chance, so this is the weakest kind of hit. */
export function isAddressPrefixOnly(signature: BleSignatureRef): boolean {
  return signature.rules.length > 0 && signature.rules.every((rule) => ADDRESS_RULES.has(rule));
}

// Pattern matches on the advertisement, informational only (see MEMORY.md):
// no alert state or correlation hangs off these. Falls back to the legacy
// on-device guess when the catalog matched nothing.
export function SignatureChips({
  signatures,
  deviceType,
}: {
  signatures: BleSignatureRef[];
  deviceType?: string;
}) {
  if (signatures.length === 0) {
    return deviceType ? <DeviceTypeBadge deviceType={deviceType} /> : <span className="page-hint">—</span>;
  }
  return (
    <span className="signature-chips">
      {signatures.map((signature) => (
        <span
          key={signature.id}
          className="badge badge-signature"
          title={`Pattern match on the advertisement (${signature.rules.join(", ") || "no rule recorded"}), not an identification`}
        >
          {signature.name}
          <span className="badge-signature-class"> · {signatureClassLabel(signature.kind)}</span>
          {isAddressPrefixOnly(signature) && <span className="badge-signature-class"> · address prefix only</span>}
        </span>
      ))}
    </span>
  );
}
