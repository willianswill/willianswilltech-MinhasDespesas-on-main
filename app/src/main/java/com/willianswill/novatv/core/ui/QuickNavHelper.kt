package com.willianswill.novatv.core.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Button
import com.willianswill.novatv.R
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.feature.home.HomeActivity
import com.willianswill.novatv.feature.livetv.LiveTvActivity
import com.willianswill.novatv.feature.settings.SettingsActivity
import com.willianswill.novatv.feature.vod.VodCatalogActivity

enum class NavTab {
    HOME, LIVE, MOVIES, SERIES, SETTINGS
}

object QuickNavHelper {
    fun bind(activity: Activity, rootNavView: View, activeTab: NavTab) {
        val btnHome = rootNavView.findViewById<Button>(R.id.nav_home)
        val btnLive = rootNavView.findViewById<Button>(R.id.nav_live)
        val btnMovies = rootNavView.findViewById<Button>(R.id.nav_movies)
        val btnSeries = rootNavView.findViewById<Button>(R.id.nav_series)
        val btnSettings = rootNavView.findViewById<Button>(R.id.nav_settings)

        btnHome.isSelected = activeTab == NavTab.HOME
        btnLive.isSelected = activeTab == NavTab.LIVE
        btnMovies.isSelected = activeTab == NavTab.MOVIES
        btnSeries.isSelected = activeTab == NavTab.SERIES
        btnSettings.isSelected = activeTab == NavTab.SETTINGS

        btnHome.setOnClickListener {
            if (activeTab != NavTab.HOME) {
                activity.startActivity(
                    Intent(activity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                activity.finish()
            }
        }
        btnLive.setOnClickListener {
            if (activeTab != NavTab.LIVE) {
                activity.startActivity(Intent(activity, LiveTvActivity::class.java))
                if (activeTab != NavTab.HOME) activity.finish()
            }
        }
        btnMovies.setOnClickListener {
            if (activeTab != NavTab.MOVIES) {
                activity.startActivity(
                    Intent(activity, VodCatalogActivity::class.java).putExtra(VodCatalogActivity.EXTRA_MODE, MediaType.MOVIE)
                )
                if (activeTab != NavTab.HOME) activity.finish()
            }
        }
        btnSeries.setOnClickListener {
            if (activeTab != NavTab.SERIES) {
                activity.startActivity(
                    Intent(activity, VodCatalogActivity::class.java).putExtra(VodCatalogActivity.EXTRA_MODE, MediaType.SERIES)
                )
                if (activeTab != NavTab.HOME) activity.finish()
            }
        }
        btnSettings.setOnClickListener {
            if (activeTab != NavTab.SETTINGS) {
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
                if (activeTab != NavTab.HOME) activity.finish()
            }
        }
    }
}
