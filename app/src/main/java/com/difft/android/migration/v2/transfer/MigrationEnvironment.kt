package com.difft.android.migration.v2.transfer

import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.p2p.engine.qr.QrPayloadV2Codec





object MigrationEnvironment {

    
    fun wireName(helper: EnvironmentHelper): String = when (helper.currentEnvironment) {
        helper.ENVIRONMENT_ONLINE -> QrPayloadV2Codec.Environment.PROD
        helper.ENVIRONMENT_DEVELOPMENT -> QrPayloadV2Codec.Environment.TEST
        else -> error("unknown build environment")
    }
}
