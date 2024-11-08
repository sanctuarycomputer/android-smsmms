
package com.android.mms.transaction;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.text.TextUtils;

import com.android.mms.MmsConfig;
import com.klinker.android.logger.Log;
import com.klinker.android.send_message.BroadcastUtils;
import com.klinker.android.send_message.MmsReceivedReceiver;

import java.io.File;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In order to avoid downloading duplicate MMS.
 * We should manage to call SMSManager.downloadMultimediaMessage().
 */
public class DownloadManager {
    private static final String TAG = "DownloadManager";
    private static DownloadManager ourInstance = new DownloadManager();
    private static final ConcurrentHashMap<String, MmsDownloadReceiver> mMap = new ConcurrentHashMap<>();

    public static DownloadManager getInstance() {
        return ourInstance;
    }

    private DownloadManager() {

    }

    public void downloadMultimediaMessage(final Context context, final String location, Uri uri, boolean byPush) {
        if (location == null || mMap.get(location) != null) {
            return;
        }

        // TransactionService can keep uri and location in memory while SmsManager download Mms.
        if (!isNotificationExist(context, location)) {
            return;
        }

        MmsDownloadReceiver receiver = new MmsDownloadReceiver();
        mMap.put(location, receiver);

        context.getApplicationContext().registerReceiver(receiver, new IntentFilter(receiver.ACTION));

        Log.v(TAG, "receiving with system method");
        final String fileName = "download." + String.valueOf(Math.abs(new Random().nextLong())) + ".dat";
        File mDownloadFile = new File(context.getCacheDir(), fileName);
        Uri contentUri = (new Uri.Builder())
                .authority(context.getPackageName() + ".MmsFileProvider")
                .path(fileName)
                .scheme(ContentResolver.SCHEME_CONTENT)
                .build();
        Intent download = new Intent(receiver.ACTION);

        download.putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, mDownloadFile.getPath());
        download.putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, location);
        download.putExtra(MmsReceivedReceiver.EXTRA_TRIGGER_PUSH, byPush);
        download.putExtra(MmsReceivedReceiver.EXTRA_URI, uri);

        /*
        the original code added a uuid to the ACTION name each time it registered the receiver and broadcast the intent - we can assume they did this to make the intents unique
        however, this would cause a 'sending non-protected broadcast' error, which we can't protect, since we have to explicitly define the action in the manifest
        using a unique request code here keeps the same behaviour as originally intended, of making the intent unique. alternatively, we could've used intent.setData, but i found that doing this would cause the receiver onReceive to not get called
        for additional information:
        https://developer.android.com/reference/android/content/Intent#filterEquals(android.content.Intent)
        https://stackoverflow.com/questions/21526319/whats-requestcode-used-for-on-pendingintent
        */

        int requestCode = UUID.randomUUID().hashCode();

        final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, download, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Bundle configOverrides = new Bundle();
        String httpParams = MmsConfig.getHttpParams();
        if (!TextUtils.isEmpty(httpParams)) {
            configOverrides.putString(SmsManager.MMS_CONFIG_HTTP_PARAMS, httpParams);
        }

        grantUriPermission(context, contentUri);
        SmsManager.getDefault().downloadMultimediaMessage(context,
                location, contentUri, configOverrides, pendingIntent);
    }

    private void grantUriPermission(Context context, Uri contentUri) {
        context.grantUriPermission(context.getPackageName() + ".MmsFileProvider",
                contentUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    private static class MmsDownloadReceiver extends BroadcastReceiver {
        private static final String ACTION = "com.android.mms.transaction.DownloadManager$MmsDownloadReceiver.";

        @Override
        public void onReceive(Context context, Intent intent) {
            context.unregisterReceiver(this);

            PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMS DownloadReceiver");
            wakeLock.acquire(60 * 1000);

            Intent newIntent = (Intent) intent.clone();
            newIntent.setAction(MmsReceivedReceiver.MMS_RECEIVED);
            BroadcastUtils.sendExplicitBroadcast(context, newIntent, MmsReceivedReceiver.MMS_RECEIVED);
        }
    }

    public static void finishDownload(String location) {
        if (location != null) {
            mMap.remove(location);
        }
    }

    private static boolean isNotificationExist(Context context, String location) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return true;
        }

        String selection = Telephony.Mms.CONTENT_LOCATION + " = ?";
        String[] selectionArgs = new String[] { location };
        Cursor c = SqliteWrapper.query(
                context, context.getContentResolver(),
                Telephony.Mms.CONTENT_URI, new String[] { Telephony.Mms._ID },
                selection, selectionArgs, null);
        if (c != null) {
            try {
                if (c.getCount() > 0) {
                    return true;
                }
            } finally {
                c.close();
            }
        }

        return false;
    }
}
