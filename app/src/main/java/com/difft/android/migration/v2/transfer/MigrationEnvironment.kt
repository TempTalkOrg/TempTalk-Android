package com.difft.android.migration.v2.transfer

import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.p2p.engine.qr.QrPayloadV2Codec

/**
 * The environment's cross-platform wire name (`online` / `test`), mapped from `BuildConfig.ENVIRONMENT`
 * at the boundary. Shared with [QrPayloadV2Codec.Environment] so the QR byte and the string agree.
 */
object MigrationEnvironment {

    /** This build's environment under its wire name. */
    fun wireName(helper: EnvironmentHelper): String = when (helper.currentEnvironment) {
        helper.ENVIRONMENT_ONLINE -> QrPayloadV2Codec.Environment.PROD
        helper.ENVIRONMENT_DEVELOPMENT -> QrPayloadV2Codec.Environment.TEST
        else -> error("unknown build environment")
    }
}
