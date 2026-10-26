# Monitor mission behavior

- **Start** creates one local mission only when none is open. **Stop** ends the current capture pass and writes its report; the mission remains open. Another **Start** appends to the same mission log and PCAP (subject to the existing 200 MB PCAP limit).
- **Reset** in Nearby closes the open mission and clears favorites and hidden APs. It leaves PDF, log, and PCAP files intact in Missions. The next Start creates a new mission.
- **Continue mission** in Missions copies the selected mission's log and PCAP to a new local UUID, records the source ID in its log, and opens Nearby. The source stays intact, including its uploaded marker. The new mission has no PDF until its first capture finishes, then it can be uploaded under the new ID. An already uploaded mission can be continued this way.
- **Replay on Location map** is a separate read-only view. Nearby has no mission picker bar.
- Hiding a named AP hides every AP with the exact same SSID in the current mission. APs with no SSID are hidden individually by BSSID. The Hidden APs filter restores them. Hiding changes the displayed list; it does not discard recorded evidence.

Acceptance check on a phone: start, stop, start, stop and confirm the mission ID is unchanged and log/PCAP sizes grow. In Missions, continue a saved mission, then confirm it has a new ID while the source files stay unchanged. Start and stop the new mission and confirm its PDF reports the new ID and its PCAP grows. Upload both missions separately. Reset, start, and confirm another new ID appears. Hide one of two APs sharing an SSID and confirm both disappear and can be restored from Hidden APs.
