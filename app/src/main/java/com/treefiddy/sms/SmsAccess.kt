package com.treefiddy.sms

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * READ_SMS health, checking BOTH the runtime grant and the app-op.
 *
 * A sideloaded, non-default-SMS app holds READ_SMS as a *restricted* permission:
 * a reboot or a Play Protect / policy sweep can silently neuter it. Two failure
 * shapes exist and only the second is subtle:
 *   REVOKED  — checkSelfPermission == DENIED (grant gone)
 *   NEUTERED — grant is GRANTED but the app-op is IGNORED; provider reads return
 *              empty or, on content://sms, hang. checkSelfPermission alone
 *              reports OK here, so we must inspect the app-op too.
 */
enum class SmsAccess {
    OK, REVOKED, NEUTERED;

    companion object {
        fun check(ctx: Context): SmsAccess {
            val granted = ctx.checkSelfPermission(Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return REVOKED

            val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return OK
            val mode = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ops.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_READ_SMS, Process.myUid(), ctx.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    ops.checkOpNoThrow(
                        AppOpsManager.OPSTR_READ_SMS, Process.myUid(), ctx.packageName)
                }
            } catch (e: Exception) {
                AppOpsManager.MODE_ALLOWED   // can't tell -> don't cry wolf
            }
            return if (mode == AppOpsManager.MODE_ALLOWED) OK else NEUTERED
        }
    }
}
