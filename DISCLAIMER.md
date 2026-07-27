# Authorized Use Only

whyfi is a passive wireless-environment scanner and visualizer. It does not
transmit, inject, deauthenticate, jam, or otherwise interfere with any
network or device — nearly everything it does is reading information
already broadcast publicly (WiFi beacons, your phone's own cellular signal
measurements, Bluetooth advertisements, GNSS signals).

The one exception is the LAN scanner: it opens brief TCP connections to
other devices on the WiFi network your phone is currently connected to, to
discover what's there and which common ports respond (the same kind of
connection your browser makes visiting a router's admin page — no
exploitation, no payloads, just connect-and-close). Only run this against a
network you own or are authorized to test.

whyfi can also be told to scan from its web UI ("Remote control"), which lets
a phone collect on a schedule while unattended. This collects nothing new —
the same passive observations, on your own device, to your own server — but
it does mean a phone can be logging for hours without anyone watching it.
It is off by default and can only be switched on physically, in the app, on
the phone itself: no server can turn it on, and it stays off after a reboot
until someone opens the app again. While it's on, the scanning notification
is visible the entire time. Keep it that way — if you find yourself wanting
to hide that notification or auto-start it on boot, you are building
something this project deliberately isn't.

Be aware that unattended collection accumulates a detailed record of the
devices around wherever the phone is left, including BLE identifiers that
may belong to other people's personal devices. Where you leave it, and for
how long, is a choice with consequences for people who never agreed to it.

You are responsible for using whyfi in compliance with the law and with any
policies that apply to networks and devices you scan. Only use it on:

- Your own networks and devices, or
- Networks and devices you are explicitly authorized to test (e.g. a signed
  penetration-testing engagement, a CTF environment you're permitted to use
  tools against, or your own home/lab equipment).

Future versions of whyfi may add active capabilities (e.g. monitor-mode
WiFi capture, packet crafting) via dedicated external-hardware sensors. Any
such feature will be opt-in, clearly labeled, and will carry its own
authorized-use requirements. Misuse of those capabilities against networks or
devices you do not own or have authorization to test may violate local, state,
or federal law (e.g. computer fraud/unauthorized access statutes) — that is
entirely on you, not this project.
