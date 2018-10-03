package ac.robinson.chameleonnotifier.view;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * A hack used to show a dialog in Immersive Mode (that is, with the system UI hidden). This approach makes the dialog
 * not focusable before showing it, changes the UI visibility of the window and then (after showing it) makes the
 * dialog focusable again.
 * <p>
 * see: http://stackoverflow.com/q/22794049/1993220
 */
public class CustomDialog extends Dialog {

	private Activity mParentActivity;

	public CustomDialog(Context context, int themeResId) {
		super(context, themeResId);
		if (context instanceof Activity) {
			mParentActivity = (Activity) context;
		}
	}

	@Override
	public void show() {
		Window window = getWindow();
		if (window == null) {
			return;
		}
		window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
		window.getDecorView()
				.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN |
						View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
						View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			window.getDecorView()
					.setSystemUiVisibility(
							window.getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
		}
		super.show(); // show the dialog with NavBar hidden.
		window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE); // focusable again
	}

	@Override
	public void onBackPressed() {
		// propagate to activity parent to avoid having to double press
		dismiss();
		mParentActivity.onBackPressed();
	}
}
