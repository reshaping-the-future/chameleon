/*
 * Copyright (c) 2014 Markus Hi
 * Portions copyright (c) 2014 Simon Robinson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ac.robinson.chameleonnotifier.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.support.annotation.NonNull;
import android.util.AttributeSet;

public class CircleImageButton extends android.support.v7.widget.AppCompatImageView {

	private int mCentreY;
	private int mCentreX;
	private int mRadius;

	private Paint mPaint;

	public CircleImageButton(Context context) {
		super(context);
		initialise();
	}

	public CircleImageButton(Context context, AttributeSet attrs) {
		super(context, attrs);
		initialise();
	}

	public CircleImageButton(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		initialise();
	}


	private void initialise() {
		setScaleType(ScaleType.CENTER_INSIDE);
		setFocusable(true);
		setClickable(true);

		mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		mPaint.setStyle(Paint.Style.FILL);

		setColour(Color.BLACK); // default
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		canvas.drawCircle(mCentreX, mCentreY, mRadius, mPaint);
		super.onDraw(canvas);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		mCentreX = w / 2;
		mCentreY = h / 2;
		mRadius = Math.min(w, h) / 2;
	}

	public void setColour(int pressedColor) {
		mPaint.setColor(pressedColor);
		invalidate();
	}
}
