package com.velmorath.app

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.bottomnavigation.BottomNavigationView

class SectionNavigationView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : BottomNavigationView(context, attrs) {
    override fun getMaxItemCount(): Int = 6
}
