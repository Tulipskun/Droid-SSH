package com.tulipskun.droidssh;

import java.nio.file.Path;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.sftp.server.SftpSubsystem;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/**
 * SFTP เริ่มที่ home (แบบ Termux: bare ls/put/get ทำงาน) แต่ยังเห็นทั้ง
 * filesystem ผ่าน absolute path (เช่น /sdcard) — ไม่ใช่ chroot
 *
 * กลไก: relative path ใน sshd resolve เทียบ getDefaultDirectory() ซึ่งอ่าน
 * field protected defaultDir -> override ค่านี้เป็น home หลัง super()
 */
public class HomeSftpSubsystem extends SftpSubsystem {
    public HomeSftpSubsystem(ChannelSession channel, SftpSubsystemFactory factory, Path home) {
        super(channel, factory);
        this.defaultDir = home.toAbsolutePath().normalize();
    }
}
