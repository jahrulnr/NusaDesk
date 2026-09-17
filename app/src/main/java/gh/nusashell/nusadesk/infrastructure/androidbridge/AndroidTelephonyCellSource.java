package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.os.Build;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoTdscdma;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrength;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, permission-aware cell-info adapter.
 *
 * <p>Cell info is location data: each read first resolves the foreground
 * location grant and consults {@link CellInfoAccessPolicy}, so a missing or
 * denied grant — or a coarse-only grant on API 29/30 where the platform
 * demands fine — is an explicit typed state and never a permission prompt.
 * The returned entries carry radio technology and, when valid, signal
 * strength only: cell identity (CID, LAC/TAC, PCI/PSC, ARFCN/EARFCN, timing
 * advance) and camped/registered state are precise location details and are
 * deliberately redacted. Entries are capped at
 * {@link MessagingReadPolicy#MAX_CELL_ROWS}; any capping sets the truncated
 * flag.</p>
 */
public final class AndroidTelephonyCellSource implements TelephonyCellSource {
    private final Context context;
    private final LocationPermissionChecker locationPermissionChecker;

    public AndroidTelephonyCellSource(Context context) {
        this(context, new AndroidLocationPermissionChecker(context));
    }

    AndroidTelephonyCellSource(Context context, LocationPermissionChecker locationPermissionChecker) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (locationPermissionChecker == null) {
            throw new IllegalArgumentException("locationPermissionChecker must not be null");
        }
        this.context = context.getApplicationContext();
        this.locationPermissionChecker = locationPermissionChecker;
    }

    @Override
    public TelephonyCellInfo read() {
        TelephonyManager manager = telephonyManager();
        if (manager == null) {
            return TelephonyCellInfo.unavailable();
        }
        int phoneType;
        try {
            phoneType = manager.getPhoneType();
        } catch (SecurityException e) {
            return TelephonyCellInfo.permissionDenied();
        } catch (RuntimeException e) {
            return TelephonyCellInfo.error();
        }
        if (phoneType == TelephonyManager.PHONE_TYPE_NONE) {
            return TelephonyCellInfo.noTelephony();
        }
        LocationGrant grant = locationPermissionChecker.check();
        if (grant == LocationGrant.REQUIRED) {
            return TelephonyCellInfo.permissionRequired();
        }
        if (grant == LocationGrant.DENIED) {
            return TelephonyCellInfo.permissionDenied();
        }
        if (!CellInfoAccessPolicy.allows(grant, Build.VERSION.SDK_INT)) {
            // Coarse-only on API 29/30 is not enough for the platform; the
            // consent flow may still ask for fine, so this is REQUIRED, not DENIED.
            return TelephonyCellInfo.permissionRequired();
        }
        List<CellInfo> cells;
        try {
            cells = manager.getAllCellInfo();
        } catch (SecurityException e) {
            // A grant revoked between the check and the call.
            return TelephonyCellInfo.permissionDenied();
        } catch (RuntimeException e) {
            return TelephonyCellInfo.error();
        }
        if (cells == null) {
            return TelephonyCellInfo.unavailable();
        }
        boolean truncated = false;
        List<TelephonyCellInfo.CellEntry> entries = new ArrayList<>();
        for (CellInfo cell : cells) {
            if (entries.size() >= MessagingReadPolicy.MAX_CELL_ROWS) {
                truncated = true;
                break;
            }
            TelephonyCellInfo.CellEntry entry = mapCell(cell);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return TelephonyCellInfo.reading(entries, truncated);
    }

    private TelephonyManager telephonyManager() {
        try {
            return (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Public per-technology signal accessor. The base
     * {@link CellInfo#getCellSignalStrength()} is only public from API 30
     * (hidden on API 29), so the subclass accessors — public since API 17/18
     * — keep the read within the public SDK contract on the API 29 floor.
     */
    private static CellSignalStrength signalOf(CellInfo cell) {
        if (cell instanceof CellInfoGsm) {
            return ((CellInfoGsm) cell).getCellSignalStrength();
        }
        if (cell instanceof CellInfoCdma) {
            return ((CellInfoCdma) cell).getCellSignalStrength();
        }
        if (cell instanceof CellInfoWcdma) {
            return ((CellInfoWcdma) cell).getCellSignalStrength();
        }
        if (cell instanceof CellInfoTdscdma) {
            return ((CellInfoTdscdma) cell).getCellSignalStrength();
        }
        if (cell instanceof CellInfoLte) {
            return ((CellInfoLte) cell).getCellSignalStrength();
        }
        if (cell instanceof CellInfoNr) {
            return ((CellInfoNr) cell).getCellSignalStrength();
        }
        return null;
    }

    /**
     * Map one platform cell into the bounded, identity-redacted contract. A
     * cell whose signal cannot be read at all yields a technology-only entry;
     * a cell that maps to no known technology is skipped rather than guessed.
     */
    private static TelephonyCellInfo.CellEntry mapCell(CellInfo cell) {
        if (cell == null) {
            return null;
        }
        String technology;
        if (cell instanceof CellInfoGsm) {
            technology = "gsm";
        } else if (cell instanceof CellInfoCdma) {
            technology = "cdma";
        } else if (cell instanceof CellInfoWcdma) {
            technology = "wcdma";
        } else if (cell instanceof CellInfoTdscdma) {
            technology = "tdscdma";
        } else if (cell instanceof CellInfoLte) {
            technology = "lte";
        } else if (cell instanceof CellInfoNr) {
            technology = "nr";
        } else {
            return null;
        }
        Integer signalDbm = null;
        Integer signalLevel = null;
        try {
            CellSignalStrength signal = signalOf(cell);
            if (signal != null) {
                int dbm = signal.getDbm();
                if (dbm != Integer.MAX_VALUE && dbm < 0) {
                    signalDbm = dbm;
                    int level = signal.getLevel();
                    if (level >= 0 && level <= 4) {
                        signalLevel = level;
                    }
                }
            }
        } catch (RuntimeException e) {
            // A platform quirk drops the signal, never the whole read.
        }
        try {
            return new TelephonyCellInfo.CellEntry(technology, signalDbm, signalLevel);
        } catch (IllegalArgumentException invalid) {
            // Impossible under the mapping above; fail closed, never guess.
            return null;
        }
    }
}
