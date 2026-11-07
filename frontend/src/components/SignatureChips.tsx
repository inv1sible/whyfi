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

const ADDRESS_RULES = new Set(["OUI", "MAC_PREFIX", "VENDOR_IE_OUI"]);
const BSSID_VIAS = new Set(["bssid", "bssid_universal"]);

/** Only the MAC prefix matched. A random BLE address can start with a
 * catalogued prefix by chance, so this is the weakest kind of hit. */
export function isAddressPrefixOnly(signature: BleSignatureRef): boolean {
  return weakHitLabel(signature) === "address prefix only";
}

/** A qualifier for hits that rest only on an OUI: the BSSID/MAC prefix
 * (vendor of the radio, or chance for a random address) or a Wi-Fi vendor
 * element (who built the firmware, not necessarily the product). */
export function weakHitLabel(signature: BleSignatureRef): string | null {
  if (signature.rules.length === 0 || !signature.rules.every((rule) => ADDRESS_RULES.has(rule))) return null;
  const vias = signature.vias ?? [];
  if (vias.length === 0 || vias.every((via) => BSSID_VIAS.has(via))) return "address prefix only";
  if (vias.every((via) => via === "vendor_ie")) return "vendor element only";
  return "address prefix + vendor element";
}

/** Union of several devices' signatures (e.g. the BSSIDs of one SSID). */
export function mergeSignatures(groups: BleSignatureRef[][]): BleSignatureRef[] {
  const byId = new Map<string, BleSignatureRef>();
  groups.flat().forEach((signature) => {
    const prev = byId.get(signature.id);
    byId.set(
      signature.id,
      prev
        ? {
            ...prev,
            rules: [...new Set([...prev.rules, ...signature.rules])].sort(),
            vias: [...new Set([...(prev.vias ?? []), ...(signature.vias ?? [])])].sort(),
          }
        : signature,
    );
  });
  return [...byId.values()];
}

/** The "Pattern match" section of a detail page: each signature's catalog
 * notes. Informational only, like the chips: no alert state or "following
 * you" logic hangs off this (see MEMORY.md). */
export function SignatureNotes({ signatures, subject }: { signatures: BleSignatureRef[]; subject: string }) {
  if (signatures.length === 0) return null;
  return (
    <>
      <h2>Pattern match</h2>
      <p className="page-hint">A pattern match on the {subject}, not proof of what the device is.</p>
      <dl className="signature-notes">
        {signatures.map((signature) => (
          <div key={signature.id}>
            <dt>
              <SignatureChips signatures={[signature]} />
            </dt>
            <dd>
              {signature.notes || "No notes in the signature catalog."}
              {/* Plain text, not .warning-text: a catalog caution, not an alert. */}
              {signature.attention_note && <p className="signature-attention">{signature.attention_note}</p>}
            </dd>
          </div>
        ))}
      </dl>
    </>
  );
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
          {weakHitLabel(signature) && <span className="badge-signature-class"> · {weakHitLabel(signature)}</span>}
        </span>
      ))}
    </span>
  );
}
