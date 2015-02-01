package org.briarproject.bonjour;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup.LayoutParams;

public class HorizontalBorder extends View {

	public HorizontalBorder(Context ctx) {
		super(ctx);
		setLayoutParams(new LayoutParams(MATCH_PARENT, 1));
		Resources res = getResources();
		setBackgroundColor(res.getColor(android.R.color.darker_gray));
	}
}
