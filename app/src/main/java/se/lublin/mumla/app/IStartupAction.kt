package se.lublin.mumla.app

import android.app.Activity

interface IStartupAction {
    fun execute(activity: Activity)
}
