package com.tamaverse.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

class TamaReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val account = PetStorage.load(context)
        if (!account.notificationsEnabled) return
        val pet = account.activePet()
        if (pet.isHibernating || pet.isDeceased || pet.stage() == PetStage.EGG) return

        // Тихие часы (23:00 - 08:00)
        if (account.quietHoursEnabled) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (hour >= 23 || hour < 8) return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val title = intent.getStringExtra("title") ?: "${pet.customName} скучает!"
        val body = intent.getStringExtra("body") ?: "Загляни в TamaVerse — пора покормить и поиграть!"

        TamaNotificationHelper.ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, TamaNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(1001, notification)
    }
}

object TamaNotificationHelper {
    const val CHANNEL_ID = "tamaverse_care"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Уход за питомцем",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Напоминания о еде, играх и ежедневных бонусах TamaVerse"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun scheduleCareReminders(context: Context, account: AccountSaveState) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, TamaReminderReceiver::class.java)
            val existing = PendingIntent.getBroadcast(
                context,
                777,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(existing)

            if (!account.notificationsEnabled) return
            val pet = account.activePet()
            if (pet.isHibernating || pet.isDeceased || pet.stage() == PetStage.EGG) return

            // Напоминание через ~8 часов (в ритме 16-18 часовой жизни питомца)
            val delayHours = when {
                pet.hunger < 40 -> 3L
                pet.hunger < 70 -> 6L
                else -> 9L
            }
            val triggerAt = System.currentTimeMillis() + delayHours * 3_600_000L
            val nextIntent = Intent(context, TamaReminderReceiver::class.java).apply {
                putExtra("title", "🐾 ${pet.customName} ждёт тебя!")
                putExtra("body", "Пора проверить сытость, настроение и забрать бонусы дня!")
            }
            val pi = PendingIntent.getBroadcast(
                context,
                777,
                nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (_: Throwable) {
        }
    }
}
