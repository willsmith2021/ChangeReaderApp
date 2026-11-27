package com.rt.changereader

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var folder: String?
        get() = sp.getString("folder", null)
        set(v) = sp.edit().putString("folder", v).apply()

    var running: Boolean
        get() = sp.getBoolean("running", false)
        set(v) = sp.edit().putBoolean("running", v).apply()

    var includeHidden: Boolean
        get() = sp.getBoolean("hidden", false)
        set(v) = sp.edit().putBoolean("hidden", v).apply()

    /** Log in-progress writes (throttled). "SAVED" is always logged when a write finishes. */
    var logModify: Boolean
        get() = sp.getBoolean("modify", true)
        set(v) = sp.edit().putBoolean("modify", v).apply()

    var logAttrib: Boolean
        get() = sp.getBoolean("attrib", false)
        set(v) = sp.edit().putBoolean("attrib", v).apply()

    /** Use su (root) to watch protected folders like /data/data/<app>. */
    var useRoot: Boolean
        get() = sp.getBoolean("root", false)
        set(v) = sp.edit().putBoolean("root", v).apply()
}
