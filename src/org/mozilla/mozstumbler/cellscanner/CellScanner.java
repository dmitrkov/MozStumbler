package org.mozilla.mozstumbler.cellscanner;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import org.mozilla.mozstumbler.BuildConfig;
import org.mozilla.mozstumbler.ScannerService;
import org.mozilla.mozstumbler.preferences.Prefs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CellScanner {
    public static final String CELL_SCANNER_EXTRA_SUBJECT = "CellScanner";
    public static final String CELL_SCANNER_ARG_CELLS = "org.mozilla.mozstumbler.cellscanner.CellScanner.cells";

    private static final boolean DBG = BuildConfig.DEBUG;
    private static final String LOGTAG = CellScanner.class.getName();
    private static final long CELL_MIN_UPDATE_TIME = 1000; // milliseconds

    private final Context mContext;
    private CellScannerImpl mImpl;
    private Handler mHandler;
    private final Set<String> mCells = new HashSet<String>();
    private final PscWatcher mPscChecker = new PscWatcher();
    private int mCurrentCellInfoCount;

    interface CellScannerImpl {
        public void start();

        public void stop();

        public List<CellInfo> getCellInfo();
    }

    public CellScanner(Context context) {
        mContext = context;
    }

    public void start() {
        if (mImpl != null) {
            return;
        }
        Prefs prefs = new Prefs(mContext);

        if (prefs.isMultipleSimEnabled()) {
            try {
                mImpl = new GeminiCellScanner(mContext);
            } catch (UnsupportedOperationException uoe) {
                Log.e(LOGTAG, "CellScannerGeminiImpl() probe failed", uoe);
            }
        }

        if ((mImpl == null) && prefs.isSamsungServiceModeEnabled()) {
            try {
                mImpl = new SamsungServiceModeCellScanner(mContext);
            } catch (UnsupportedOperationException uoe) {
                Log.e(LOGTAG, "SamsungServiceModeCellScanner() probe failed", uoe);
            }
        }

        if (mImpl == null) {
            try {
                mImpl = new DefaultCellScanner(mContext);
            } catch (UnsupportedOperationException uoe) {
                Log.e(LOGTAG, "Cell scanner probe failed", uoe);
                return;
            }
        }

        mPscChecker.reset();
        mImpl.start();
        mHandler = new Handler();
        mHandler.post(mCellScanRunnable);
    }

    private final Runnable mCellScanRunnable = new Runnable() {
        @Override
        public void run() {
            if (DBG) Log.d(LOGTAG, "Cell Scanning Timer fired");
            final long curTime = System.currentTimeMillis();
            ArrayList<CellInfo> cells = new ArrayList<CellInfo>(mImpl.getCellInfo());
            cells = mPscChecker.removeInvalidCells(cells);
            mCurrentCellInfoCount = cells.size();
            if (!cells.isEmpty()) {
                for (CellInfo cell : cells) mCells.add(cell.getCellIdentity());

                Intent intent = new Intent(ScannerService.MESSAGE_TOPIC);
                intent.putExtra(Intent.EXTRA_SUBJECT, CELL_SCANNER_EXTRA_SUBJECT);
                intent.putParcelableArrayListExtra(CELL_SCANNER_ARG_CELLS, cells);
                intent.putExtra("time", curTime);
                LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
            }
            mHandler.postDelayed(this, CELL_MIN_UPDATE_TIME);
        }
    };

    public void stop() {
        if (mHandler != null) {
            mHandler.removeCallbacks(mCellScanRunnable);
            mHandler = null;
        }
        if (mImpl != null) {
            mImpl.stop();
            mImpl = null;
        }
    }

    public int getCellInfoCount() {
        return mCells.size();
    }

    public int getCurrentCellInfoCount() {
        return mCurrentCellInfoCount;
    }


    public static class PscWatcher {
        private static final int WAIT_TIMEOUT_S = 40;
        private int mLastMcc;
        private int mLastMnc;
        private int mLastLac;
        private int mLastCid;
        private int mLastPsc;
        private long mLastUpdateTime;

        public PscWatcher() {
            reset();
        }

        public void reset() {
            mLastMcc = -1;
            mLastMnc = -1;
            mLastLac = -1;
            mLastCid = -1;
            mLastPsc = -1;
            mLastUpdateTime = -1;
        }

        void putCell(CellInfo cell) {
            if (!isUmtsCellWithCidAndPsc(cell)) {
                return;
            }

            if (!isValidCell(cell)) {
                if (Math.abs(System.nanoTime() - mLastUpdateTime) > WAIT_TIMEOUT_S * 1e9) {
                    setCell(cell);
                } else {
                    Log.i(LOGTAG, "PSC is changed while CID remains the same");
                }
            } else {
                setCell(cell);
            }
        }

        public ArrayList<CellInfo> removeInvalidCells(ArrayList<CellInfo> cells) {
            CellInfo invalidCell = null;
            for (CellInfo cell: cells) {
                putCell(cell);
                if (!isValidCell(cell)) {
                    invalidCell = cell;
                    break;
                }
            }
            if (invalidCell == null) {
                return cells;
            } else {
                Log.i(LOGTAG, "PSC is changed while CID remains the same. Remove cell " + invalidCell.getCellIdentity());
                ArrayList<CellInfo> list = new ArrayList<CellInfo>(cells.size() - 1);
                for (CellInfo cell: cells) if (invalidCell != cell) list.add(cell);
                return list;
            }
        }

        boolean isValidCell(CellInfo cell) {
            return !(cell.getPsc() != mLastPsc
                    && isLastMccMncLacCidEquals(cell));
        }

        private boolean isUmtsCellWithCidAndPsc(CellInfo cell) {
            return CellInfo.CELL_RADIO_UMTS.equals(cell.getCellRadio())
                    && cell.getCid() > 0
                    && cell.getPsc() > 0;
        }

        private boolean isLastMccMncLacCidEquals(CellInfo cell) {
            return (cell.getCid() == mLastCid)
                    && (cell.getLac() == mLastLac)
                    && (cell.getMnc() == mLastMnc)
                    && (cell.getMcc() == mLastMcc);
        }

        private void setCell(CellInfo cell) {
            mLastUpdateTime = System.nanoTime();
            mLastMcc = cell.getMcc();
            mLastMnc = cell.getMnc();
            mLastLac = cell.getLac();
            mLastCid = cell.getCid();
            mLastPsc = cell.getPsc();
            if (DBG) Log.v(LOGTAG, "new cell+ " + lastCellToString());
        }

        private String lastCellToString() {
            return mLastUpdateTime + " " + mLastMcc + " " + mLastMnc + " "
                    + mLastLac + " " + mLastCid + " " + mLastPsc;
        }


    }
}
