# Authorized Use Only

whyfi is being developed as a tool for authorized wireless surveys and
pentesting. Its current WiFi, cellular, BLE, GNSS, and USB Monitor features
collect observations; the USB adapter receives 802.11 frame metadata in
monitor mode. Current WiFi functionality does not inject frames,
deauthenticate clients, jam, or craft packets. Future active tests, if added,
will need their own explicit controls and documentation.

The LAN scanner opens brief TCP connections to other devices on the WiFi
network your phone is currently connected to, to
discover what's there and which common ports respond (the same kind of
connection your browser makes visiting a router's admin page — no
exploitation, no payloads, just connect-and-close). Only run this against a
network you own or are authorized to test.

whyfi can also be told to scan from its web UI ("Remote control"), which lets
a phone collect on a schedule while unattended. This uses the regular scan
features, not USB Monitor, and can log for hours without anyone watching it.
It is off by default and can only be switched on physically, in the app, on
the phone itself: no server can turn it on, and it stays off after a reboot
until someone opens the app again. While it's on, the scanning notification
is visible the entire time. USB Monitor also requires a local start and a
visible foreground notification.

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

The optional USB Monitor feature receives nearby WiFi management and data-frame
metadata with an external adapter. It is explicitly started on the phone after
a successful adapter test. The live device list stays local to the app
session; a PDF, mission log, and optional PCAP are retained locally and can
be uploaded explicitly to the private backend. Probe names and MAC addresses may
describe other people's devices; only collect and retain them within the
scope of your authorization. An observed MAC address or a guessed device type
does not establish a person's identity.
