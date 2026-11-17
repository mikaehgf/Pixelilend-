package com.pixelisland

import android.service.notification.NotificationListenerService

// Пустой слушатель уведомлений: он нужен, чтобы Android дал доступ к музыкальным сессиям.
class MediaListener : NotificationListenerService()
