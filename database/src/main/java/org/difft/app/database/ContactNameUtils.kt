//package org.difft.app.database
//
//import com.difft.android.base.log.lumberjack.L
//import com.difft.android.base.utils.Base58
//import org.difft.app.database.models.ContactorModel
//
//
///**
// * 获取联系人显示名称，包含备注信息
// * 优先级：remark > groupRemark > publicName > name > groupDisplayName > id
// */
//fun ContactorModel.getDisplayNameForUI(): String {
//    return getFirstNonEmptyValue(
//        remark,
//        groupMemberContactor?.remark,
//        publicName,
//        name,
//        groupMemberContactor?.displayName,
//        id.formatBase58Id()
//    )
//}
//
///**
// * 获取不含有remark的显示名称，避免泄露隐私（比如@功能，快速建群功能等）
// * 优先级：publicName > name > groupDisplayName > id
// */
//fun ContactorModel.getDisplayNameWithoutRemarkForUI(): String {
//    return getFirstNonEmptyValue(
//        publicName,
//        name,
//        groupMemberContactor?.displayName,
//        id.formatBase58Id()
//    )
//}
//
//fun getFirstNonEmptyValue(vararg values: String?): String {
//    return values.firstOrNull { !it.isNullOrEmpty() } ?: ""
//}
//
///**
// * 格式化联系人ID为显示格式：TT-Base58(id) 或 Base58(id)
// * 例如：id是+78262753445，展示为：TT-base58Encoded 或 base58Encoded
// * @param showPrefix 是否显示TT-前缀，默认为true
// */
//fun String.formatBase58Id(showPrefix: Boolean = true): String {
//    return try {
//        // 去掉开头的+号
//        val cleanId = this.removePrefix("+")
//        // 转换为数字类型
//        val numericId = cleanId.toLong()
//        val base58Encoded = Base58.encode(numericId)
//        if (showPrefix) "TT-$base58Encoded" else base58Encoded
//    } catch (e: Exception) {
//        L.e { "[ContactorUtil] formatContactId fail for id: $this, error: ${e.stackTraceToString()}" }
//        if (showPrefix) "TT-${this.takeLast(7)}" else this.takeLast(7) // fallback to last 7 characters
//    }
//}