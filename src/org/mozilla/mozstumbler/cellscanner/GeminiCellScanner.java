package org.mozilla.mozstumbler.cellscanner;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.telephony.CellLocation;
import android.telephony.NeighboringCellInfo;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;

import ru0xdc.mtk.service.CsceEMServCellSStatusInd;
import ru0xdc.mtk.service.IMtkServiceMode;

/**
 * MediaTek Multi-SIM 'gemini' TelephonyManager API.
 */
class GeminiCellScanner implements CellScanner.CellScannerImpl {
    private static final String LOGTAG = GeminiCellScanner.class.getName();

    private static final int MAX_GEMINI_SIM_NUM = 4;

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;

    private final int mPresentSimNums[];
    private final int mPhoneTypes[];
    private final PhoneStateListener mPhoneStateListeners[];

    private AtomicIntegerArray mSignalStrength;
    private AtomicIntegerArray mCdmaDbm;

    private final Method mMethodListenGemini;
    private final Method mMethodGetCellLocationGemini;
    private final Method mMethodGetNeighboringCellInfoGemini;
    private final Method mMethodGetNetworkOperatorGemini;
    private final Method mMethodGetNetworkTypeGemini;

    private boolean mBound;
    private IMtkServiceMode mMtkService;

    private final ScreenMonitor mScreenMonitor;

    GeminiCellScanner(Context context) throws UnsupportedOperationException {
        int presentSimNums[] = new int[MAX_GEMINI_SIM_NUM];
        int presentPhoneTypes[] = new int[MAX_GEMINI_SIM_NUM];
        int presentSimCnt;
        final Method methodGetPhoneTypeGemini;
        final Method methodGetSimStateGemini;

        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (mTelephonyManager == null) {
            throw new UnsupportedOperationException();
        }

        final Class<? extends TelephonyManager> tmClass = mTelephonyManager.getClass();
        try {
            methodGetPhoneTypeGemini = tmClass.getMethod("getPhoneTypeGemini", int.class);
            methodGetSimStateGemini = tmClass.getMethod("getSimStateGemini", int.class);
            mMethodListenGemini = tmClass.getMethod("listenGemini", android.telephony.PhoneStateListener.class, int.class, int.class);
            mMethodGetCellLocationGemini = tmClass.getMethod("getCellLocationGemini", int.class);
            mMethodGetNeighboringCellInfoGemini = tmClass.getMethod("getNeighboringCellInfoGemini", int.class);
            mMethodGetNetworkOperatorGemini = tmClass.getMethod("getNetworkOperatorGemini", int.class);
            mMethodGetNetworkTypeGemini = tmClass.getMethod("getNetworkTypeGemini", int.class);
        } catch (NoSuchMethodException nsme) {
            throw new UnsupportedOperationException(nsme);
        }

        presentSimCnt = 0;
        try {
            for (int simNo = 0; simNo < MAX_GEMINI_SIM_NUM; ++simNo) {
                final int simStatus = (Integer) methodGetSimStateGemini.invoke(mTelephonyManager, simNo);
                switch (simStatus) {
                    case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
                    case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                    case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                    case TelephonyManager.SIM_STATE_READY:
                        int type = (Integer) methodGetPhoneTypeGemini.invoke(mTelephonyManager, simNo);
                        if (type == TelephonyManager.PHONE_TYPE_CDMA
                                || type == TelephonyManager.PHONE_TYPE_GSM) {
                            presentSimNums[presentSimCnt] = simNo;
                            presentPhoneTypes[presentSimCnt] = type;
                            presentSimCnt += 1;
                        }
                        break;
                }
            }
        } catch (Exception ex) {
            throw new UnsupportedOperationException(ex);
        }

        if (presentSimCnt == 0) {
            throw new UnsupportedOperationException("No SIM cards available");
        }

        mPresentSimNums = new int[presentSimCnt];
        System.arraycopy(presentSimNums, 0, mPresentSimNums, 0, presentSimCnt);
        mPhoneTypes = new int[presentSimCnt];
        System.arraycopy(presentPhoneTypes, 0, mPhoneTypes, 0, presentSimCnt);
        mSignalStrength = new AtomicIntegerArray(presentSimCnt);
        mCdmaDbm = new AtomicIntegerArray(presentSimCnt);
        mPhoneStateListeners = new PhoneStateListener[presentSimCnt];

        for (int i = 0; i < presentSimCnt; ++i) {
            mPhoneStateListeners[i] = new PhoneStateListener(i);
        }

        mContext = context;
        mScreenMonitor = new ScreenMonitor(context);

        Log.i(LOGTAG, "Numbers of available SIM cards: " + Arrays.toString(mPresentSimNums));
    }

    @Override
    public void start() {
        for (int i = 0; i < mSignalStrength.length(); ++i) {
            mSignalStrength.set(i, CellInfo.UNKNOWN_SIGNAL);
        }
        for (int i = 0; i < mCdmaDbm.length(); ++i) {
            mCdmaDbm.set(i, CellInfo.UNKNOWN_SIGNAL);
        }

        try {
            for (int i = 0; i < mPhoneStateListeners.length; ++i) {
                mMethodListenGemini.invoke(mTelephonyManager,
                        mPhoneStateListeners[i],
                        PhoneStateListener.LISTEN_SIGNAL_STRENGTHS | PhoneStateListener.LISTEN_CELL_LOCATION,
                        mPresentSimNums[i]
                );
            }
        } catch (Exception ex) {
            Log.e(LOGTAG, "listen.invoke() failed", ex);
        }

        Intent i = new Intent("ru0xdc.mtk.service.MtkService");
        mContext.bindService(i, mMtkServiceConnection, Context.BIND_AUTO_CREATE);

        mScreenMonitor.start();
    }

    @Override
    public void stop() {
        try {
            for (int i = 0; i < mPhoneStateListeners.length; ++i) {
                mMethodListenGemini.invoke(mTelephonyManager,
                        mPhoneStateListeners[i],
                        PhoneStateListener.LISTEN_NONE,
                        mPresentSimNums[i]
                );
            }
        } catch (Exception ex) {
            Log.e(LOGTAG, "listen.invoke() failed", ex);
        }

        for (int i = 0; i < mSignalStrength.length(); ++i) {
            mSignalStrength.set(i, CellInfo.UNKNOWN_SIGNAL);
        }
        for (int i = 0; i < mCdmaDbm.length(); ++i) {
            mCdmaDbm.set(i, CellInfo.UNKNOWN_SIGNAL);
        }

        mScreenMonitor.stop();

        if (mBound) mContext.unbindService(mMtkServiceConnection);
    }

    @Override
    public List<CellInfo> getCellInfo() {
        final List<CellInfo> cellsInfo;

        cellsInfo = new ArrayList<CellInfo>();

        for (int i = 0; i < mPresentSimNums.length; ++i) {
            cellsInfo.addAll(getCellInfo(i));
        }
        return cellsInfo;
    }

    @SuppressWarnings("unchecked")
    private List<CellInfo> getCellInfo(int presentSimNumsIndex) {
        CellLocation cl;
        List<NeighboringCellInfo> neighbours;
        String networkOperator;
        int networkType;
        CsceEMServCellSStatusInd mStatusInd = null;

        try {
            cl = (CellLocation) mMethodGetCellLocationGemini.invoke(
                    mTelephonyManager, mPresentSimNums[presentSimNumsIndex]
            );
            if (cl == null) {
                return Collections.emptyList();
            }
            if (presentSimNumsIndex == 0) {
                mScreenMonitor.putLocation(cl);
            }
            if (!mScreenMonitor.isLocationValid()) {
                return Collections.emptyList();
            }

            neighbours = (List<NeighboringCellInfo>) mMethodGetNeighboringCellInfoGemini.invoke(
                    mTelephonyManager, mPresentSimNums[presentSimNumsIndex]
            );
            networkOperator = (String) mMethodGetNetworkOperatorGemini.invoke(
                    mTelephonyManager, mPresentSimNums[presentSimNumsIndex]
            );
            networkType = (Integer) mMethodGetNetworkTypeGemini.invoke(
                    mTelephonyManager, mPresentSimNums[presentSimNumsIndex]
            );
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        if (mBound &&
                presentSimNumsIndex == 0 &&
                CellInfo.CELL_RADIO_UMTS.equals(CellInfo.getCellRadioTypeName(networkType))) {
            try {
                mStatusInd = mMtkService.getCsceEMServCellSStatusInd();
            } catch (RemoteException ignore) {
            }
        }

        final List<CellInfo> cells = new ArrayList<CellInfo>();
        final CellInfo current = new CellInfo(mPhoneTypes[presentSimNumsIndex]);
        try {
            final int signalStrength = mSignalStrength.get(presentSimNumsIndex);
            final int cdmaDbm = mCdmaDbm.get(presentSimNumsIndex);
            current.setCellLocation(cl,
                    networkType,
                    networkOperator,
                    signalStrength == CellInfo.UNKNOWN_SIGNAL ? null : signalStrength,
                    cdmaDbm == CellInfo.UNKNOWN_SIGNAL ? null : cdmaDbm);
            if (mStatusInd != null) {
                if (mStatusInd.cellIdentity == current.getCid()) {
                    if (current.getPsc() == -1 && mStatusInd.psc >= 0 && mStatusInd.psc <= 511) {
                        current.setPsc(mStatusInd.psc);
                    }
                } else {
                    Log.i(LOGTAG, "mStatusInd.cellIdentity != current.getCid() status ind "
                            + mStatusInd.cellIdentity + " != " +  current.getCid());
                }
                Log.v(LOGTAG, "status ind: " + mStatusInd
                                + " current signal: " + current.getAsu()
                                + " current id: " + current.getCellIdentity()
                );
            }
            cells.add(current);
        } catch (IllegalArgumentException iae) {
            Log.e(LOGTAG, "Skip invalid or incomplete CellLocation: " + cl, iae);
        }

        if (neighbours != null) {
            for (NeighboringCellInfo nci : neighbours) {
                try {
                    final CellInfo neighbour = new CellInfo(mPhoneTypes[presentSimNumsIndex]);
                    neighbour.setNeighboringCellInfo(nci, networkOperator);
                    cells.add(neighbour);
                } catch (IllegalArgumentException iae) {
                    Log.e(LOGTAG, "Skip invalid or incomplete NeighboringCellInfo: " + nci, iae);
                }
            }
        }

        return cells;
    }

    private final class PhoneStateListener extends android.telephony.PhoneStateListener {
        private final int mSimNum;

        public PhoneStateListener(int simNum) {
            mSimNum = simNum;
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength ss) {
            if (ss.isGsm()) {
                mSignalStrength.set(mSimNum, ss.getGsmSignalStrength());
            } else {
                mCdmaDbm.set(mSimNum, ss.getCdmaDbm());
            }
        }

        @Override
        public void onCellLocationChanged(CellLocation location) {
            Log.v(LOGTAG, "onCellLocationChanged(): " + location);
        }
    }

    private ServiceConnection mMtkServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            mMtkService = IMtkServiceMode.Stub.asInterface(service);
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mMtkService = null;
            mBound = false;
        }
    };

    private LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            Log.v(LOGTAG, "network location changed: " + location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {

        }

        @Override
        public void onProviderEnabled(String provider) {

        }

        @Override
        public void onProviderDisabled(String provider) {

        }
    };

}
