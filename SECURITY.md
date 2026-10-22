# Security model / 安全模型

## One-way isolation（單向隔離）
```
 family console ──(outbound only)──►  relay (ntfy.sh or self-hosted)  ◄──(outbound only)──  elder's phone
 push commands / read status (as data)                                   poll commands / push status
```
- The phone never accepts inbound connections and never learns where the family console is.
- Both sides only make **outbound HTTPS** requests to the relay.
- The console treats everything the phone sends as **data**: known fields only, truncated, never `eval`, never passed to a shell.
- Even if the elder's phone is fully compromised, the attacker reaches at most the relay — not the family's device.

## Crypto（加密）
- AES-256-GCM. Key = SHA-256("pg-key:" + pairing code). Output = base64(iv[12] ‖ ciphertext ‖ tag[16]).
- Topic names = SHA-256("pg-topic:kind:" + pairing code)[:20]; not guessable without the pairing code.
- Every command is wrapped as `{"c": cmd, "t": unix_time, "n": nonce}` **before** encryption. The phone rejects
  commands older/newer than ±5 minutes or with a nonce it has already seen (replay protection).
- The pairing code is 12 chars from a 31-symbol alphabet (~59 bits). It is shown once on the phone; treat it like a password.

## What the relay can see（轉發站看得到什麼）
- Only ciphertext and topic names. ntfy.sh is a public relay; anyone who learns a topic name can read ciphertext and post
  garbage, but cannot decrypt, forge or replay commands. If you prefer, self-host ntfy and set the server URL in the app
  (and `PG_NTFY` on the console).

## Known limits（已知極限）— please read before relying on it
- Tier B (accessibility-service based) cannot force-stop arbitrary apps without root/device-owner. The strongest action is
  repeated HOME + rescue overlay + guiding the user to uninstall.
  **Optional Shizuku mode** (0.2.0+) closes most of this gap while Shizuku is running: force-stop, revoke draw-over-apps,
  remove the offending app's accessibility service, and disable block-listed apps. It runs shell-level commands, so it never
  touches system apps, allow-listed apps or itself, and package names are validated (`[A-Za-z0-9_.]`) before use.
  On non-rooted phones Shizuku stops at every reboot; until someone starts it again the guard falls back to Tier B.
- A malicious app that *also* holds accessibility rights can fight back; without Shizuku this guard does not guarantee a win every time.
- Heuristics (what counts as "takeover") are first-pass and will need tuning on real devices.

## Reporting
Open a GitHub issue. Do not include pairing codes or relay topic names in reports.
