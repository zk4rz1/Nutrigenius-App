package com.example.nutrigeniusbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "NutriGenius"
        var body = intent.getStringExtra("body") ?: ""

        // Il promemoria è stato programmato dalla pagina con l'acqua di allora: nel frattempo
        // dai widget si può aver bevuto (#158). Si rifà il conto con i dati di adesso.
        when (val esito = PromemoriaAcqua.controlla(context)) {
            is PromemoriaAcqua.Esito.Niente -> return
            is PromemoriaAcqua.Esito.Dopo -> {
                programma(context, title, esito.body, esito.fra)
                return
            }
            is PromemoriaAcqua.Esito.Adesso -> body = esito.body
            null -> {}
        }

        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "water_reminders"
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Promemoria Acqua", android.app.NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }
        
        val activityIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(context, 0, activityIntent, android.app.PendingIntent.FLAG_IMMUTABLE)

        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    companion object {
        /** Riprogramma il promemoria: lo stesso allarme che la pagina può annullare o sostituire. */
        fun programma(c: Context, title: String, body: String, fra: Long) {
            val i = Intent(c, NotificationReceiver::class.java).putExtra("title", title).putExtra("body", body)
            val pi = android.app.PendingIntent.getBroadcast(c, 0, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val am = c.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val quando = System.currentTimeMillis() + maxOf(1000L, fra)
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, quando, pi)
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, quando, pi)
        }
    }
}
