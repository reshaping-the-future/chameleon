package ac.robinson.chameleonnotifier.service;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * This class receives notifications from {@link NotificationMonitorService} and decides whether to hide them.
 * If a Facebook, SMS or WhatsApp message is received, the subscribing activity is notified.
 */
public class MonitorManager {

	private static final String TAG = "MonitorManager";

	public static final String START_MANAGING_NOTIFICATIONS = "start_managing_notifications";
	public static final String STOP_MANAGING_NOTIFICATIONS = "stop_managing_notifications";

	public static final String NOTIFICATION_EXTRAS = "extras";
	public static final String NOTIFICATION_INTENT = "intent";

	public static final String TYPE_FACEBOOK = "facebook";
	public static final String TYPE_SMS = "sms";
	public static final String TYPE_WHATSAPP = "whatsapp";

	private static boolean sManageNotifications = false;

	private static int sOriginalRingerMode = AudioManager.RINGER_MODE_NORMAL;
	private static int sOriginalVibrationMode = AudioManager.VIBRATE_SETTING_ON;


	MonitorManager(Context context) {
		LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(context);
		localBroadcastManager.registerReceiver(mBroadcastReceiver, new IntentFilter(START_MANAGING_NOTIFICATIONS));
		localBroadcastManager.registerReceiver(mBroadcastReceiver, new IntentFilter(STOP_MANAGING_NOTIFICATIONS));
	}

	public void cancelManager(Context context) {
		restoreAudio(context);
		restoreVibration(context);
		LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(context);
		localBroadcastManager.unregisterReceiver(mBroadcastReceiver); // cancel event listener
	}

	public boolean handleNotificationEvent(Context context, String packageName, Bundle notificationExtras,
										   PendingIntent notificationIntent) {
		if (!sManageNotifications) {
			return false; // don't do anything when we're not managing notifications
		}

		boolean shouldCancelNotification = true;
		Intent eventIntent = new Intent();
		if (notificationExtras != null) {
			eventIntent.putExtra(NOTIFICATION_EXTRAS, notificationExtras);
		}
		if (notificationIntent != null) {
			eventIntent.putExtra(NOTIFICATION_INTENT, notificationIntent);
		}

		switch (packageName) {
			case "com.facebook.katana":
			case "com.facebook.lite":
			case "com.facebook.orca":
				// Log.d(TAG, "Received Facebook notification from " + packageName);
				eventIntent.setAction(TYPE_FACEBOOK);
				break;

			case "com.android.mms":
			case "com.google.android.talk":
			case "com.sonyericsson.conversations":
				// Log.d(TAG, "Received SMS/MMS/Google Talk notification from " + packageName);
				eventIntent.setAction(TYPE_SMS);
				break;

			case "com.whatsapp":
				// Log.d(TAG, "Received WhatsApp notification from " + packageName);
				eventIntent.setAction(TYPE_WHATSAPP);
				break;

			default:
				// Log.d(TAG, "Received other notification from " + packageName + " - ignoring");
				shouldCancelNotification = false;
				break;
		}

		// cancel this notification and notify the Chameleon activity - note that the notification can sometimes
		// briefly show, but then disappears instantly - overall, this is better than using cancelAllNotifications();
		if (shouldCancelNotification) {
			// TODO: a more extensible implementation of this would probably just forward the notification itself...
			LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(context);
			localBroadcastManager.sendBroadcast(eventIntent);
		}

		return shouldCancelNotification;
	}

	private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (TextUtils.isEmpty(action)) {
				return;
			}
			switch (action) {
				case START_MANAGING_NOTIFICATIONS:
					// TODO: note that we currently have to silence and stop vibration for *all* notifications...
					silenceAudio(context);
					disableVibration(context);
					sManageNotifications = true;
					Log.d(TAG, "Starting managing notifications");
					break;

				case STOP_MANAGING_NOTIFICATIONS:
					sManageNotifications = false;
					restoreAudio(context);
					restoreVibration(context);
					Log.d(TAG, "Stopping managing notifications");
					break;

				default:
					break;
			}
		}
	};

	private void silenceAudio(Context context) {
		// see: http://stackoverflow.com/a/11699632)?
		try {
			final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
			if (audioManager != null) {
				sOriginalRingerMode = audioManager.getRingerMode();
				audioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
			}
		} catch (RuntimeException ignored) {
		}
	}

	private void restoreAudio(Context context) {
		try {
			final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
			if (audioManager != null) {
				audioManager.setRingerMode(sOriginalRingerMode);
			}
		} catch (RuntimeException ignored) {
		}
	}

	private void disableVibration(Context context) {
		try {
			// see: http://stackoverflow.com/a/23165919 - deprecated, but we need this approach for our application
			final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
			if (audioManager != null) {
				sOriginalVibrationMode = audioManager.getVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION);
				audioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, AudioManager.VIBRATE_SETTING_OFF);
			}
		} catch (RuntimeException ignored) {
		}
	}

	private void restoreVibration(Context context) {
		try {
			final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
			if (audioManager != null) {
				audioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, sOriginalVibrationMode);
			}
		} catch (RuntimeException ignored) {
		}
	}
}
