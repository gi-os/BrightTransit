package com.thelightphone.subway

import android.content.res.Resources
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.thelightphone.sdk.ui.LightIcons
import org.xmlpull.v1.XmlPullParser

/**
 * What the tool's own manifest says about it: the name LightOS lists it under, and whether
 * this is a build made for development.
 *
 * The name is written once, in lighttool.toml, and read from here, so the title on Home is
 * always the name the rider tapped to get there. The SDK gives a tool no way to ask for its
 * own name, so this reads the manifest packed in the tool's APK. That APK is found through
 * one of the kit's icons, which are packed in it too.
 */
class ToolManifest(val label: String?, val debuggable: Boolean) {
    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"

        @Volatile
        private var kept: ToolManifest? = null

        /** Read once and kept; it cannot change while the tool is running. */
        fun of(resources: Resources): ToolManifest = kept ?: read(resources).also { kept = it }

        private fun read(resources: Resources): ToolManifest = runCatching {
            val ours = TypedValue().also { resources.getValue(LightIcons.BACK.drawableResource, it, false) }.assetCookie
            resources.assets.openXmlResourceParser(ours, "AndroidManifest.xml").use { xml ->
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType != XmlPullParser.START_TAG || xml.name != "application") continue
                    // The label is either the words themselves or the id of a string that holds them.
                    val named = xml.getAttributeResourceValue(ANDROID, "label", 0)
                    return@use ToolManifest(
                        label = if (named != 0) resources.getString(named) else xml.getAttributeValue(ANDROID, "label"),
                        debuggable = xml.getAttributeBooleanValue(ANDROID, "debuggable", false),
                    )
                }
                null
            }
        }.getOrNull() ?: ToolManifest(label = null, debuggable = false)
    }
}

@Composable
fun toolManifest(): ToolManifest = ToolManifest.of(LocalResources.current)
