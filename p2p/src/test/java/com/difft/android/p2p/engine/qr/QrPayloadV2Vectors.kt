package com.difft.android.p2p.engine.qr

/**
 * The cross-platform example vectors of the v2 pairing payload (protocol proposal §2.6). Generated
 * by one script together with the proposal text and round-tripped there; do not hand-edit — the
 * three platforms compare against these bytes.
 *
 * Shared inputs: fp = 32 × 0x9f, pin 123456, transferKey = 32 × 0x5a, default port; precheck =
 * prod, appVersion "2.4.4", formatVersion 1.0, account +8613800138000.
 */
object QrPayloadV2Vectors {
    const val FP_HEX = "9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f"
    const val KEY_BASE64 = "WlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlo="
    const val PIN = "123456"
    const val ACCOUNT = "+8613800138000"

    /** A — phone receive code: two addresses, upload, key, precheck; flags 0x06. */
    const val A_HEX = "020602c0a8006ac0a82b019f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f1234565a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a0b506978656c20382050726f0005322e342e3401000e2b38363133383030313338303030"
    const val A_BASE45 = "NB0TF0XALDMDVBLA9038K38K38K38K38K38K38K38K38K38K38K38K38K38K38K.4KXR60JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0JBGJ10FD: CD34$340LE500LF6%Q6%P6E00*K5D%6HL6646+96T47646"
    const val A_BASE64URL = "AgYCwKgAasCoKwGfn5-fn5-fn5-fn5-fn5-fn5-fn5-fn5-fn5-fn5-fnxI0VlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaC1BpeGVsIDggUHJvAAUyLjQuNAEADis4NjEzODAwMTM4MDAw"

    /** B — Desktop login-link inner payload: one address, upload, key, no precheck; flags 0x02. */
    const val B_HEX = "0202010a0000059f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f1234565a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a0e4e6174652773204d6163426f6f6b"
    const val B_BASE64URL = "AgIBCgAABZ-fn5-fn5-fn5-fn5-fn5-fn5-fn5-fn5-fn5-fn5-fEjRWWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWloOTmF0ZSdzIE1hY0Jvb2s"

    /** C — Desktop export code: two addresses, DOWNLOAD, key, precheck; flags 0x07. */
    const val C_HEX = "020702c0a8006ac0a82b019f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f1234565a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a0e4e6174652773204d6163426f6f6b0005322e342e3401000e2b38363133383030313338303030"
    const val C_BASE45 = "OB0TF0XALDMDVBLA9038K38K38K38K38K38K38K38K38K38K38K38K38K38K38K.4KXR60JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0JBH 1IECKZC.OE9Z9UOC.3EWNDPT0%$5%$5V50RZ1Z47+96T47746HL664631"
}
