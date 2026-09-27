package com.ledgernoir.tracker.sms

import android.content.Context
import android.provider.Telephony

/** A single raw SMS read from the device inbox. */
data class RawSms(
    val address: String,
    val body: String,
    val dateMillis: Long,
    val id: Long,
)

/**
 * Reads the device SMS inbox via the content resolver. Requires READ_SMS.
 * Pure read — no network, no filtering. Filtering happens in the importer.
 */
class SmsHistoryReader(private val context: Context) {

    /**
     * Queries the SMS inbox, newest first. Returns an empty list if the cursor
     * is null (e.g. permission missing / no provider). Null-safe per column.
     */
    fun readInbox(): List<RawSms> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            Telephony.Sms.Inbox._ID,
            Telephony.Sms.Inbox.ADDRESS,
            Telephony.Sms.Inbox.BODY,
            Telephony.Sms.Inbox.DATE,
        )

        val out = ArrayList<RawSms>()
        resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.Inbox.DATE} DESC",
        )?.use { cursor ->
            val idIdx      = cursor.getColumnIndex(Telephony.Sms.Inbox._ID)
            val addressIdx = cursor.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)
            val bodyIdx    = cursor.getColumnIndex(Telephony.Sms.Inbox.BODY)
            val dateIdx    = cursor.getColumnIndex(Telephony.Sms.Inbox.DATE)

            while (cursor.moveToNext()) {
                val id      = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                val address = if (addressIdx >= 0) cursor.getString(addressIdx) ?: "" else ""
                val body    = if (bodyIdx >= 0) cursor.getString(bodyIdx) ?: "" else ""
                val date    = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                if (address.isBlank()) continue
                out.add(RawSms(address = address, body = body, dateMillis = date, id = id))
            }
        }
        return out
    }
}
