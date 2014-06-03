package ru0xdc.samsung.ril.multiclient.app.rilexecutor;

import android.os.Message;

public interface OemRilExecutor {

    public DetectResult detect();

    public void start();

    public void stop();

    /**
     * Invokes RIL_REQUEST_OEM_HOOK_RAW.
     *
     * @param data The data for the request.
     * @param response <strong>On success</strong>,
     * (byte[])(((AsyncResult)response.obj).result)
     * <strong>On failure</strong>,
     * (((RawResult)response.obj).result) == null and
     * (((RawResult)response.obj).exception) being an instance of
     * com.android.internal.telephony.gsm.CommandException
     *
     * @see #invokeOemRilRequestRaw(byte[], android.os.Message)
     */
    public void invokeOemRilRequestRaw(byte data[], Message response);


}
