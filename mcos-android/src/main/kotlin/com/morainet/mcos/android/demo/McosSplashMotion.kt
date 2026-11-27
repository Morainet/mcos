package com.morainet.mcos.android.demo

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Build
import android.view.View
import android.window.SplashScreenView
import android.widget.ImageView
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreenViewProvider

private const val MARK_BEFORE_FADE_MS = 205L
private const val FADE_DURATION_MS = 135L

internal fun SplashScreenViewProvider.animateToWorkspace() {
    if (!ValueAnimator.areAnimatorsEnabled()) {
        remove()
        return
    }

    val window = view
    val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (window as? SplashScreenView)?.iconView
    } else {
        iconView
    }

    val mark = (icon as? ImageView)?.let { image ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Match the adaptive foreground bounds used for the platform's static splash icon.
            val scale = 1f + 2f * AdaptiveIconDrawable.getExtraInsetFraction()
            image.scaleX = scale
            image.scaleY = scale
        }
        (image.context.getDrawable(R.drawable.mcos_splash_mark_motion) as AnimatedVectorDrawable).also { animation ->
            image.setImageDrawable(animation)
            animation.start()
        }
    }
    ObjectAnimator.ofFloat(window, View.ALPHA, 0f).apply {
        startDelay = if (mark == null) 0L else MARK_BEFORE_FADE_MS
        duration = FADE_DURATION_MS
        doOnEnd { remove() }
        start()
    }
}
