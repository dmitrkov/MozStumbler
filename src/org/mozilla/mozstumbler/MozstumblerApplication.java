package org.mozilla.mozstumbler;

import android.app.Application;

import org.acra.ACRA;
import org.acra.ReportingInteractionMode;
import org.acra.annotation.ReportsCrashes;

@ReportsCrashes(
        formKey = "",
        mailTo = "bug@0xdc.ru",
        mode = ReportingInteractionMode.NOTIFICATION,
        resToastText = R.string.crash_toast_text,
        resNotifTickerText = R.string.crash_notif_ticker_text,
        resNotifTitle = R.string.crash_notif_title,
        resDialogText = R.string.crash_dialog_text,
        resNotifText = R.string.crash_notif_text
        )
public class MozstumblerApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        if (!BuildConfig.DEBUG && !BuildConfig.ACRA_URL.equals("")) ACRA.init(this);
    }

}
