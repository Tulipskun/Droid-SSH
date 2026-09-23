# Droid-SSH

แอป Android (Kotlin) เปิด **SSHD server** บนมือถือ — sideload only (ไม่ขึ้น Play Store)

- Shell ผ่าน **PTY จริง** + one-shot exec (`ssh user@host "cmd"`) + SFTP (Apache MINA SSHD)
- Login: password (เก็บเป็น SHA-256+salt) + public key (`authorized_keys`: ssh-rsa/ecdsa/ed25519) พร้อมกัน
- SFTP เปิดมาอยู่ home (`~/`) แบบ Termux, absolute path ยังเห็นทั้งเครื่อง, มี `~/storage` -> /sdcard
- Port: **22** (root mode, NAT อัตโนมัติ) / **2222** (non-root) + fallback อัตโนมัติถ้า bind ไม่ได้
- Auto startup: `BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT` + `directBootAware`
- Keep alive: ForegroundService (`START_STICKY`) + notification, WifiLock/WakeLock, WorkManager 15 นาที, restart เมื่อ swipe ทิ้ง (กด “หยุด SSH” = หยุดจริง ไม่ restart เอง)
- ตั้งค่าในแอป: username/password, root switch, auto-start switch, วาง public key,
  ปุ่มขอ ignore battery + all-files access + ปุ่มติดตั้ง Termux guest

## Root mode (ต้อง grant root ใน KernelSU/Magisk ก่อน)

- Shell ได้สิทธิ์ root เต็ม (`uid=0`) ผ่าน PTY
- แอปตั้งกฎ iptables NAT `22 -> 2222` ให้อัตโนมัติทุกครั้งที่สตาร์ท (idempotent)
  จึงใช้ `ssh -p 22` ได้เลย (แอป bind port ต่ำกว่า 1024 ตรงๆ ไม่ได้ — เป็นข้อจำกัด Android)
- สถานะหน้าแอปบอกชัดว่า su พร้อมใช้หรือยังรอ grant

## Termux guest (apt/pkg จาก repo จริงของ Termux) — ต้องใช้ root

- กดปุ่ม “ติดตั้ง Termux guest” ในแอป (โหลด ~100MB ครั้งเดียว, เฉพาะ arm64 ตอนนี้)
- เข้า guest ด้วยคำสั่ง `tlogin` (user ปกติ — `apt`/`pkg` ใช้งานได้) หรือ `tlogin-root` (root เต็ม)
- ข้างในมี `apt/dpkg/bash/coreutils` ครบ ติดตั้งเพิ่มได้ปกติ เช่น `apt-get install -y htop`
- เทคนิค: chroot + bind mounts (ไม่ใช้ proot — ทดสอบแล้วใช้ไม่ได้บนเคอร์เนลนี้),
  ลดสิทธิ์ด้วย `droproot` (uid/gid/groups ของแอป รวมกลุ่ม inet), DNS จากเครื่องจริง

## Build (GH Action)

push `main` -> workflow `Android build` รัน `gradle assembleDebug` แล้วแนบ `Droid-SSH.apk` ใน Release `v0.1.<run>` อัตโนมัติ

## ลายเซ็น (stable key)

ทุก build เซ็นด้วยคีย์เดิม (`CN=Droid-SSH`, หมดอายุ 2056, เก็บเป็น `app/droid-ssh-debug.keystore.b64`)
-> ติดตั้งทับเวอร์ชันใหม่ได้เลยโดยไม่ต้องถอน

> ผู้ที่ลงเวอร์ชันเก่ากว่า stable key (v0.1.3 ลงมา ซึ่งเซ็นด้วย debug key แบบสุ่มของ runner)
> ต้องถอนติดตั้งครั้งเดียว แล้วลงเวอร์ชันใหม่ หลังจากนั้นอัปเดตทับได้ตลอด

## ใช้งาน

```
ssh droid@<ip-มือถือ> -p 2222   # non-root
ssh droid@<ip-มือถือ> -p 22     # root mode
ssh droid@<ip-มือถือ> -p 2222 "ls /sdcard"  # one-shot exec
sftp -P 2222 droid@<ip>
```

## Workspace แบบ Termux

- Shell ได้ **PTY จริง** (JNI forkpty) — มี tty, job control, `vim`/`top`/`stty` ใช้ได้ ไม่มี warning
- เข้า shell แล้วอยู่ `~/home` ของแอปทันที (`/data/data/.../files/home`)
- env ครบ: `HOME USER SHELL TERM LANG PATH TMPDIR` (+ `TERM/LANG` จาก client)
- `~/.droidrc` ถูก source ทุกครั้ง (prompt `user@host:$PWD $`, alias `ll/la`, motd) — แก้เองได้ อัปเกรดไม่เขียนทับ

> หมายเหตุ Android 12+: ระบบจำกัด start FGS จาก background หลัง boot — ถ้า auto-start โดนบล็อก ให้เปิดแอปครั้งนึงแล้วกด “เปิด SSH” + อนุญาต Ignore Battery + ล็อกแอปกันโดน kill (OEM: Xiaomi/Oppo/Vivo ต้องเปิด Autostart เพิ่ม)
