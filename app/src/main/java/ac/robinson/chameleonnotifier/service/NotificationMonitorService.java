package ac.robinson.chameleonnotifier.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

/**
 * This service intercepts notifications and passes them to {@link MonitorManager} for handling
 */
public class NotificationMonitorService extends NotificationListenerService {

	private static MonitorManager sMonitorManager;

	@Override
	public void onCreate() {
		super.onCreate();
		sMonitorManager = new MonitorManager(NotificationMonitorService.this);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		sMonitorManager.cancelManager(NotificationMonitorService.this);
	}

	@Override
	public void onNotificationPosted(StatusBarNotification notification) {
		// note - this is a basic implementation, but we can also get all the notification's details - e.g.,
		// notification.getNotification().extras, then extras.getString(Notification.EXTRA_TITLE) etc; it is also
		// possible to get pre-existing notifications via getActiveNotifications()
		Bundle extras = null;
		PendingIntent intent = null;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			Notification baseNotification = notification.getNotification();
			extras = baseNotification.extras;
			intent = baseNotification.contentIntent;
		}

		// note: one improvement here could be to track last notification times to avoid multiple notification events
		boolean shouldCancelNotification = sMonitorManager.handleNotificationEvent(NotificationMonitorService.this,
				notification
				.getPackageName(), extras, intent);

		// cancel this notification - note that on recent SDK versions the notification can sometimes briefly show,
		// but then disappears instantly - overall, this is better than using cancelAllNotifications();
		if (shouldCancelNotification) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				cancelNotification(notification.getKey());
			} else {
				cancelNotification(notification.getPackageName(), notification.getTag(), notification.getId());
			}
		}
	}

	@Override
	public void onNotificationRemoved(StatusBarNotification sbn) {
		// can also take action here (e.g., on manual interaction, or after we know the notification has gone)
	}

	public static boolean isEnabled(Context context) {
		// check the list of system notification listeners to see whether ours has been enabled
		String notificationListeners;
		try {
			notificationListeners = Settings.Secure.getString(context.getContentResolver(),
					"enabled_notification_listeners");
		} catch (Throwable t) {
			return false; // can't read the value - no point continuing
		}
		if (!TextUtils.isEmpty(notificationListeners)) {
			final String[] listenerNames = notificationListeners.split(":");
			final String packageName = context.getPackageName();
			final String className = NotificationMonitorService.class.getName();
			for (String name : listenerNames) {
				final ComponentName componentName = ComponentName.unflattenFromString(name);
				if (componentName != null) {
					if (packageName.equals(componentName.getPackageName()) &&
							className.equals(componentName.getClassName())) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
