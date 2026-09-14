package gh.nusashell.nusadesk.presentation.widget;

import android.app.Activity;
import android.app.AlertDialog;

import gh.nusashell.nusadesk.R;

/** Reusable progressive-disclosure dialog for how the product works. */
public final class FoundationContractDialog {
    private final AlertDialog dialog;

    public FoundationContractDialog(Activity activity) {
        dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.system_how_title)
                .setMessage(R.string.system_how_body)
                .setPositiveButton(R.string.dialog_close, null)
                .create();
    }

    /** Shows the contract without changing host state. */
    public void show() {
        dialog.show();
    }
}
