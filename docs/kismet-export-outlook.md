# Exporting WiFi observations to Kismet (outlook, not built)

## Idea

Forward whyfi's already-collected WiFi observations into a running Kismet
instance, so the same scan data shows up in Kismet's device tracker/UI
alongside whatever Kismet is capturing itself. Raised as "can we send our WiFi
data to Kismet" — this note addresses the existing parsed `WifiManager`
observations, not the newer USB Monitor session.

## Findings

Kismet is built around real 802.11 frames arriving from a datasource (a radio
in monitor mode, or its remote-capture protocol), which looked at first like a
poor fit for the regular scan pipeline: it has already-parsed scan results
(BSSID/SSID/RSSI/...), not raw frames. The USB Monitor prototype receives
802.11 frames locally but does not yet store or export them. Two paths exist
for the regular pre-parsed data:

1. **Custom PHY + `KDSDATAREPORT`** (datasource framework). A datasource can
   submit a JSON blob via the Kismet External protocol instead of raw packet
   bytes, but a *custom PHY handler* (a C++ component built into Kismet) is
   still required to parse that JSON into device records. Real integration
   work, not just an HTTP call.
2. **Wi-Fi scanning mode REST endpoint** — `POST /phy/phy80211/scan/scan_report.cmd`.
   Built by the Kismet project specifically for external scanners (phones,
   wardriving tools) to push already-parsed observations. No custom PHY, no
   packet synthesis, no persistent datasource process — Kismet creates the
   datasource dynamically from the first report. This is the fit.

### `scan_report.cmd` request shape

Auth: API key with the `admin` or `scanreport` role.

Required: `source_uuid` (stable per whyfi installation/sensor), `source_name`,
`bssid`.

Optional per report: `ssid`, `channel`, `freqkhz`, `signal` (dBm),
`capabilities`, `timestamp`, `lat`/`lon`/`alt`/`spd`. Reports can be batched
in one request, which fits whyfi's already-batched, sometimes-offline upload
model (see the outbox in `SettingsRepository`/`ScanForegroundService`).

### Field mapping to whyfi's existing schema

| Kismet field | whyfi source |
|---|---|
| `bssid` | `AccessPoint.bssid` |
| `ssid` | `AccessPoint.ssid` |
| `signal` | `WiFiObservation.rssi` |
| `freqkhz` | `WiFiObservation.frequency_mhz * 1000` |
| `channel` | `WiFiObservation.channel` |
| `capabilities` | `WiFiObservation.capabilities_raw` |
| `timestamp` | `WiFiObservation.observed_at` |
| `lat`/`lon` | `ScanSession.latitude`/`longitude` |

Close to a direct field-for-field mapping — no derived/synthetic data needed
on whyfi's side.

## Open design question (deferred)

Where the forwarder would live, if built:

- **Backend-side** — one Kismet server URL + API key configured once,
  forwarding the merged view across every sensor. Simpler to operate; the
  backend already holds every field above in one place.
- **App-side** — each phone forwards its own local scan directly, with
  Kismet credentials configured per device. More moving parts (N configs
  instead of one), but keeps working if a phone talks to a different whyfi
  backend, or none at all.

No decision made — not being built right now, kept here so the research
doesn't have to happen twice.

## Sources

- [Scanning mode: Wi-Fi — Kismet](https://www.kismetwireless.net/docs/api/wifi_scanningmode/)
- [Wi-Fi (phy80211) — Kismet](https://www.kismetwireless.net/docs/api/wifi_dot11/)
- [Datasources — Kismet](https://www.kismetwireless.net/docs/readme/datasources/datasources/)
- [kismet-docs/devel/datasource.md](https://github.com/kismetwireless/kismet-docs/blob/master/devel/datasource.md)
