# Monitor improvements — current scope

This list supersedes the unapproved feature decisions in `monitor-operations-plan.md`.

- [x] Use “randomized MAC” consistently in cards, tables, diagnostics and noise matching.
- [x] Put Missions first in the Monitor tab row.
- [x] Load the mission list without parsing mission logs on the UI thread; load device details only on request.
- [x] Generate PDFs on demand; save raw evidence and capture statistics immediately at Stop.
- [x] Put PCAP export and delete actions at the top of each mission card.
- [x] Edit mission names.
- [x] Preserve the name on continuation and display the continuation date, including after renaming legacy missions.
- [x] Explain the bounded log preview and provide full-log export.
- [x] Restore all recorded devices on continuation; mark exact MACs heard again and offer match-first/RSSI sorting, respecting Manual updates.
- [x] Request ownership of map touch gestures; disable follow-me on a deliberate pan. Physical one-finger behavior needs phone verification.
- [x] Correlate client as well as AP transmitter observations with receiver positions; expose estimates with uncertainty. A downlink recipient is not assigned the AP's RSSI.
- [x] Check camera identification and improve manufacturer/model matching and visible classification evidence. A camera without identifying metadata may remain unclassified.

Interpretation: “naming should be kept” means the edited mission name is inherited and the new date is displayed separately. A cross match means an exact previously recorded MAC was heard again; it does not establish that rotating addresses belong to one physical device.

Verification includes a header-listing fixture with 23 sparse 8 MiB logs, name/date continuation, raw-statistics persistence without PDF rendering, inventory union and matching, malformed-location handling, client correlation, and camera/noise regression cases. Device timing, map gestures and Android PDF viewing still require a physical phone.

Camera rule references: [Lenovo SE-441P](https://support.lenovo.com/us/en/accessories/acc500184), [Tapo setup identity](https://www.tp-link.com/ph/support/faq/2710/), [Tapo C200](https://static.tp-link.com/2020/202004/20200401/Tapo%20C200%28EU%26US%291.0_Datasheet.pdf), [Foscam](https://www.foscam.com/products/Indoor_Camera.html), [VStarcam](https://www.vstarcam.com/support/), and [Xiongmai](https://www.xiongmaitech.com/en/index.php/product). These support device-type hints, not identification of a physical device from a generic brand alone.
