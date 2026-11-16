package com.example.util

import android.content.Context
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class PhoneContact(
    val contactId: String,
    val displayName: String,
    val phoneNumber: String,
    val normalizedPhone: String
)

object ContactsHelper {

    fun normalizePhoneNumber(rawPhone: String, defaultCountryCode: String = "+1"): String {
        val trimmed = rawPhone.trim()
        if (trimmed.isBlank()) return ""

        // Strip spaces, punctuation, extensions; preserve leading +
        val platformNormalized = PhoneNumberUtils.normalizeNumber(trimmed) ?: trimmed
        val digitsOnly = platformNormalized.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return ""

        return when {
            platformNormalized.startsWith("+") -> "+$digitsOnly"
            platformNormalized.startsWith("00") && digitsOnly.length > 4 -> "+${digitsOnly.drop(2)}"
            digitsOnly.length == 10 -> "${defaultCountryCode.trim().ifBlank { "+1" }}$digitsOnly"
            else -> "+$digitsOnly"
        }
    }

    fun candidateNormalizedPhones(rawPhone: String, defaultCountryCode: String = "+1"): List<String> {
        val primary = normalizePhoneNumber(rawPhone, defaultCountryCode)
        if (primary.isBlank()) return emptyList()
        val digits = primary.filter { it.isDigit() }
        val candidates = linkedSetOf(primary)
        if (digits.length >= 10) {
            candidates.add("+$digits")
            val lastTen = digits.takeLast(10)
            val code = defaultCountryCode.trim().ifBlank { "+1" }
            candidates.add("$code$lastTen")
        }
        return candidates.toList()
    }

    fun hashPhoneNumber(normalizedPhone: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedPhone.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun loadDeviceContacts(
        context: Context,
        defaultCountryCode: String = "+1"
    ): List<PhoneContact> = withContext(Dispatchers.IO) {
        val result = mutableListOf<PhoneContact>()
        val seenNormalized = HashSet<String>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val normIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)

            while (cursor.moveToNext()) {
                val contactId = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else ""
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                val rawNum = if (numIdx >= 0) cursor.getString(numIdx).orEmpty().trim() else ""
                val systemNorm = if (normIdx >= 0) cursor.getString(normIdx).orEmpty().trim() else ""

                val displayRaw = rawNum.ifBlank { systemNorm }
                if (displayRaw.isBlank()) continue

                val normalized = normalizePhoneNumber(
                    systemNorm.ifBlank { rawNum },
                    defaultCountryCode
                )
                if (normalized.isBlank() || !seenNormalized.add(normalized)) continue

                result.add(
                    PhoneContact(
                        contactId = contactId.ifBlank { normalized },
                        displayName = name.ifBlank { displayRaw },
                        phoneNumber = displayRaw,
                        normalizedPhone = normalized
                    )
                )
            }
        }
        result
    }
}
