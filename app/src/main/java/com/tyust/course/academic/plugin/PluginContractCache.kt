package com.tyust.course.academic.plugin

import android.content.Context
import android.content.res.AssetManager
import java.util.WeakHashMap

/** APK contract assets cannot change during a process lifetime. */
internal object PluginContractCache {
    private val schemas = WeakHashMap<AssetManager, PluginSchema>()
    @Synchronized fun schema(context: Context): PluginSchema = schemas.getOrPut(context.assets) {
        PluginSchema(PluginJson.parse(context.assets.open("academic-plugin/contract.schema.json").bufferedReader().use { it.readText() }))
    }
}
