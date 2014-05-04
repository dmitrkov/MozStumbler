package org.mozilla.mozstumbler.cellscanner;

import android.content.Context;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.cyanogenmod.samsungservicemode.OemCommands;

import org.mozilla.mozstumbler.BuildConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru0xdc.samsung.ril.multiclient.app.Utils;
import ru0xdc.samsung.ril.multiclient.app.rilexecutor.DetectResult;
import ru0xdc.samsung.ril.multiclient.app.rilexecutor.RawResult;
import ru0xdc.samsung.ril.multiclient.app.rilexecutor.SamsungMulticlientRilExecutor;


public class SamsungServiceModeCellScanner implements CellScanner.CellScannerImpl {
    private static final boolean DBG = BuildConfig.DEBUG && true;
    private static final String LOGTAG = "SamsungServiceModeCellScanner";

    private static final int ID_REQUEST_START_SERVICE_MODE_COMMAND = 1;
    private static final int ID_REQUEST_FINISH_SERVICE_MODE_COMMAND = 2;
    private static final int ID_REQUEST_EXECUTE_KEY_SEQUENCE = 3;
    private static final int ID_REQUEST_PRESS_A_KEY = 4;
    private static final int ID_REQUEST_REFRESH = 5;

    private static final int ID_RESPONSE = 101;
    private static final int ID_RESPONSE_FINISH_SERVICE_MODE_COMMAND = 102;
    private static final int ID_RESPONSE_PRESS_A_KEY = 103;

    private static final int REQUEST_TIMEOUT = 10000; // ms

    private SamsungMulticlientRilExecutor mRequestExecutor;

    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private final ConditionVariable mRequestCondvar = new ConditionVariable();
    private final List<String> mLastResponse = new ArrayList<String>();

    private final int mPhoneType;
    private final TelephonyManager mTelephonyManager;

    private final Matcher mWcdmaNetTypeMatcher;
    private final Matcher mWcdmaRegPlmnMatcher;
    private final Matcher mWcdmaHspaPlusUsedMatcher;
    private final Matcher mWcdmaChUplDlMatcher;
    private final Matcher mWcdmaCidMatcher;
    private final Matcher mWcdmaLacMatcher;
    private final Matcher mWcdmaPscMatcher;
    private final Matcher mWcdmaRscpMatcher;
    private final Matcher mUmtMatcher;
    private final Matcher mUmtsNeighbourMatcher;

    private final Matcher mGsmMatcher;
    private final Matcher mGsmBandBsicMatcher;
    private final Matcher mGsmPlmnMatcher;
    private final Matcher mGsmTchBcchFreqMatcher;
    private final Matcher mGsmRssiRxLevMatcher;
    private final Matcher mGsmAvgRssiMatcher;
    private final Matcher mGsmCidLacMatcher;
    private final Matcher mGsmNomTxLevMatcher;
    private final Matcher mGsmTsVocTypeMatcher;


    public SamsungServiceModeCellScanner(Context serviceContext) {
        mTelephonyManager = (TelephonyManager) serviceContext.getSystemService(Context.TELEPHONY_SERVICE);

        if (mTelephonyManager == null) {
            throw new UnsupportedOperationException("TelephonyManager service is not available");
        }

        mPhoneType = mTelephonyManager.getPhoneType();

        mUmtMatcher = Pattern.compile("UMTS\\s+:\\s+(.+)").matcher("");
        // Matcher mRrcStateMatcher = Pattern.compile("RRC State\\s+:\\s+(.+)").matcher("");

        mWcdmaNetTypeMatcher = Pattern.compile("WCDMA\\s+\\d+\\s+Band\\s+\\d").matcher("");
        mWcdmaRegPlmnMatcher = Pattern.compile("Reg\\s+PLMN\\s+(\\d+)\\-(\\d+),\\s*IsPCS\\?\\s+(\\d+)").matcher("");
        mWcdmaHspaPlusUsedMatcher = Pattern.compile("HSPA\\+\\s+used:\\s+(\\d+)").matcher("");
        mWcdmaChUplDlMatcher = Pattern.compile("CH\\s+DL:\\s*(\\d)+,\\s+UL:\\s*(\\d+)").matcher("");
        mWcdmaCidMatcher = Pattern.compile("CELL_ID\\s*:\\s*0x([0-9a-fA-F]+)").matcher("");
        mWcdmaLacMatcher = Pattern.compile("LAC\\s*:\\s*0x([0-9a-fA-F]+)").matcher("");
        mWcdmaPscMatcher = Pattern.compile("PSC\\s*:\\s*([0-9a-fA-F]+)").matcher("");
        mWcdmaRscpMatcher = Pattern.compile("RSCP:\\s*(\\-?\\d+)\\s*\\(AVG\\s*:\\s*(\\-?\\d+)\\s*\\),\\s*ECIO:\\s*(\\-?\\d+)").matcher("");
        mUmtsNeighbourMatcher = Pattern.compile("PSC\\s*:\\s*(\\d*),\\s*RSCP\\s*:\\s*(\\-?\\d*),\\s*Type\\s*:\\s*(\\d+)").matcher("");

        mGsmMatcher = Pattern.compile("GSM\\s+:\\s+(.+)").matcher("");
        mGsmBandBsicMatcher = Pattern.compile("Band\\s*:\\s*(.+),\\s*BSIC\\s*:\\s*(\\d+)").matcher("");
        mGsmPlmnMatcher = Pattern.compile("Reg\\s*PLMN\\s+MCC\\((\\d+)\\)-MNC\\((\\d)\\).*").matcher("");
        mGsmTchBcchFreqMatcher = Pattern.compile("TchFrq\\s*:\\s*(\\d*)\\s*,\\s*BcchFrq\\s*:\\s*(\\d*).*").matcher("");
        mGsmRssiRxLevMatcher = Pattern.compile("RSSI\\s*:\\s*(\\-?\\d+)\\s*,\\s*RxLev\\s*:\\s*(\\-?\\d*).*").matcher("");
        mGsmAvgRssiMatcher = Pattern.compile("AVG\\s+RSSI\\s*:\\s*(\\-?\\d+)").matcher("");
        mGsmCidLacMatcher = Pattern.compile("Cell_id\\s*:\\s*(\\d+)\\s*,\\s*LAC\\s*:\\s*(\\d*)").matcher("");
        mGsmNomTxLevMatcher = Pattern.compile("NOM\\s*:\\s*(\\d+),\\s*TxLev\\s*:\\s*(\\d+)").matcher("");
        mGsmTsVocTypeMatcher = Pattern.compile("TS\\s*:\\s*(\\d+),\\s*Voc\\s*Type\\s*:\\s*(\\d+)").matcher("");

    }

    @Override
    public void start() {
        mRequestExecutor = new SamsungMulticlientRilExecutor();
        DetectResult r = mRequestExecutor.detect();
        if (!r.available) {
            throw new UnsupportedOperationException(r.error);
        }
        mRequestExecutor.start();

        mHandlerThread = new HandlerThread("ServiceModeSeqHandler");
        mHandlerThread.start();

        Looper l = mHandlerThread.getLooper();
        mHandler = new Handler(l, new MyHandler());

        enterServiceMode();
    }

    @Override
    public void stop() {
        try {
            leaveServiceMode();
        } catch (UnsupportedOperationException iae) {
            Log.e(LOGTAG, "leaveServiceMode() failed", iae);
        }

        mHandler = null;
        mHandlerThread.quit();
        mHandlerThread = null;

        mRequestExecutor.stop();
        mRequestExecutor = null;
    }

    @Override
    public List<CellInfo> getCellInfo() {
        List<CellInfo> cells;
        if (!mRequestCondvar.block(100)) return Collections.emptyList();

        cells = new ArrayList<CellInfo>(7);

        List<String> currentInfo = getBasicInfo();
        List<String> neighbours = getNeighbours();

        CellInfo info = parseCurrentCell(currentInfo);
        if (info != null) cells.add(info);
        cells.addAll(parseNeighbours(neighbours));
        return cells;
    }

    private CellInfo parseCurrentCell(List<String> info) {
        CellInfo cell;

        if (info.size() < 2) return null;

        cell = null;
        String s = info.get(0);
        if (mUmtMatcher.reset(s).matches()) {
            // String isHomeStr = mUmtMatcher.group(1);
            cell =  parseWcdmaStatus(info);
        } else if (mGsmMatcher.reset(s).matches()) {
            // String isHomeStr = mGsmMatcher.group(1);
            cell = parseGsmStatus(info);
        } else {
            Log.e(LOGTAG, "Unparseable network type: " + s);
        }

        // pase cell network type?
        if (cell != null) cell.setCellNetworkType(mTelephonyManager.getNetworkType());

        return cell;
    }

    private List<CellInfo> parseNeighbours(List<String> info) {
        List<CellInfo> neighbours;

        if (info.size() < 2) return Collections.emptyList();
        neighbours = new ArrayList<CellInfo>(6);

        for (String s: info) {
            if (mUmtsNeighbourMatcher.reset(s).matches()) {
                int psc = integerOrMaxValue(mUmtsNeighbourMatcher.group(1));
                int rscp = -1 * integerOrMaxValue(mUmtsNeighbourMatcher.group(2));
                // int type = integerOrMaxValue(mUmtsNeighbourMatcher.group(3));
                if (psc != 0xffff && psc != Integer.MAX_VALUE && rscp != Integer.MAX_VALUE) {
                    final CellInfo cellInfo = new CellInfo(mPhoneType);
                    cellInfo.setWcmdaCellInfo(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                            Integer.MAX_VALUE, psc, rscp + 116);
                    cellInfo.setNetworkOperator(mTelephonyManager.getNetworkOperator());
                    neighbours.add(cellInfo);
                }
            }
        }
        return neighbours;
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private CellInfo parseWcdmaStatus(List<String> info) {
        int mcc = Integer.MAX_VALUE;
        int mnc = Integer.MAX_VALUE;
        int lac = Integer.MAX_VALUE;
        int cid = Integer.MAX_VALUE;
        int psc = Integer.MAX_VALUE;
        int rscp = Integer.MAX_VALUE;
        boolean plmnFound = false;
        boolean rscpFound = false;

        final CellInfo cellInfo = new CellInfo(mPhoneType);

        Iterator<String> i = info.iterator();
        // UMTS: HOME(CS+PS)
        i.next();
        // RRC State:
        i.next();

        while (i.hasNext()) {
            String s = i.next();
            // XXX: WCDMA 2100 Band 1
            if (mWcdmaNetTypeMatcher.reset(s).matches()) {
                // netType = s;
            // Reg PLMN 250-2, IsPCS? 0
            } else if (mWcdmaRegPlmnMatcher.reset(s).matches()) {
                mcc = integerOrMaxValue(mWcdmaRegPlmnMatcher.group(1));
                mnc = integerOrMaxValue(mWcdmaRegPlmnMatcher.group(2));
                plmnFound = true;
            // HSPA+ used:
            } else if (mWcdmaHspaPlusUsedMatcher.reset(s).matches()) {
                // hspaUsedFound = true;
                // hspaPlusUsed = !"0".equals(mWcdmaHspaPlusUsedMatcher.group(1));
            // CH DL:10687, UL:9737
            } else if (mWcdmaChUplDlMatcher.reset(s).matches()) {
                // chDl = integerOrMaxValue(mWcdmaChUplDlMatcher.group(1));
                // chUl = integerOrMaxValue(mWcdmaChUplDlMatcher.group(2));
            } else if (mWcdmaCidMatcher.reset(s).matches()) {
                cid = integerOrMaxValue(mWcdmaCidMatcher.group(1), 16);
            // LAC: 0xXXX
            } else if (mWcdmaLacMatcher.reset(s).matches()) {
                lac = integerOrMaxValue(mWcdmaLacMatcher.group(1), 16);
            // PSC:8
            } else if (mWcdmaPscMatcher.reset(s).matches()) {
                psc = integerOrMaxValue(mWcdmaPscMatcher.group(1), 16);
                if (psc == 0xffff) psc = Integer.MAX_VALUE;
            // RSCP:-74(AVG:-73), ECIO:-8
            } else if (mWcdmaRscpMatcher.reset(s).matches()) {
                rscpFound = true;
                rscp = integerOrMaxValue(mWcdmaRscpMatcher.group(1));
                // rscpAvg = integerOrMaxValue(mWcdmaRscpMatcher.group(2));
                // ecio = integerOrMaxValue(mWcdmaRscpMatcher.group(3));
            } else if ("AMR-NB".equals(s)) {
                // boolean amrNb = true;
            } else {
                if (DBG) Log.v(LOGTAG, "unparsable string: " + s);
            }
        }

        if (!plmnFound) {
            if (DBG) Log.i(LOGTAG, "plmn not parsed");
            return null;
        }

        if (!rscpFound) {
            if (DBG) Log.i(LOGTAG, "RSCP not parsed");
            return null;
        }

        if (rscp == -371) {
            Log.v(LOGTAG, "rscp is -371. data: " + Arrays.toString(info.toArray(new String[info.size()])));
            return null;
        }

        if (lac == 2147483647 || cid == 2147483647) {
            Log.v(LOGTAG, "lac or cid =-1. data: " + Arrays.toString(info.toArray(new String[info.size()])));
            return null;
        }

        cellInfo.setWcmdaCellInfo(mcc, mnc, lac, cid, psc, rscp + 116);
        return cellInfo;
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private CellInfo parseGsmStatus(List<String> info) {
        int mcc = Integer.MAX_VALUE;
        int mnc = Integer.MAX_VALUE;
        int lac = Integer.MAX_VALUE;
        int cid = Integer.MAX_VALUE;
        int rssi = Integer.MAX_VALUE;
        boolean plmnFound = false;
        boolean rssiFound = false;

        final CellInfo cellInfo = new CellInfo(mPhoneType);

        Iterator<String> i = info.iterator();
        // GSM: HOME(CS+PS)
        i.next();
        while (i.hasNext()) {
            String s = i.next();
            // Band : GSM1800, BSIC: 21
            if (mGsmBandBsicMatcher.reset(s).matches()) {
                // String band = mGsmBandBsicMatcher.group(1);
                // int bsic = integerOrMaxValue(mGsmBandBsicMatcher.group(2));
            // Reg PLMN MCC(XXX)-MNC(XXX), IsPCS
            } else if (mGsmPlmnMatcher.reset(s).matches()) {
                plmnFound = true;
                mcc = integerOrMaxValue(mGsmPlmnMatcher.group(1));
                mnc = integerOrMaxValue(mGsmPlmnMatcher.group(2));
            // TchFrq: , BcchFrq: XXX
            } else if (mGsmTchBcchFreqMatcher.reset(s).matches()) {
                // String chFrq = mGsmTchBcchFreqMatcher.group(1);
                // String bcchFrq = mGsmTchBcchFreqMatcher.group(2);
            } else if (mGsmRssiRxLevMatcher.reset(s).matches()) {
                rssiFound = true;
                rssi = integerOrMaxValue(mGsmRssiRxLevMatcher.group(1));
                // int txLev = integerOrMaxValue(mGsmRssiRxLevMatcher.group(2));
            //  AVG RSSI :   54
            } else if (mGsmAvgRssiMatcher.reset(s).matches()) {
                // String avgRssi = mGsmAvgRssiMatcher.group(1);
            // NOM : 0,   TxLev : 255
            } else if (mGsmNomTxLevMatcher.reset(s).matches()) {
                // String nom = mGsmNomTxLevMatcher.group(1);
                // String txLev = mGsmNomTxLevMatcher.group(2);
            } else if (mGsmCidLacMatcher.reset(s).matches()) {
                cid = integerOrMaxValue(mGsmCidLacMatcher.group(1));
                lac = integerOrMaxValue(mGsmCidLacMatcher.group(2));
            } else if (mGsmTsVocTypeMatcher.reset(s).matches()) {
                // String ts = mGsmTsVocTypeMatcher.group(1);
                // String vocType = mGsmTsVocTypeMatcher.group(2);
            } else if ("AMR-NB".equals(s)) {
                // boolean amrNb = true;
            } else {
                if (DBG) Log.v(LOGTAG, "unparsable string: " + s);
            }
        }

        if (!plmnFound) {
            if (DBG) Log.i(LOGTAG, "plmn not parsed");
            return null;
        }

        if (!rssiFound) {
            if (DBG) Log.i(LOGTAG, "RSSI not parsed");
            return null;
        }

        cellInfo.setGsmCellInfo(mcc, mnc, lac, cid, (rssi + 113) / 2);
        return cellInfo;
    }

    private int integerOrMaxValue(String string) { return integerOrMaxValue(string, 10); }

    private int integerOrMaxValue(String string, int radix) {
        int res = Integer.MAX_VALUE;
        try {
            res = Integer.valueOf(string, radix);
        } catch (NumberFormatException ignore) {
        }
        return res;
    }

    private static final KeyStep GET_BASIC_INFO_KEY_SEQ[] = new KeyStep[]{
            new KeyStep('1', true), // [1] BASIC INFORMATION
            new KeyStep((char)92, false), // back
    };

    public List<String> getBasicInfo() {
        return executeKeySequence(GET_BASIC_INFO_KEY_SEQ, REQUEST_TIMEOUT);
    }

    private static final KeyStep GET_NEIGHBOURS_KEY_SEQ[] = new KeyStep[]{
            new KeyStep('4', true), // [4] NEIGHBOUR CELL
            new KeyStep((char)92, false) // back
    };

    public List<String> getNeighbours() {
        return executeKeySequence(GET_NEIGHBOURS_KEY_SEQ, REQUEST_TIMEOUT);
    }

    private synchronized void enterServiceMode() {
        mRequestCondvar.close();
        mHandler.obtainMessage(ID_REQUEST_START_SERVICE_MODE_COMMAND,
                OemCommands.OEM_SM_TYPE_TEST_MANUAL,
                OemCommands.OEM_SM_TYPE_SUB_ENTER,
                new KeyStep[] {
                    KeyStep.KEY_START_SERVICE_MODE,
                    new KeyStep('1', false), // [1] DEBUG SCREEN
                }).sendToTarget();
        if (!mRequestCondvar.block(REQUEST_TIMEOUT)) {
            Log.e(LOGTAG, "request timeout");
            throw new UnsupportedOperationException("request timeout");
        }
    }

    private synchronized void leaveServiceMode() {
        mRequestCondvar.close();
        mHandler.obtainMessage(ID_REQUEST_FINISH_SERVICE_MODE_COMMAND,
                OemCommands.OEM_SM_TYPE_TEST_MANUAL,
                OemCommands.OEM_SM_TYPE_SUB_ENTER,
                null).sendToTarget();
        if (!mRequestCondvar.block(REQUEST_TIMEOUT)) {
            Log.e(LOGTAG, "request timeout");
            mRequestCondvar.open();
            throw new UnsupportedOperationException("request timeout");
        }
    }

    private synchronized List<String> executeKeySequence(KeyStep keySeqence[], int timeout) {
        if (mRequestExecutor == null) return Collections.emptyList();

        mRequestCondvar.close();
        mHandler.obtainMessage(ID_REQUEST_EXECUTE_KEY_SEQUENCE, keySeqence).sendToTarget();
        if (!mRequestCondvar.block(timeout)) {
            Log.e(LOGTAG, "request timeout");
            mRequestCondvar.open();
            return Collections.emptyList();
        } else {
            synchronized (mLastResponse) {
                return new ArrayList<String>(mLastResponse);
            }
        }
    }

    private static class KeyStep {
        public final char keychar;
        public boolean captureResponse;

        public KeyStep(char keychar, boolean captureResponse) {
            this.keychar = keychar;
            this.captureResponse = captureResponse;
        }

        public static KeyStep KEY_START_SERVICE_MODE = new KeyStep('\0', true);
    }

    private class MyHandler implements Handler.Callback {

        private int mCurrentType;
        private int mCurrentSubtype;

        private Queue<KeyStep> mKeySequence;

        private List<String> mResponse = new ArrayList<String>();

        @Override
        public boolean handleMessage(Message msg) {
            byte[] requestData;
            Message responseMsg;
            KeyStep lastKeyStep;

            switch (msg.what) {
                case ID_REQUEST_START_SERVICE_MODE_COMMAND:
                    if (DBG) Log.v(LOGTAG, "ID_REQUEST_START_SERVICE_MODE_COMMAND");
                    mCurrentType = msg.arg1;
                    mCurrentSubtype = msg.arg2;
                    mResponse.clear();

                    if (msg.obj != null) {
                        mKeySequence = new LinkedList<KeyStep>(Arrays.asList((KeyStep[]) msg.obj));
                    } else {
                        mKeySequence = new LinkedList<KeyStep>(Collections.singleton(KeyStep.KEY_START_SERVICE_MODE));
                    }

                    requestData = OemCommands.getEnterServiceModeData(
                            mCurrentType, mCurrentSubtype, OemCommands.OEM_SM_ACTION);
                    responseMsg = mHandler.obtainMessage(ID_RESPONSE);
                    mRequestExecutor.invokeOemRilRequestRaw(requestData, responseMsg);
                    break;
                case ID_REQUEST_EXECUTE_KEY_SEQUENCE:
                    if (DBG) Log.v(LOGTAG, "ID_REQUEST_EXECUTE_KEY_SEQUENCE");
                    if (msg.obj != null) {
                        mKeySequence = new LinkedList<KeyStep>(Arrays.asList((KeyStep []) msg.obj));
                    } else {
                        throw new IllegalArgumentException("key sequence is null");
                    }
                    mHandler.obtainMessage(ID_REQUEST_PRESS_A_KEY, mKeySequence.element().keychar, 0).sendToTarget();
                    break;
                case ID_REQUEST_FINISH_SERVICE_MODE_COMMAND:
                    if (DBG) Log.v(LOGTAG, "ID_REQUEST_FINISH_SERVICE_MODE_COMMAND");
                    requestData = OemCommands.getEndServiceModeData(mCurrentType);
                    responseMsg = mHandler.obtainMessage(ID_RESPONSE_FINISH_SERVICE_MODE_COMMAND);
                    mRequestExecutor.invokeOemRilRequestRaw(requestData, responseMsg);
                    break;
                case ID_REQUEST_PRESS_A_KEY:
                    if (DBG) Log.v(LOGTAG, "ID_REQUEST_PRESS_A_KEY " + (char)msg.arg1);
                    requestData = OemCommands.getPressKeyData(msg.arg1, OemCommands.OEM_SM_ACTION);
                    responseMsg = mHandler.obtainMessage(ID_RESPONSE_PRESS_A_KEY);
                    mRequestExecutor.invokeOemRilRequestRaw(requestData, responseMsg);
                    break;
                case ID_REQUEST_REFRESH:
                    if (DBG) Log.v(LOGTAG, "ID_REQUEST_REFRESH");
                    requestData = OemCommands.getPressKeyData('\0', OemCommands.OEM_SM_QUERY);
                    responseMsg = mHandler.obtainMessage(ID_RESPONSE);
                    mRequestExecutor.invokeOemRilRequestRaw(requestData, responseMsg);
                    break;
                case ID_RESPONSE:
                    if (DBG) Log.v(LOGTAG, "ID_RESPONSE");
                    lastKeyStep = mKeySequence.poll();
                    try {
                        RawResult result = (RawResult) msg.obj;
                        if (result == null) {
                            Log.e(LOGTAG, "result is null");
                            break;
                        }
                        if (result.exception != null) {
                            Log.e(LOGTAG, "", result.exception);
                            break;
                        }
                        if (result.result == null) {
                            Log.v(LOGTAG, "No need to refresh.");
                            break;
                        }
                        if (lastKeyStep.captureResponse) mResponse.addAll(Utils.unpackListOfStrings(result.result));
                    } finally {
                        if (mKeySequence.isEmpty()) {
                            synchronized (mLastResponse) {
                                mLastResponse.clear();
                                mLastResponse.addAll(mResponse);
                                mResponse.clear();
                            }
                            mRequestCondvar.open();
                        } else {
                            mHandler.obtainMessage(ID_REQUEST_PRESS_A_KEY, mKeySequence.element().keychar, 0).sendToTarget();
                        }
                    }
                    break;
                case ID_RESPONSE_PRESS_A_KEY:
                    if (DBG) Log.v(LOGTAG, "ID_RESPONSE_PRESS_A_KEY");
                    try {
                        Thread.sleep(10);
                        mHandler.obtainMessage(ID_REQUEST_REFRESH).sendToTarget();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    break;
                case ID_RESPONSE_FINISH_SERVICE_MODE_COMMAND:
                    if (DBG) Log.v(LOGTAG, "ID_RESPONSE_FINISH_SERVICE_MODE_COMMAND");
                    mRequestCondvar.open();
                    break;

            }
            return true;
        }
    }
}
