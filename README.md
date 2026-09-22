# Droid-SSH

แอป Android (Kotlin) เปิด **SSHD server** บนมือถือ — sideload only (ไม่ขึ้น Play Store)

- Shell + SFTP (Apache MINA SSHD)
- Login: password + public key (`authorized_keys`) พร้อมกัน (v1: key ชนิด `ssh-rsa`,ชนิดอื่นใช้ password ไปก่อน)
- Port: **22** (root mode, shell ผ่าน `su`) / **2222** (non-root) + fallback อัตโนมัติถ้า bind ไม่ได้
- Auto startup: `BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT` + `directBootAware`
- Keep alive: ForegroundService (`START_STICKY`) + notification, WifiLock/WakeLock, WorkManager 15 นาที, restart เมื่อ swipe ทิ้ง
- ตั้งค่าในแอป: username/password, root switch, auto-start switch, วาง public key, ปุ่มขอ ignore battery + all-files access

## Build (GH Action)

push `main` -> workflow `Android build` รัน `gradle assembleDebug` แล้วแนบ `Droid-SSH.apk` ใน Release `v0.1.<run>` อัตโนมัติ

## ลายเซ็น (stable key)

ทุก build เซ็นด้วยคีย์เดิม (`CN=Droid-SSH`, หมดอายุ 2056, เก็บเป็น `app/droid-ssh-debug.keystore.b64`)
-> ติดตั้งทับเวอร์ชันใหม่ได้เลยโดยไม่ต้องถอน

> ผู้ที่ลงเวอร์ชันเก่ากว่า stable key (v0.1.3 ลงมา ซึ่งเซ็นด้วย debug key แบบสุ่มของ runner)
> ต้องถอนติดตั้งครั้งเดียว แล้วลงเวอร์ชันใหม่ หลังจากนั้นอัปเดตทับได้ตลอด

## ใช้งาน

```
ssh droid@<ip-mือถือ> -p 2222   # non-root
ssh droid@<ip-มือถือ> -p 22     # root mode
sftp -P 2222 droid@<ip>
```

> หมายเหตุ Android 12+: ระบบจำกัด start FGS จาก background หลัง boot — ถ้า auto-start โดนบล็อก ให้เปิดแอปครั้งนึงแล้วกด “เปิด SSH” + อนุญาต Ignore Battery + ล็อกแอปกันโดน kill (OEM: Xiaomi/Oppo/Vivo ต้องเปิด Autostart เพิ่ม)
