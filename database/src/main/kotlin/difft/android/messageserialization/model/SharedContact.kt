package difft.android.messageserialization.model

import java.io.Serializable

data class SharedContact(
    val name: SharedContactName?,
    val phone: List<SharedContactPhone>?,
    val avatar: SharedContactAvatar?,
    val email: List<SharedContactEmail>?,
    val address: List<SharedContactPostalAddress>?,
    val organization: String?,
):Serializable

data class SharedContactName(
    val givenName: String?,
    val familyName: String?,
    val prefix: String?,
    val suffix: String?,
    val middleName: String?,
    val displayName: String?
):Serializable

data class SharedContactPhone(
    val value: String?,
    val type: Int,
    val label: String?
):Serializable

data class SharedContactEmail(
    val value: String,
    val type: Int,
    val label: String?
):Serializable

data class SharedContactAvatar(
    val attachment: Attachment?,
    val isProfile: Boolean
):Serializable

data class SharedContactPostalAddress(
    val type: Int,
    val label: String?,
    val street: String?,
    val pobox: String?,
    val neighborhood: String?,
    val city: String?,
    val region: String?,
    val postcode: String?,
    val country: String?
):Serializable
