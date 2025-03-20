/*
 * SPDX-FileCopyrightText: 2025 Pixel Updater contributors
 * SPDX-FileCopyrightText: 2022-2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * Based on BCR code.
 */

package com.github.pixelupdater.pixelupdater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.pixelupdater.pixelupdater.settings.SettingsActivity
import com.github.pixelupdater.pixelupdater.updater.UpdaterService
import kotlinx.serialization.json.Json

class Notifications(
    private val context: Context,
) {
    companion object {
        private val TAG = Notifications::class.java.simpleName

        const val CHANNEL_ID_PERSISTENT = "persistent"
        const val CHANNEL_ID_CHECK = "check"
        const val CHANNEL_ID_FAILURE = "failure"
        const val CHANNEL_ID_SUCCESS = "success"

        const val GROUP_KEY_UPDATES = "UPDATES"

        private val LEGACY_CHANNEL_IDS = arrayOf<String>()

        const val ID_SUMMARY = 0
        const val ID_PERSISTENT = 1
        const val ID_ALERT = 2
        const val ID_INDEXED = 3
        const val ID_PREPARING = 4

        // https://stackoverflow.com/a/57769424/434343
        fun areEnabled(context: Context): Boolean {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            return when {
                notificationManager.areNotificationsEnabled().not() -> false
                else -> notificationManager.notificationChannels.firstOrNull { channel -> channel.importance == NotificationManager.IMPORTANCE_NONE } == null
            }
        }
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    init {
        updateChannels()
    }

    private fun createPersistentChannel() = NotificationChannel(
        CHANNEL_ID_PERSISTENT,
        context.getString(R.string.notification_channel_persistent_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.notification_channel_persistent_desc)
    }

    private fun createCheckAlertsChannel() = NotificationChannel(
        CHANNEL_ID_CHECK,
        context.getString(R.string.notification_channel_check_name),
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = context.getString(R.string.notification_channel_check_desc)
    }

    private fun createFailureAlertsChannel() = NotificationChannel(
        CHANNEL_ID_FAILURE,
        context.getString(R.string.notification_channel_failure_name),
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = context.getString(R.string.notification_channel_failure_desc)
    }

    private fun createSuccessAlertsChannel() = NotificationChannel(
        CHANNEL_ID_SUCCESS,
        context.getString(R.string.notification_channel_success_name),
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = context.getString(R.string.notification_channel_success_desc)
    }

    /**
     * Ensure notification channels are up-to-date.
     *
     * Legacy notification channels are deleted without migrating settings.
     */
    fun updateChannels() {
        notificationManager.createNotificationChannels(listOf(
            createPersistentChannel(),
            createCheckAlertsChannel(),
            createFailureAlertsChannel(),
            createSuccessAlertsChannel(),
        ))
        LEGACY_CHANNEL_IDS.forEach { notificationManager.deleteNotificationChannel(it) }
    }

    /**
     * Safely get a drawable resource ID for notification icons.
     * Falls back to system icons if the requested icon cannot be loaded.
     */
    @DrawableRes
    private fun getSafeIcon(@DrawableRes iconResId: Int): Int {
        return try {
            Log.d(TAG, "Attempting to use icon resource: $iconResId")
            context.resources.getResourceName(iconResId)
            context.resources.getDrawable(iconResId, null)
            Log.d(TAG, "Using provided icon: $iconResId")
            iconResId
        } catch (e: Resources.NotFoundException) {
            Log.e(TAG, "Failed to load icon resource $iconResId, falling back to system icon", e)
            android.R.drawable.ic_dialog_info
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading icon resource $iconResId, falling back to system icon", e)
            android.R.drawable.ic_dialog_info
        }
    }

    /** Create a persistent notification for background services. */
    fun createPersistentNotification(
        @StringRes titleResId: Int,
        message: String?,
        @DrawableRes iconResId: Int,
        actions: List<Pair<Int, Intent>>,
        progressCurrent: Int?,
        progressMax: Int?,
        showImmediately: Boolean,
    ): Notification {
        require((progressCurrent == null) == (progressMax == null)) {
            "Must specify both current and max progress or neither"
        }

        // Get a safe icon resource ID that won't crash the app
        val safeIconResId = getSafeIcon(iconResId)

        val notificationIntent = Intent(context, SettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(context, CHANNEL_ID_PERSISTENT).run {
            setContentTitle(context.getText(titleResId))
            if (message != null) {
                setContentText(message)
                style = Notification.BigTextStyle().bigText(message)
            }
            setSmallIcon(safeIconResId)
            setContentIntent(pendingIntent)
            setOngoing(true)
            setOnlyAlertOnce(true)

            if (progressCurrent != null && progressMax != null) {
                if (progressMax <= 0) {
                    setProgress(0, 0, true)
                } else {
                    setProgress(progressMax, progressCurrent, false)
                }
            }

            for ((i, pair) in actions.withIndex()) {
                val (actionTextResId, actionIntent) = pair
                val actionPendingIntent = PendingIntent.getService(
                    context,
                    i + 10, // different request code for each action
                    actionIntent,
                    PendingIntent.FLAG_IMMUTABLE or
                            PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_ONE_SHOT,
                )

                addAction(Notification.Action.Builder(
                    null,
                    context.getString(actionTextResId),
                    actionPendingIntent,
                ).build())
            }

            // Inhibit 10-second delay when showing persistent notification
            if (showImmediately) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }

            build()
        }
    }

    /**
     * Send an alert notification with the given [title] and [icon].
     *
     * * If [errorMsg] is not null, then it is appended to the text with a black line before it.
     */
    fun sendAlertNotification(
        channel: String,
        onlyAlertOnce: Boolean,
        @StringRes title: Int,
        @DrawableRes icon: Int,
        errorMsg: String?,
        actions: List<Pair<Int, Intent>>,
        id: Int?,
    ) {
        // Get a safe icon resource ID that won't crash the app
        val safeIconResId = getSafeIcon(icon)

        val notification = Notification.Builder(context, channel).run {
            val text = buildString {
                val errorMsgTrimmed = errorMsg?.trim()
                if (!errorMsgTrimmed.isNullOrBlank()) {
                    append(errorMsgTrimmed)
                }
            }

            setContentTitle(context.getString(title))
            if (text.isNotBlank()) {
                setContentText(text)
                style = Notification.BigTextStyle()
            }
            setSmallIcon(safeIconResId)
            setOnlyAlertOnce(onlyAlertOnce)

            for ((i, pair) in actions.withIndex()) {
                val (actionTextResId, actionIntent) = pair
                val actionPendingIntent = PendingIntent.getService(
                    context,
                    (id ?: 0) * 10 + i, // unique request code for each action
                    actionIntent,
                    PendingIntent.FLAG_IMMUTABLE or
                            PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_ONE_SHOT,
                )

                addAction(Notification.Action.Builder(
                    null,
                    context.getString(actionTextResId),
                    actionPendingIntent,
                ).build())
            }

            if (id != null && id >= ID_INDEXED) {
                setGroup(GROUP_KEY_UPDATES)
            }

            Log.d(TAG, "notification ${id ?: ID_ALERT}: ${context.getString(title)}, $text")
            build()
        }

        notificationManager.notify(id ?: ID_ALERT, notification)
    }

    /**
     * Send a summary notification.
     */
    fun sendSummaryNotification() {
        val notification = Notification.Builder(context, CHANNEL_ID_CHECK).run {
            setSmallIcon(getSafeIcon(R.drawable.ic_notifications))
            setGroup(GROUP_KEY_UPDATES)
            setGroupSummary(true)
            Log.d(TAG, "notification $ID_SUMMARY")
            build()
        }

        notificationManager.notify(ID_SUMMARY, notification)
    }

    fun dismissAlertNotifications() {
        notificationManager.cancel(ID_ALERT)
        val prefs = Preferences(context)
        if (prefs.alertCache.isNotEmpty()) {
            val alerts = Json.decodeFromString<List<UpdaterService.IndexedAlert>>(prefs.alertCache)
            for (alert in alerts) {
                notificationManager.cancel(alert.index)
            }
            prefs.alertCache = ""
            notificationManager.cancel(ID_SUMMARY)
        }
    }

    fun dismissNotifications() {
        notificationManager.cancel(ID_PREPARING)
        dismissAlertNotifications()
    }
}
