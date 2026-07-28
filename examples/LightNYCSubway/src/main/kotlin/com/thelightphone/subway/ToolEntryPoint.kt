package com.thelightphone.subway

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * Required entry point. This tool is fully client-side (no push notifications or
 * app server), so there is nothing to initialize here.
 */
@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {}
    override suspend fun onPushNotification(data: ByteArray) {}
}
