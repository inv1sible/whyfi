# Monitor mission behavior

- **Start** creates one local mission only when none is open. **Stop** ends the current capture pass and writes its report; the mission remains open. Another **Start** appends to the same mission log and PCAP (subject to the existing 200 MB PCAP limit).
- **Reset** in Nearby closes the open mission and clears favorites and hidden APs. It leaves PDF, log, and PCAP files intact in Missions. The next Start creates a new mission.
- **Open for capture** in Missions selects an unuploaded local mission and opens Nearby with Start/Stop. **Replay on Location map** is a separate read-only view. Uploaded missions cannot be extended.
- Hiding a named AP hides every AP with the exact same SSID in the current mission. APs with no SSID are hidden individually by BSSID. The Hidden APs filter restores them. Hiding changes the displayed list; it does not discard recorded evidence.

Acceptance check on a phone: start, stop, start, stop and confirm the mission ID is unchanged and log/PCAP sizes grow. Open a different saved mission, start and stop, then confirm its original ID and files grow. Reset, start, and confirm a new ID appears while prior files remain in Missions. Hide one of two APs sharing an SSID and confirm both disappear and can be restored from Hidden APs.
